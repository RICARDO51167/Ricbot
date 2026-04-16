package ricbot.integration.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ricbot.tool.api.ToolRegistry;
import ricbot.infra.config.Config;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP (Model Context Protocol) 加载器。
 */
public class MCPLoader implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MCPLoader.class);

    private final ToolRegistry registry;
    private volatile Map<String, Object> serverConfigs;
    private final Map<String, MCPServerConnection> connections = new ConcurrentHashMap<>();

    public MCPLoader(ToolRegistry registry, Map<String, Object> serverConfigs) {
        this.registry = registry;
        this.serverConfigs = serverConfigs != null ? new LinkedHashMap<>(serverConfigs) : Collections.emptyMap();
    }

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

    public synchronized void load() {
        reloadAll();
    }

    public synchronized void reload(Map<String, Object> newServerConfigs) {
        this.serverConfigs = newServerConfigs != null ? new LinkedHashMap<>(newServerConfigs) : Collections.emptyMap();
        reloadAll();
    }

    public synchronized void reloadAll() {
        Map<String, Config.MCPServerConfig> parsed = MCPAdapters.parseMcpServers(serverConfigs);
        disconnectAll("reload_all");

        if (parsed.isEmpty()) {
            log.info("MCP: 未发现可用 server 配置，跳过加载。");
            return;
        }

        Map<String, MCPServerConnection> newConnections = MCPAdapters.connectMcpServers(parsed, registry);
        connections.putAll(newConnections);
        int failed = parsed.size() - newConnections.size();
        log.info("MCP: 加载完成，configured={}, connected={}, failed={}", parsed.size(), newConnections.size(), failed);
    }

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

    public synchronized boolean removeServer(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return false;
        }
        return disconnectServer(serverName, "remove_server");
    }

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

    public synchronized Map<String, MCPServerConnection> getConnections() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(connections));
    }

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
