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

    /**
     * 构造函数
     *
     * @param registry      工具注册表实例
     * @param serverConfigs MCP 服务器配置映射
     */
    public MCPLoader(ToolRegistry registry, Map<String, Object> serverConfigs) {
        this.registry = registry;
        // 如果传入的配置为 null，则使用空映射，避免后续空指针异常
        this.serverConfigs = serverConfigs != null ? new LinkedHashMap<>(serverConfigs) : Collections.emptyMap();
    }

    /**
     * 强类型配置入口，便于上层逐步去掉 Map<String, Object> 的弱类型输入。
     */
    public MCPLoader(ToolRegistry registry, Map<String, Config.MCPServerConfig> typedServerConfigs, boolean typed) {
        this.registry = registry;
        if (typedServerConfigs == null) {
            this.serverConfigs = Collections.emptyMap();
            return;
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.putAll(typedServerConfigs);
        this.serverConfigs = raw;
    }

    /**
     * 加载并连接所有配置的 MCP 服务器
     */
    public synchronized void load() {
        reloadAll();
    }

    /**
     * 更新配置并重新加载所有连接。
     */
    public synchronized void reload(Map<String, Object> newServerConfigs) {
        this.serverConfigs = newServerConfigs != null ? new LinkedHashMap<>(newServerConfigs) : Collections.emptyMap();
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
            return;
        }

        // 建立与服务器的连接，并将连接对象存入 connections 映射中，同时注册工具到 registry
        Map<String, MCPServerConnection> newConnections = MCPAdapters.connectMcpServers(parsed, registry);
        connections.putAll(newConnections);
        int failed = parsed.size() - newConnections.size();
        log.info("MCP: 加载完成，configured={}, connected={}, failed={}", parsed.size(), newConnections.size(), failed);
    }

    /**
     * 重新加载单个 server（最小热更新能力）。
     */
    public synchronized boolean reloadServer(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return false;
        }
        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(serverConfigs);
        Config.MCPServerConfig cfg = parsed.get(serverName);
        if (cfg == null) {
            log.warn("MCP: reloadServer 失败，配置中不存在 server '{}'", serverName);
            return false;
        }

        disconnectServer(serverName, "reload_server");
        Map<String, MCPServerConnection> connected = MCPAdapters.connectMcpServers(Map.of(serverName, cfg), registry);
        MCPServerConnection conn = connected.get(serverName);
        if (conn == null) {
            log.warn("MCP: server '{}' 重连失败", serverName);
            return false;
        }
        connections.put(serverName, conn);
        log.info("MCP: server '{}' 重连成功", serverName);
        return true;
    }

    /**
     * 移除并关闭单个 server。
     */
    public synchronized boolean removeServer(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return false;
        }
        return disconnectServer(serverName, "remove_server");
    }

    /**
     * 查询 server 连接状态。
     */
    public synchronized Map<String, String> getServerStatuses() {
        Map<String, String> statuses = new LinkedHashMap<>();
        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(serverConfigs);
        for (String name : parsed.keySet()) {
            statuses.put(name, connections.containsKey(name) ? "connected" : "disconnected");
        }
        for (String name : connections.keySet()) {
            statuses.putIfAbsent(name, "connected");
        }
        return statuses;
    }

    /**
     * 获取所有已建立的 MCP 服务器连接
     *
     * @return 连接映射
     */
    public synchronized Map<String, MCPServerConnection> getConnections() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(connections));
    }

    /**
     * 关闭所有 MCP 服务器连接，实现 AutoCloseable 接口
     */
    @Override
    public synchronized void close() {
        disconnectAll("close");
    }

    private void disconnectAll(String reason) {
        for (String name : new ArrayList<>(connections.keySet())) {
            disconnectServer(name, reason);
        }
    }

    private boolean disconnectServer(String serverName, String reason) {
        MCPServerConnection conn = connections.remove(serverName);

        unregisterServerTools(serverName);
        if (conn == null) {
            return false;
        }

        try {
            conn.close();
            log.info("MCP: server '{}' 已关闭 ({})", serverName, reason);
            return true;
        } catch (Exception e) {
            log.warn("MCP: 关闭 server '{}' 失败 ({})", serverName, reason, e);
            return false;
        }
    }

    private void unregisterServerTools(String serverName) {
        String prefix = "mcp_" + serverName + "_";
        for (String toolName : new ArrayList<>(registry.toolNames())) {
            if (toolName.startsWith(prefix)) {
                registry.unregister(toolName);
            }
        }
    }
}
