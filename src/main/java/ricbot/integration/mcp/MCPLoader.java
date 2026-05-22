package ricbot.integration.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ricbot.tool.api.ToolRegistry;
import ricbot.infra.config.Config;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP (Model Context Protocol) 加载器。
 *
 * 说明：
 * - 作为 MCP 工具注册与连接管理的统一装配入口；
 * - 由 AgentLoop 持有并在 stop() 时统一关闭连接。
 */
public class MCPLoader implements AutoCloseable {
    // 日志记录器，用于记录类运行时的信息
    private static final Logger log = LoggerFactory.getLogger(MCPLoader.class);

    // 工具注册表，用于注册从 MCP 服务器获取的工具
    private final ToolRegistry registry;
    // 原始的服务端配置映射，键为服务名，值为配置对象
    private volatile Map<String, Object> serverConfigs;
    // 已建立的 MCP 服务器连接映射，键为服务名，值为连接对象
    private final Map<String, MCPServerConnection> connections = new ConcurrentHashMap<>();
    private volatile Map<String, MCPAdapters.MCPServerLoadInfo> lastLoadInfo = new LinkedHashMap<>();

    /**
     * 构造函数
     *
     * @param registry      工具注册表实例
     * @param serverConfigs MCP 服务器配置映射
     */
    public MCPLoader(ToolRegistry registry, Map<String, Object> serverConfigs) {
        // 初始化工具注册表
        this.registry = registry;
        // 如果传入的配置为 null，则使用空映射，避免后续空指针异常；否则创建新的 LinkedHashMap 以保留插入顺序
        this.serverConfigs = serverConfigs != null ? new LinkedHashMap<>(serverConfigs) : Collections.emptyMap();
    }

    /**
     * 强类型配置入口，便于上层逐步去掉 Map<String, Object> 的弱类型输入。
     */
    public MCPLoader(ToolRegistry registry, Map<String, Config.MCPServerConfig> typedServerConfigs, boolean typed) {
        // 初始化工具注册表
        this.registry = registry;
        // 如果传入的强类型配置为 null，则初始化为空映射并返回
        if (typedServerConfigs == null) {
            this.serverConfigs = Collections.emptyMap();
            return;
        }
        // 创建一个新的 LinkedHashMap 用于存储原始配置
        Map<String, Object> raw = new LinkedHashMap<>();
        // 将强类型配置全部放入原始配置映射中
        raw.putAll(typedServerConfigs);
        // 赋值给成员变量
        this.serverConfigs = raw;
    }

    /**
     * 加载并连接所有配置的 MCP 服务器
     */
    public synchronized void load() {
        // 调用重新加载所有服务器的方法
        reloadAll();
    }

    /**
     * 重新加载全部服务器（幂等安全）。
     */
    public synchronized void reloadAll() {
        // 解析原始配置为标准的 MCPServerConfig 对象
        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(serverConfigs);
        // 先安全卸载旧连接和其工具，避免重复注册和连接泄漏
        disconnectAll("reload_all");

        // 如果没有解析出任何有效配置，记录日志并返回
        if (parsed.isEmpty()) {
            log.info("MCP: 未发现可用 server 配置，跳过加载。");
            lastLoadInfo = new LinkedHashMap<>();
            return;
        }

        // 建立与服务器的连接，并将连接对象存入 connections 映射中，同时注册工具到 registry
        MCPAdapters.MCPConnectReport report = MCPAdapters.connectMcpServersDetailed(parsed, registry);
        Map<String, MCPServerConnection> newConnections = report.connections();
        lastLoadInfo = new LinkedHashMap<>(report.servers());
        // 将新建立的连接全部放入 connections 映射中
        connections.putAll(newConnections);
        // 计算连接失败的服务器数量
        int failed = parsed.size() - newConnections.size();
        // 记录加载完成的日志，包括配置总数、成功连接数和失败数
        log.info("MCP: 加载完成，configured={}, connected={}, failed={}", parsed.size(), newConnections.size(), failed);
    }

    /**
     * 重新加载单个 server（最小热更新能力）。
     */
    public synchronized boolean reloadServer(String serverName) {
        // 如果服务器名称为空或空白，直接返回 false
        if (serverName == null || serverName.isBlank()) {
            return false;
        }
        // 解析原始配置为标准的 MCPServerConfig 对象
        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(serverConfigs);
        // 获取指定服务器名称的配置对象
        Config.MCPServerConfig cfg = parsed.get(serverName);
        // 如果配置不存在，记录警告日志并返回 false
        if (cfg == null) {
            log.warn("MCP: reloadServer 失败，配置中不存在 server '{}'", serverName);
            return false;
        }

        // 断开指定服务器的连接
        disconnectServer(serverName, "reload_server");
        // 尝试重新连接指定的服务器
        MCPAdapters.MCPConnectReport report = MCPAdapters.connectMcpServersDetailed(Map.of(serverName, cfg), registry);
        Map<String, MCPServerConnection> connected = report.connections();
        Map<String, MCPAdapters.MCPServerLoadInfo> info = new LinkedHashMap<>(lastLoadInfo);
        info.putAll(report.servers());
        lastLoadInfo = info;
        // 获取新建立的连接对象
        MCPServerConnection conn = connected.get(serverName);
        // 如果连接对象为 null，表示重连失败，记录警告日志并返回 false
        if (conn == null) {
            log.warn("MCP: server '{}' 重连失败", serverName);
            return false;
        }
        // 将新连接放入 connections 映射中
        connections.put(serverName, conn);
        // 记录重连成功的日志
        log.info("MCP: server '{}' 重连成功", serverName);
        // 返回 true 表示重连成功
        return true;
    }

    /**
     * 移除并关闭单个 server。
     */
    public synchronized boolean removeServer(String serverName) {
        // 如果服务器名称为空或空白，直接返回 false
        if (serverName == null || serverName.isBlank()) {
            return false;
        }
        // 调用断开服务器连接的方法，并返回结果
        return disconnectServer(serverName, "remove_server");
    }

    /**
     * 查询 server 连接状态。
     */
    public synchronized Map<String, String> getServerStatuses() {
        // 创建一个新的 LinkedHashMap 用于存储服务器状态
        Map<String, String> statuses = new LinkedHashMap<>();
        // 解析原始配置为标准的 MCPServerConfig 对象
        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(serverConfigs);
        // 遍历所有解析出的服务器配置
        for (String name : parsed.keySet()) {
            // 如果 connections 中包含该服务器名称，则状态为 connected，否则为 disconnected
            statuses.put(name, connections.containsKey(name) ? "connected" : "disconnected");
        }
        // 遍历所有已建立的连接，确保它们的状态被正确标记
        for (String name : connections.keySet()) {
            // 如果状态映射中不存在该服务器名称，则添加并标记为 connected
            statuses.putIfAbsent(name, "connected");
        }
        // 返回服务器状态映射
        return statuses;
    }

    public synchronized Map<String, Object> dashboard(int healthTimeoutSeconds) {
        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(serverConfigs);
        Map<String, String> statuses = getServerStatuses();
        Map<String, MCPAdapters.MCPServerHealth> healthByName = new LinkedHashMap<>();
        for (MCPAdapters.MCPServerHealth health : MCPAdapters.healthReport(connections, healthTimeoutSeconds)) {
            healthByName.put(health.name(), health);
        }

        List<Map<String, Object>> servers = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        names.addAll(parsed.keySet());
        names.addAll(statuses.keySet());
        for (String name : names) {
            Config.MCPServerConfig cfg = parsed.get(name);
            MCPAdapters.MCPServerHealth health = healthByName.get(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("status", statuses.getOrDefault(name, "disconnected"));
            row.put("health", health != null ? health.status() : statuses.getOrDefault(name, "disconnected"));
            row.put("tool_count", health != null ? health.toolCount() : countRegisteredTools(name));
            row.put("registered_count", countRegisteredTools(name));
            row.put("error", health != null ? health.error() : "");
            if (cfg != null) {
                row.put("type", resolveType(cfg));
                row.put("tool_timeout", cfg.getToolTimeout());
                row.put("enabled_tools", cfg.getEnabledTools());
            }
            servers.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured_count", parsed.size());
        body.put("connected_count", connections.size());
        body.put("servers", servers);
        body.put("tools", registeredMcpTools());
        return body;
    }

    /**
     * 获取所有已建立的 MCP 服务器连接
     *
     * @return 连接映射
     */
    public synchronized Map<String, MCPServerConnection> getConnections() {
        // 返回一个不可修改的 LinkedHashMap 副本，防止外部修改内部状态
        return Collections.unmodifiableMap(new LinkedHashMap<>(connections));
    }

    public synchronized Map<String, Object> getServerConfigs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(serverConfigs));
    }

    public synchronized Map<String, MCPAdapters.MCPServerLoadInfo> getLastLoadInfo() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(lastLoadInfo));
    }

    private List<Map<String, Object>> registeredMcpTools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String toolName : registry.toolNames()) {
            if (!toolName.startsWith("mcp_")) {
                continue;
            }
            ToolRegistry.ToolPolicy policy = registry.policyFor(toolName);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", toolName);
            row.put("server", serverNameFromTool(toolName));
            row.put("read_only", policy.readOnly());
            row.put("exclusive", policy.exclusive());
            row.put("concurrent_safe", policy.concurrentSafe());
            row.put("risk", policy.risk());
            out.add(row);
        }
        return out;
    }

    private int countRegisteredTools(String serverName) {
        String prefix = "mcp_" + serverName + "_";
        int count = 0;
        for (String toolName : registry.toolNames()) {
            if (toolName.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private String serverNameFromTool(String toolName) {
        if (toolName == null || !toolName.startsWith("mcp_")) {
            return "";
        }
        String rest = toolName.substring("mcp_".length());
        int idx = rest.indexOf('_');
        return idx > 0 ? rest.substring(0, idx) : rest;
    }

    private String resolveType(Config.MCPServerConfig cfg) {
        String type = cfg.getType();
        if (type != null && !type.isBlank()) {
            return type;
        }
        if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
            return "stdio";
        }
        if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
            return cfg.getUrl().replaceAll("/+$", "").endsWith("/sse") ? "sse" : "streamableHttp";
        }
        return "unknown";
    }

    /**
     * 关闭所有 MCP 服务器连接，实现 AutoCloseable 接口
     */
    @Override
    public synchronized void close() {
        // 断开所有服务器连接，原因标记为 close
        disconnectAll("close");
    }

    /**
     * 断开所有服务器连接
     *
     * @param reason 断开连接的原因
     */
    private void disconnectAll(String reason) {
        // 创建 connections 键集合的副本，避免在迭代过程中修改集合导致异常
        for (String name : new ArrayList<>(connections.keySet())) {
            // 逐个断开服务器连接
            disconnectServer(name, reason);
        }
    }

    /**
     * 断开指定服务器的连接
     *
     * @param serverName 服务器名称
     * @param reason     断开连接的原因
     * @return 是否成功断开连接
     */
    private boolean disconnectServer(String serverName, String reason) {
        // 从 connections 映射中移除指定服务器的连接对象
        MCPServerConnection conn = connections.remove(serverName);

        // 注销该服务器注册的所有工具
        unregisterServerTools(serverName);
        // 如果连接对象为 null，表示没有需要断开的连接，返回 false
        if (conn == null) {
            return false;
        }

        try {
            // 关闭连接
            conn.close();
            // 记录成功关闭连接的日志
            log.info("MCP: server '{}' 已关闭 ({})", serverName, reason);
            // 返回 true 表示成功断开
            return true;
        } catch (Exception e) {
            // 捕获关闭连接时的异常，记录警告日志
            log.warn("MCP: 关闭 server '{}' 失败 ({})", serverName, reason, e);
            // 返回 false 表示断开失败
            return false;
        }
    }

    /**
     * 注销指定服务器注册的所有工具
     *
     * @param serverName 服务器名称
     */
    private void unregisterServerTools(String serverName) {
        // 构建工具名称的前缀，格式为 mcp_{serverName}_
        String prefix = "mcp_" + serverName + "_";
        // 创建工具名称列表的副本，避免在迭代过程中修改集合导致异常
        for (String toolName : new ArrayList<>(registry.toolNames())) {
            // 如果工具名称以前缀开头，说明是该服务器注册的工具
            if (toolName.startsWith(prefix)) {
                // 从注册表中注销该工具
                registry.unregister(toolName);
            }
        }
    }
}
