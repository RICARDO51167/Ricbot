package ricbot.tool.mcp;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;
import ricbot.infra.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * MCP 适配层：
 * 负责连接 MCP Server，并把 MCP 的 tool/resource/prompt 包装成 nanobot Tool。
 *
 * 对应 Python 文件 mcp.py。
 */
public final class MCPAdapters {

    private static final Logger log = LoggerFactory.getLogger(MCPAdapters.class);

    private MCPAdapters() {
    }

    // =========================================================
    // Schema 规范化
    // =========================================================

    /**
     * 从 oneOf / anyOf 中提取“唯一非 null 分支”。
     *
     * 例如：
     * [
     *   {"type":"null"},
     *   {"type":"string"}
     * ]
     *
     * -> 返回 {"type":"string"}, true
     */
    @SuppressWarnings("unchecked")
    public static NullableBranch extractNullableBranch(Object options) {
        if (!(options instanceof List<?> list)) {
            return null;
        }

        List<Map<String, Object>> nonNull = new ArrayList<>();
        boolean sawNull = false;

        for (Object option : list) {
            if (!(option instanceof Map<?, ?> map)) {
                return null;
            }

            Object type = ((Map<String, Object>) map).get("type");
            if ("null".equals(type)) {
                sawNull = true;
                continue;
            }

            nonNull.add((Map<String, Object>) map);
        }

        if (sawNull && nonNull.size() == 1) {
            return new NullableBranch(nonNull.get(0), true);
        }

        return null;
    }

    /**
     * 把 MCP schema 规范化为更适合 nanobot / OpenAI function-tool 风格的 schema。
     *
     * 主要处理：
     * - type: ["string", "null"]
     * - oneOf / anyOf 中的 nullable 分支
     * - 递归处理 properties / items
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeSchemaForOpenAI(Object schema) {
        if (!(schema instanceof Map<?, ?>)) {
            return new HashMap<>(Map.of(
                    "type", "object",
                    "properties", new HashMap<String, Object>()
            ));
        }

        Map<String, Object> normalized = new HashMap<>((Map<String, Object>) schema);

        Object rawType = normalized.get("type");
        if (rawType instanceof List<?> typeList) {
            List<Object> nonNull = new ArrayList<>();
            boolean hasNull = false;

            for (Object item : typeList) {
                if ("null".equals(item)) {
                    hasNull = true;
                } else {
                    nonNull.add(item);
                }
            }

            if (hasNull && nonNull.size() == 1) {
                normalized.put("type", nonNull.get(0));
                normalized.put("nullable", true);
            }
        }

        for (String key : List.of("oneOf", "anyOf")) {
            NullableBranch branch = extractNullableBranch(normalized.get(key));
            if (branch != null) {
                Map<String, Object> merged = new HashMap<>();
                for (Map.Entry<String, Object> entry : normalized.entrySet()) {
                    if (!entry.getKey().equals(key)) {
                        merged.put(entry.getKey(), entry.getValue());
                    }
                }
                merged.putAll(branch.branch());
                merged.put("nullable", true);
                normalized = merged;
                break;
            }
        }

        Object propertiesObj = normalized.get("properties");
        if (propertiesObj instanceof Map<?, ?> properties) {
            Map<String, Object> newProps = new HashMap<>();
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Map<?, ?>) {
                    newProps.put(String.valueOf(entry.getKey()), normalizeSchemaForOpenAI(value));
                } else {
                    newProps.put(String.valueOf(entry.getKey()), value);
                }
            }
            normalized.put("properties", newProps);
        }

        Object itemsObj = normalized.get("items");
        if (itemsObj instanceof Map<?, ?>) {
            normalized.put("items", normalizeSchemaForOpenAI(itemsObj));
        }

        if (!"object".equals(normalized.get("type"))) {
            return normalized;
        }

        normalized.putIfAbsent("properties", new HashMap<String, Object>());
        normalized.putIfAbsent("required", new ArrayList<String>());

        return normalized;
    }

    // =========================================================
    // MCP Tool Wrapper
    // =========================================================

    /**
     * 包装 MCP server 的单个 tool。
     */
    public static class MCPToolWrapper extends Tool {

        private final MCPClientSession session;
        private final String originalName;
        private final String name;
        private final String description;
        private final Map<String, Object> parameters;
        private final int toolTimeout;

        public MCPToolWrapper(
                MCPClientSession session,
                String serverName,
                MCPToolDefinition toolDef,
                int toolTimeout
        ) {
            this.session = session;
            this.originalName = toolDef.getName();
            this.name = "mcp_" + serverName + "_" + toolDef.getName();
            this.description = toolDef.getDescription() != null
                    ? toolDef.getDescription()
                    : toolDef.getName();

            Object rawSchema = toolDef.getInputSchema() != null
                    ? toolDef.getInputSchema()
                    : Map.of("type", "object", "properties", Map.of());

            this.parameters = normalizeSchemaForOpenAI(rawSchema);
            this.toolTimeout = toolTimeout;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        @Override
        public Object execute(Map<String, Object> kwargs) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<MCPToolResult> future = executor.submit(() ->
                        session.callTool(originalName, kwargs)
                );

                MCPToolResult result = future.get(toolTimeout, TimeUnit.SECONDS);

                List<String> parts = new ArrayList<>();
                for (Object block : result.getContent()) {
                    if (block instanceof MCPTextContent text) {
                        parts.add(text.getText());
                    } else {
                        parts.add(String.valueOf(block));
                    }
                }
                return parts.isEmpty() ? "(no output)" : String.join("\n", parts);

            } catch (TimeoutException e) {
                log.warn("MCP tool '{}' timed out after {}s", name, toolTimeout);
                return "(MCP tool call timed out after " + toolTimeout + "s)";
            } catch (CancellationException e) {
                log.warn("MCP tool '{}' was cancelled by server/SDK", name);
                return "(MCP tool call was cancelled)";
            } catch (Exception e) {
                log.error("MCP tool '{}' failed: {}: {}", name, e.getClass().getSimpleName(), e.getMessage(), e);
                return "(MCP tool call failed: " + e.getClass().getSimpleName() + ")";
            } finally {
                executor.shutdownNow();
            }
        }
    }

    // =========================================================
    // MCP Resource Wrapper
    // =========================================================

    /**
     * 把 MCP resource 包装成只读 Tool。
     */
    public static class MCPResourceWrapper extends Tool {

        private final MCPClientSession session;
        private final String uri;
        private final String name;
        private final String description;
        private final int resourceTimeout;

        public MCPResourceWrapper(
                MCPClientSession session,
                String serverName,
                MCPResourceDefinition resourceDef,
                int resourceTimeout
        ) {
            this.session = session;
            this.uri = resourceDef.getUri();
            this.name = "mcp_" + serverName + "_resource_" + resourceDef.getName();

            String desc = resourceDef.getDescription() != null
                    ? resourceDef.getDescription()
                    : resourceDef.getName();

            this.description = "[MCP Resource] " + desc + "\nURI: " + uri;
            this.resourceTimeout = resourceTimeout;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }
        public Map<String, Object> getParameters() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of()
            );
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Object execute(Map<String, Object> kwargs) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<MCPResourceResult> future = executor.submit(() ->
                        session.readResource(uri)
                );

                MCPResourceResult result = future.get(resourceTimeout, TimeUnit.SECONDS);

                List<String> parts = new ArrayList<>();
                for (Object block : result.getContents()) {
                    if (block instanceof MCPTextResourceContents text) {
                        parts.add(text.getText());
                    } else if (block instanceof MCPBlobResourceContents blob) {
                        parts.add("[Binary resource: " + blob.getBlobLength() + " bytes]");
                    } else {
                        parts.add(String.valueOf(block));
                    }
                }

                return parts.isEmpty() ? "(no output)" : String.join("\n", parts);

            } catch (TimeoutException e) {
                log.warn("MCP resource '{}' timed out after {}s", name, resourceTimeout);
                return "(MCP resource read timed out after " + resourceTimeout + "s)";
            } catch (CancellationException e) {
                log.warn("MCP resource '{}' was cancelled by server/SDK", name);
                return "(MCP resource read was cancelled)";
            } catch (Exception e) {
                log.error("MCP resource '{}' failed: {}: {}", name, e.getClass().getSimpleName(), e.getMessage(), e);
                return "(MCP resource read failed: " + e.getClass().getSimpleName() + ")";
            } finally {
                executor.shutdownNow();
            }
        }
    }

    // =========================================================
    // MCP Prompt Wrapper
    // =========================================================

    /**
     * 把 MCP prompt 包装成只读 Tool。
     */
    public static class MCPPromptWrapper extends Tool {

        private final MCPClientSession session;
        private final String promptName;
        private final String name;
        private final String description;
        private final Map<String, Object> parameters;
        private final int promptTimeout;

        public MCPPromptWrapper(
                MCPClientSession session,
                String serverName,
                MCPPromptDefinition promptDef,
                int promptTimeout
        ) {
            this.session = session;
            this.promptName = promptDef.getName();
            this.name = "mcp_" + serverName + "_prompt_" + promptDef.getName();

            String desc = promptDef.getDescription() != null
                    ? promptDef.getDescription()
                    : promptDef.getName();

            this.description = "[MCP Prompt] " + desc
                    + "\nReturns a filled prompt template that can be used as a workflow guide.";
            this.promptTimeout = promptTimeout;

            Map<String, Object> properties = new HashMap<>();
            List<String> required = new ArrayList<>();

            if (promptDef.getArguments() != null) {
                for (MCPPromptArgument arg : promptDef.getArguments()) {
                    Map<String, Object> prop = new HashMap<>();
                    prop.put("type", "string");
                    if (arg.getDescription() != null) {
                        prop.put("description", arg.getDescription());
                    }
                    properties.put(arg.getName(), prop);
                    if (arg.isRequired()) {
                        required.add(arg.getName());
                    }
                }
            }

            this.parameters = Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", required
            );
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> getParameters() {
            return parameters;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Object execute(Map<String, Object> kwargs) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<MCPPromptResult> future = executor.submit(() ->
                        session.getPrompt(promptName, kwargs)
                );

                MCPPromptResult result = future.get(promptTimeout, TimeUnit.SECONDS);

                List<String> parts = new ArrayList<>();
                for (MCPPromptMessage message : result.getMessages()) {
                    Object content = message.getContent();
                    if (content instanceof MCPTextContent text) {
                        parts.add(text.getText());
                    } else if (content instanceof List<?> list) {
                        for (Object block : list) {
                            if (block instanceof MCPTextContent t) {
                                parts.add(t.getText());
                            } else {
                                parts.add(String.valueOf(block));
                            }
                        }
                    } else {
                        parts.add(String.valueOf(content));
                    }
                }

                return parts.isEmpty() ? "(no output)" : String.join("\n", parts);

            } catch (TimeoutException e) {
                log.warn("MCP prompt '{}' timed out after {}s", name, promptTimeout);
                return "(MCP prompt call timed out after " + promptTimeout + "s)";
            } catch (CancellationException e) {
                log.warn("MCP prompt '{}' was cancelled by server/SDK", name);
                return "(MCP prompt call was cancelled)";
            } catch (Exception e) {
                log.error("MCP prompt '{}' failed: {}: {}", name, e.getClass().getSimpleName(), e.getMessage(), e);
                return "(MCP prompt call failed: " + e.getClass().getSimpleName() + ")";
            } finally {
                executor.shutdownNow();
            }
        }
    }

    // =========================================================
    // 连接 MCP Servers
    // =========================================================

    /**
     * 连接所有配置好的 MCP server，并把其能力注册进 ToolRegistry。
     *
     * 返回：
     * serverName -> MCPServerConnection
     *
     * 对应 Python 的 connect_mcp_servers(...)
     */
    public static Map<String, MCPServerConnection> connectMcpServers(
            Map<String, Config.MCPServerConfig> mcpServers,
            ToolRegistry registry
    ) {
        Map<String, MCPServerConnection> serverConnections = new HashMap<>();
        List<Future<ServerConnectResult>> futures = new ArrayList<>();

        ExecutorService executor = Executors.newCachedThreadPool();

        List<String> names = new ArrayList<>(mcpServers.keySet());

        for (String name : names) {
            Config.MCPServerConfig cfg = mcpServers.get(name);
            futures.add(executor.submit(() -> connectSingleServer(name, cfg, registry)));
        }

        for (int i = 0; i < futures.size(); i++) {
            String name = names.get(i);
            try {
                ServerConnectResult result = futures.get(i).get();
                if (result != null && result.connection() != null) {
                    serverConnections.put(result.name(), result.connection());
                }
            } catch (Exception e) {
                log.error("MCP server '{}' connection task failed: {}", name, e.getMessage(), e);
            }
        }

        executor.shutdown();
        return serverConnections;
    }

    /**
     * 连接单个 MCP server。
     */
    private static ServerConnectResult connectSingleServer(
            String name,
            Config.MCPServerConfig cfg,
            ToolRegistry registry
    ) {
        MCPServerConnection connection = null;

        try {
            String transportType = cfg.getType();

            if (transportType == null || transportType.isBlank()) {
                if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
                    transportType = "stdio";
                } else if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
                    transportType = cfg.getUrl().replaceAll("/+$", "").endsWith("/sse")
                            ? "sse"
                            : "streamableHttp";
                } else {
                    log.warn("MCP server '{}': no command or url configured, skipping", name);
                    return new ServerConnectResult(name, null);
                }
            }

            connection = switch (transportType) {
                case "stdio" -> MCPTransportFactory.connectStdio(cfg);
                case "sse" -> MCPTransportFactory.connectSse(cfg);
                case "streamableHttp" -> MCPTransportFactory.connectStreamableHttp(cfg);
                default -> {
                    log.warn("MCP server '{}': unknown transport type '{}'", name, transportType);
                    yield null;
                }
            };

            if (connection == null) {
                return new ServerConnectResult(name, null);
            }

            MCPClientSession session = connection.getSession();
            session.initialize();

            int registeredCount = 0;

            Set<String> enabledTools = new HashSet<>(cfg.getEnabledTools());
            boolean allowAllTools = enabledTools.contains("*");
            Set<String> matchedEnabledTools = new HashSet<>();

            List<MCPToolDefinition> toolDefs = session.listTools();
            List<String> availableRawNames = new ArrayList<>();
            List<String> availableWrappedNames = new ArrayList<>();

            for (MCPToolDefinition toolDef : toolDefs) {
                availableRawNames.add(toolDef.getName());
                availableWrappedNames.add("mcp_" + name + "_" + toolDef.getName());
            }

            for (MCPToolDefinition toolDef : toolDefs) {
                String wrappedName = "mcp_" + name + "_" + toolDef.getName();

                if (!allowAllTools
                        && !enabledTools.contains(toolDef.getName())
                        && !enabledTools.contains(wrappedName)) {
                    log.debug("MCP: skipping tool '{}' from server '{}' (not in enabledTools)", wrappedName, name);
                    continue;
                }

                MCPToolWrapper wrapper = new MCPToolWrapper(
                        session,
                        name,
                        toolDef,
                        cfg.getToolTimeout()
                );
                registry.register(wrapper);
                registeredCount++;

                if (enabledTools.contains(toolDef.getName())) {
                    matchedEnabledTools.add(toolDef.getName());
                }
                if (enabledTools.contains(wrappedName)) {
                    matchedEnabledTools.add(wrappedName);
                }

                log.debug("MCP: registered tool '{}' from server '{}'", wrapper.getName(), name);
            }

            if (!enabledTools.isEmpty() && !allowAllTools) {
                Set<String> unmatched = new TreeSet<>(enabledTools);
                unmatched.removeAll(matchedEnabledTools);

                if (!unmatched.isEmpty()) {
                    log.warn(
                            "MCP server '{}': enabledTools entries not found: {}. Available raw names: {}. Available wrapped names: {}",
                            name,
                            String.join(", ", unmatched),
                            String.join(", ", availableRawNames),
                            String.join(", ", availableWrappedNames)
                    );
                }
            }

            try {
                for (MCPResourceDefinition resource : session.listResources()) {
                    MCPResourceWrapper wrapper = new MCPResourceWrapper(
                            session, name, resource, cfg.getToolTimeout()
                    );
                    registry.register(wrapper);
                    registeredCount++;
                    log.debug("MCP: registered resource '{}' from server '{}'", wrapper.getName(), name);
                }
            } catch (Exception e) {
                log.debug("MCP server '{}': resources not supported or failed: {}", name, e.getMessage());
            }

            try {
                for (MCPPromptDefinition prompt : session.listPrompts()) {
                    MCPPromptWrapper wrapper = new MCPPromptWrapper(
                            session, name, prompt, cfg.getToolTimeout()
                    );
                    registry.register(wrapper);
                    registeredCount++;
                    log.debug("MCP: registered prompt '{}' from server '{}'", wrapper.getName(), name);
                }
            } catch (Exception e) {
                log.debug("MCP server '{}': prompts not supported or failed: {}", name, e.getMessage());
            }

            log.info("MCP server '{}': connected, {} capabilities registered", name, registeredCount);
            return new ServerConnectResult(name, connection);

        } catch (Exception e) {
            String text = String.valueOf(e.getMessage()).toLowerCase();
            String hint = "";

            if (text.contains("parse error")
                    || text.contains("invalid json")
                    || text.contains("unexpected token")
                    || text.contains("jsonrpc")
                    || text.contains("content-length")) {
                hint = " Hint: this looks like stdio protocol pollution. Make sure the MCP server writes "
                        + "only JSON-RPC to stdout and sends logs/debug output to stderr instead.";
            }

            log.error("MCP server '{}': failed to connect: {}{}", name, e.getMessage(), hint);

            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }

            return new ServerConnectResult(name, null);
        }
    }

    // =========================================================
    // 小型返回结构
    // =========================================================

    public record NullableBranch(Map<String, Object> branch, boolean nullable) {
    }

    public record ServerConnectResult(String name, MCPServerConnection connection) {
    }
}