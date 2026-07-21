package ricbot.integration.mcp;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolRegistry;
import ricbot.infra.config.Config;
import ricbot.infra.common.TextParsingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * MCP 适配层：
 * 负责连接 MCP Server，并把 MCP 的 tool/resource/prompt 包装成 ricbot Tool。
 *
 * 对应 Python 文件 mcp.py。
 */
public final class MCPAdapters {

    private static final Logger log = LoggerFactory.getLogger(MCPAdapters.class);
    private static final ExecutorService CALL_EXECUTOR = createCallExecutor();

    private MCPAdapters() {
    }

    private static ExecutorService createCallExecutor() {
        int threads = Math.max(4, Math.min(Runtime.getRuntime().availableProcessors(), 16));
        return new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "mcp-call");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private static ExecutorService createConnectExecutor(int serverCount) {
        int threads = Math.max(1, Math.min(serverCount, Math.min(Runtime.getRuntime().availableProcessors(), 8)));
        return new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(threads, serverCount)),
                r -> {
                    Thread t = new Thread(r, "mcp-connect");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private static <T> T awaitMcpCall(Callable<T> task, int timeoutSeconds) throws Exception {
        Future<T> future = CALL_EXECUTOR.submit(task);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (TimeoutException | ExecutionException | CancellationException e) {
            future.cancel(true);
            throw e;
        }
    }

    /**
     * 解析原始配置 Map，将其转换为 MCP 服务器配置对象。
     *
     * @param raw 原始配置数据，键为服务器名称，值为配置详情（可能是 Config.MCPServerConfig 实例或 Map）
     * @return 解析后的 MCP 服务器配置映射
     */
    public static Map<String, Config.MCPServerConfig> parseMcpServers(Map<String, Object> raw) {
        // 初始化结果映射，保持插入顺序
        Map<String, Config.MCPServerConfig> out = new LinkedHashMap<>();
        
        // 如果输入为空或 null，直接返回空映射
        if (raw == null || raw.isEmpty()) {
            return out;
        }

        // 遍历原始配置中的每个条目
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            // 获取服务器名称
            String name = entry.getKey();
            // 获取配置值
            Object value = entry.getValue();
            
            // 如果值已经是 Config.MCPServerConfig 类型，直接放入结果映射
            if (value instanceof Config.MCPServerConfig typed) {
                out.put(name, typed);
                continue;
            }
            
            // 如果值不是 Map 类型，跳过该条目
            if (!(value instanceof Map<?, ?> map)) {
                continue;
            }

            // 将原始 Map 转换为字符串键的 Map，便于后续处理
            Map<String, Object> cfg = copyObjectMap(map);

            // 创建新的 MCP 服务器配置对象
            Config.MCPServerConfig server = new Config.MCPServerConfig();
            
            // 设置传输类型，优先使用 "type" 字段， fallback 到默认值
            server.setType(stringValue(cfg, "type", server.getType()));
            
            // 设置 URL，优先使用 "url" 字段， fallback 到默认值
            server.setUrl(stringValue(cfg, "url", server.getUrl()));
            
            // 设置命令，优先使用 "command" 字段， fallback 到默认值
            server.setCommand(stringValue(cfg, "command", server.getCommand()));
            
            // 设置参数列表，优先使用 "args" 字段， fallback 到默认值
            server.setArgs(stringListValue(cfg, "args", server.getArgs()));
            
            // 设置环境变量，优先使用 "env" 字段， fallback 到默认值
            server.setEnv(stringMapValue(cfg, "env", server.getEnv()));
            
            // 设置启用的工具列表，支持蛇形命名 "enabled_tools" 和驼峰命名 "enabledTools"
            server.setEnabledTools(stringListValue(
                    cfg,
                    "enabled_tools",
                    stringListValue(cfg, "enabledTools", server.getEnabledTools())
            ));
            
            // 设置工具超时时间，支持蛇形命名 "tool_timeout" 和驼峰命名 "toolTimeout"
            server.setToolTimeout(intValue(cfg, "tool_timeout", intValue(cfg, "toolTimeout", server.getToolTimeout())));

            // 将解析后的配置放入结果映射
            out.put(name, server);
        }

        // 返回解析结果
        return out;
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

            Map<String, Object> branch = copyObjectMap(map);
            Object type = branch.get("type");
            if ("null".equals(type)) {
                sawNull = true;
                continue;
            }

            nonNull.add(branch);
        }

        if (sawNull && nonNull.size() == 1) {
            return new NullableBranch(nonNull.get(0), true);
        }

        return null;
    }

    /**
     * 把 MCP schema 规范化为更适合 ricbot / OpenAI function-tool 风格的 schema。
     *
     * 主要处理：
     * - type: ["string", "null"]
     * - oneOf / anyOf 中的 nullable 分支
     * - 递归处理 properties / items
     */
    public static Map<String, Object> normalizeSchemaForOpenAI(Object schema) {
        if (!(schema instanceof Map<?, ?>)) {
            return new HashMap<>(Map.of(
                    "type", "object",
                    "properties", new HashMap<String, Object>()
            ));
        }

        Map<String, Object> normalized = new HashMap<>(copyObjectMap((Map<?, ?>) schema));

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
                if (entry.getKey() == null) {
                    continue;
                }
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

        private final MCPServerConnection connection;
        private final String originalName;
        private final String name;
        private final String description;
        private final Map<String, Object> parameters;
        private final int toolTimeout;

        public MCPToolWrapper(
                MCPServerConnection connection,
                String serverName,
                MCPToolDefinition toolDef,
                int toolTimeout
        ) {
            this.connection = connection;
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

        @Override
        public Object execute(Map<String, Object> kwargs) {
            try {
                MCPToolResult result = awaitMcpCall(() -> connection.getSession().callTool(originalName, kwargs), toolTimeout);

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
                log.warn("MCP 工具 '{}' 在 {} 秒后超时", name, toolTimeout);
                return "Error: MCP tool call timed out after " + toolTimeout + "s.";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("MCP 工具 '{}' 被中断", name);
                return "Error: MCP tool call was interrupted.";
            } catch (CancellationException e) {
                log.warn("MCP 工具 '{}' 已被服务端/SDK 取消", name);
                return "Error: MCP tool call was cancelled.";
            } catch (Exception e) {
                if (e instanceof ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    if (cause instanceof TimeoutException) {
                        log.warn("MCP 工具 '{}' 在 {} 秒后超时", name, toolTimeout);
                        return "Error: MCP tool call timed out after " + toolTimeout + "s.";
                    }
                    if (cause instanceof CancellationException) {
                        log.warn("MCP 工具 '{}' 已被服务端/SDK 取消", name);
                        return "Error: MCP tool call was cancelled.";
                    }
                }
                if (connection instanceof ReconnectingMCPServerConnection reconnecting && reconnecting.reconnect()) {
                    try {
                        MCPToolResult result = awaitMcpCall(() -> connection.getSession().callTool(originalName, kwargs), toolTimeout);
                        List<String> parts = new ArrayList<>();
                        for (Object block : result.getContent()) {
                            if (block instanceof MCPTextContent text) {
                                parts.add(text.getText());
                            } else {
                                parts.add(String.valueOf(block));
                            }
                        }
                        return parts.isEmpty() ? "(no output)" : String.join("\n", parts);
                    } catch (Exception retryError) {
                        log.error("MCP 工具 '{}' 自动重连后仍执行失败: {}: {}", name, retryError.getClass().getSimpleName(), retryError.getMessage(), retryError);
                    }
                }
                log.error("MCP 工具 '{}' 执行失败: {}: {}", name, e.getClass().getSimpleName(), e.getMessage(), e);
                return "Error: MCP tool call failed: " + e.getClass().getSimpleName();
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

        private final MCPServerConnection connection;
        private final String uri;
        private final String name;
        private final String description;
        private final int resourceTimeout;

        public MCPResourceWrapper(
                MCPServerConnection connection,
                String serverName,
                MCPResourceDefinition resourceDef,
                int resourceTimeout
        ) {
            this.connection = connection;
            this.uri = resourceDef.getUri();
            this.name = "mcp_" + serverName + "_resource_" + resourceDef.getName();

            String desc = resourceDef.getDescription() != null
                    ? resourceDef.getDescription()
                    : resourceDef.getName();

            this.description = "[MCP 资源] " + desc + "\nURI：" + uri;
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

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Object execute(Map<String, Object> kwargs) {
            try {
                MCPResourceResult result = awaitMcpCall(() -> connection.getSession().readResource(uri), resourceTimeout);

                List<String> parts = new ArrayList<>();
                for (Object block : result.getContents()) {
                    if (block instanceof MCPTextResourceContents text) {
                        parts.add(text.getText());
                    } else if (block instanceof MCPBlobResourceContents blob) {
                        parts.add("[二进制资源：" + blob.getBlobLength() + " 字节]");
                    } else {
                        parts.add(String.valueOf(block));
                    }
                }

                return parts.isEmpty() ? "（无输出）" : String.join("\n", parts);

            } catch (TimeoutException e) {
                log.warn("MCP 资源 '{}' 在 {} 秒后超时", name, resourceTimeout);
                return "（MCP 资源读取超时：" + resourceTimeout + " 秒）";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("MCP 资源 '{}' 被中断", name);
                return "（MCP 资源读取已被中断）";
            } catch (CancellationException e) {
                log.warn("MCP 资源 '{}' 已被服务端/SDK 取消", name);
                return "（MCP 资源读取已被服务端/SDK 取消）";
            } catch (Exception e) {
                log.error("MCP 资源 '{}' 读取失败: {}: {}", name, e.getClass().getSimpleName(), e.getMessage(), e);
                return "（MCP 资源读取失败：" + e.getClass().getSimpleName() + "）";
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

        private final MCPServerConnection connection;
        private final String promptName;
        private final String name;
        private final String description;
        private final Map<String, Object> parameters;
        private final int promptTimeout;

        public MCPPromptWrapper(
                MCPServerConnection connection,
                String serverName,
                MCPPromptDefinition promptDef,
                int promptTimeout
        ) {
            this.connection = connection;
            this.promptName = promptDef.getName();
            this.name = "mcp_" + serverName + "_prompt_" + promptDef.getName();

            String desc = promptDef.getDescription() != null
                    ? promptDef.getDescription()
                    : promptDef.getName();

            this.description = "[MCP 提示] " + desc
                    + "\n返回一个已填充的提示词模板，可作为工作流指引使用。";
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
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Object execute(Map<String, Object> kwargs) {
            try {
                MCPPromptResult result = awaitMcpCall(() -> connection.getSession().getPrompt(promptName, kwargs), promptTimeout);

                List<String> parts = getStrings(result);

                return parts.isEmpty() ? "（无输出）" : String.join("\n", parts);

            } catch (TimeoutException e) {
                log.warn("MCP 提示词 '{}' 在 {} 秒后超时", name, promptTimeout);
                return "（MCP prompt 调用超时：" + promptTimeout + " 秒）";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("MCP 提示词 '{}' 被中断", name);
                return "（MCP prompt 调用已被中断）";
            } catch (CancellationException e) {
                log.warn("MCP 提示词 '{}' 已被服务端/SDK 取消", name);
                return "（MCP prompt 调用已被服务端/SDK 取消）";
            } catch (Exception e) {
                log.error("MCP 提示词 '{}' 调用失败: {}: {}", name, e.getClass().getSimpleName(), e.getMessage(), e);
                return "（MCP prompt 调用失败：" + e.getClass().getSimpleName() + "）";
            }
        }
    }

    private static List<String> getStrings(MCPPromptResult result) {
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
        return parts;
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
        return connectMcpServersDetailed(mcpServers, registry).connections();
    }

    public static MCPConnectReport connectMcpServersDetailed(
            Map<String, Config.MCPServerConfig> mcpServers,
            ToolRegistry registry
    ) {
        Map<String, MCPServerConnection> serverConnections = new LinkedHashMap<>();
        Map<String, MCPServerLoadInfo> serverInfo = new LinkedHashMap<>();
        if (mcpServers == null || mcpServers.isEmpty()) {
            return new MCPConnectReport(serverConnections, serverInfo);
        }
        List<Future<ServerConnectResult>> futures = new ArrayList<>();

        ExecutorService executor = createConnectExecutor(mcpServers.size());

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
                if (result != null && result.info() != null) {
                    serverInfo.put(result.name(), result.info());
                }
            } catch (Exception e) {
                log.error("MCP 服务器 '{}' 连接任务失败: {}", name, e.getMessage(), e);
                serverInfo.put(name, new MCPServerLoadInfo(
                        name,
                        "unknown",
                        "FAILED",
                        List.of(),
                        List.of(),
                        List.of(),
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        List.of("connect task failed")
                ));
            }
        }

        executor.shutdown();
        return new MCPConnectReport(serverConnections, serverInfo);
    }

    public static List<MCPServerHealth> healthReport(Map<String, MCPServerConnection> connections, int timeoutSeconds) {
        if (connections == null || connections.isEmpty()) {
            return List.of();
        }
        List<MCPServerHealth> out = new ArrayList<>();
        int timeout = Math.max(1, timeoutSeconds);
        for (Map.Entry<String, MCPServerConnection> entry : new TreeMap<>(connections).entrySet()) {
            String name = entry.getKey();
            MCPServerConnection connection = entry.getValue();
            if (connection == null) {
                out.add(new MCPServerHealth(name, "disconnected", 0, "no connection"));
                continue;
            }
            try {
                List<MCPToolDefinition> tools = awaitMcpCall(() -> connection.getSession().listTools(), timeout);
                out.add(new MCPServerHealth(name, "ok", tools != null ? tools.size() : 0, ""));
            } catch (TimeoutException e) {
                out.add(new MCPServerHealth(name, "timeout", 0, "health check timed out after " + timeout + "s"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.add(new MCPServerHealth(name, "interrupted", 0, "health check interrupted"));
            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException ee && ee.getCause() != null ? ee.getCause() : e;
                if (connection instanceof ReconnectingMCPServerConnection reconnecting && reconnecting.reconnect()) {
                    try {
                        List<MCPToolDefinition> tools = awaitMcpCall(() -> connection.getSession().listTools(), timeout);
                        out.add(new MCPServerHealth(name, "reconnected", tools != null ? tools.size() : 0, ""));
                        continue;
                    } catch (Exception retryError) {
                        Throwable retryCause = retryError instanceof ExecutionException ee && ee.getCause() != null ? ee.getCause() : retryError;
                        out.add(new MCPServerHealth(name, "error", 0, retryCause.getClass().getSimpleName() + ": " + retryCause.getMessage()));
                        continue;
                    }
                }
                out.add(new MCPServerHealth(name, "error", 0, cause.getClass().getSimpleName() + ": " + cause.getMessage()));
            }
        }
        return out;
    }

    private static MCPServerConnection connectTransport(String name, Config.MCPServerConfig cfg, String transportType) {
        return switch (transportType) {
            case "stdio" -> MCPTransportFactory.connectStdio(cfg);
            case "streamableHttp" -> MCPTransportFactory.connectStreamableHttp(cfg);
            case "sse" -> throw new IllegalArgumentException(
                    "MCP SSE transport 已删除；请将 server type 改为 streamableHttp，并配置 Streamable HTTP endpoint"
            );
            default -> {
                log.warn("MCP 服务器 '{}': 未知的传输类型：'{}'", name, transportType);
                yield null;
            }
        };
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
        String transportType = cfg != null ? cfg.getType() : "";
        List<String> configWarnings = new ArrayList<>();

        try {
            if (transportType == null || transportType.isBlank()) {
                if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
                    transportType = "stdio";
                } else if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
                    transportType = "streamableHttp";
                } else {
                    log.warn("MCP 服务器 '{}': 未配置 command 或 url，已跳过", name);
                    return new ServerConnectResult(name, null, new MCPServerLoadInfo(
                            name,
                            "unknown",
                            "DISABLED",
                            List.of(),
                            List.of(),
                            List.of(),
                            "missing command or url",
                            List.of("missing command or url")
                    ));
                }
            }

            connection = connectTransport(name, cfg, transportType);

            if (connection == null) {
                return new ServerConnectResult(name, null, new MCPServerLoadInfo(
                        name,
                        transportType,
                        "FAILED",
                        List.of(),
                        List.of(),
                        List.of(),
                        "transport connection failed",
                        List.of("transport connection failed")
                ));
            }

            MCPClientSession session = connection.getSession();
            session.initialize();
            MCPServerConnection managedConnection = new ReconnectingMCPServerConnection(name, cfg, transportType, connection);

            int registeredCount = 0;

            Set<String> enabledTools = new HashSet<>(cfg.getEnabledTools());
            boolean allowAllTools = enabledTools.isEmpty() || enabledTools.contains("*");
            Set<String> matchedEnabledTools = new HashSet<>();

            List<MCPToolDefinition> toolDefs = session.listTools();
            List<String> availableRawNames = new ArrayList<>();
            List<String> availableWrappedNames = new ArrayList<>();
            List<String> registeredToolNames = new ArrayList<>();
            List<String> filteredToolNames = new ArrayList<>();

            for (MCPToolDefinition toolDef : toolDefs) {
                availableRawNames.add(toolDef.getName());
                availableWrappedNames.add("mcp_" + name + "_" + toolDef.getName());
            }

            for (MCPToolDefinition toolDef : toolDefs) {
                String wrappedName = "mcp_" + name + "_" + toolDef.getName();

                if (!allowAllTools
                        && !enabledTools.contains(toolDef.getName())
                        && !enabledTools.contains(wrappedName)) {
                    log.debug("MCP：跳过工具 '{}'（来自服务器 '{}'，不在 enabledTools 中）", wrappedName, name);
                    filteredToolNames.add(wrappedName);
                    continue;
                }

                MCPToolWrapper wrapper = new MCPToolWrapper(
                        managedConnection,
                        name,
                        toolDef,
                        cfg.getToolTimeout()
                );
                registry.register(wrapper);
                registeredCount++;
                registeredToolNames.add(wrapper.getName());

                if (enabledTools.contains(toolDef.getName())) {
                    matchedEnabledTools.add(toolDef.getName());
                }
                if (enabledTools.contains(wrappedName)) {
                    matchedEnabledTools.add(wrappedName);
                }

                log.debug("MCP：已注册工具 '{}'（来自服务器 '{}'）", wrapper.getName(), name);
            }

            if (!enabledTools.isEmpty() && !allowAllTools) {
                Set<String> unmatched = new TreeSet<>(enabledTools);
                unmatched.removeAll(matchedEnabledTools);

                if (!unmatched.isEmpty()) {
                    log.warn(
                            "MCP 服务器 '{}': enabledTools 中存在未匹配项：{}。可用原始名称：{}。可用包装名称：{}",
                            name,
                            String.join(", ", unmatched),
                            String.join(", ", availableRawNames),
                            String.join(", ", availableWrappedNames)
                    );
                    configWarnings.add("enabledTools unmatched: " + String.join(", ", unmatched));
                }
            }

            try {
                for (MCPResourceDefinition resource : session.listResources()) {
                    MCPResourceWrapper wrapper = new MCPResourceWrapper(
                            managedConnection, name, resource, cfg.getToolTimeout()
                    );
                    registry.register(wrapper);
                    registeredCount++;
                    log.debug("MCP：已注册资源 '{}'（来自服务器 '{}'）", wrapper.getName(), name);
                }
            } catch (Exception e) {
                log.debug("MCP 服务器 '{}': resources 不支持或失败：{}", name, e.getMessage());
            }

            try {
                for (MCPPromptDefinition prompt : session.listPrompts()) {
                    MCPPromptWrapper wrapper = new MCPPromptWrapper(
                            managedConnection, name, prompt, cfg.getToolTimeout()
                    );
                    registry.register(wrapper);
                    registeredCount++;
                    log.debug("MCP：已注册 prompt '{}'（来自服务器 '{}'）", wrapper.getName(), name);
                }
            } catch (Exception e) {
                log.debug("MCP 服务器 '{}': prompts 不支持或失败：{}", name, e.getMessage());
            }

            log.info("MCP 服务器 '{}': 已连接，已注册能力数：{}", name, registeredCount);
            return new ServerConnectResult(name, managedConnection, new MCPServerLoadInfo(
                    name,
                    transportType,
                    "CONNECTED",
                    List.copyOf(availableRawNames),
                    List.copyOf(registeredToolNames),
                    List.copyOf(filteredToolNames),
                    "",
                    List.copyOf(configWarnings)
            ));

        } catch (Exception e) {
            String text = String.valueOf(e.getMessage()).toLowerCase();
            String hint = "";

            if (text.contains("parse error")
                    || text.contains("invalid json")
                    || text.contains("unexpected token")
                    || text.contains("jsonrpc")
                    || text.contains("content-length")) {
                hint = " 提示：这看起来像是 stdio 协议被日志污染。请确保 MCP server 只把 JSON-RPC 写到 stdout，把日志/调试输出写到 stderr。";
            }

            if (!hint.isBlank()) {
                // hint already set above in Chinese
            }
            log.error("MCP 服务器 '{}': 连接失败：{}{}", name, e.getMessage(), hint);

            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }

            List<String> warnings = new ArrayList<>(configWarnings);
            if (!hint.isBlank()) {
                warnings.add(hint.trim());
            }
            return new ServerConnectResult(name, null, new MCPServerLoadInfo(
                    name,
                    transportType != null && !transportType.isBlank() ? transportType : "unknown",
                    "FAILED",
                    List.of(),
                    List.of(),
                    List.of(),
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    List.copyOf(warnings)
            ));
        }
    }

    // =========================================================
    // 小型返回结构
    // =========================================================

    public record NullableBranch(Map<String, Object> branch, boolean nullable) {
    }

    public record MCPConnectReport(
            Map<String, MCPServerConnection> connections,
            Map<String, MCPServerLoadInfo> servers
    ) {
    }

    public record MCPServerLoadInfo(
            String name,
            String transportType,
            String status,
            List<String> rawToolNames,
            List<String> registeredToolNames,
            List<String> filteredToolNames,
            String lastError,
            List<String> configWarnings
    ) {
    }

    public record ServerConnectResult(String name, MCPServerConnection connection, MCPServerLoadInfo info) {
    }

    public record MCPServerHealth(String name, String status, int toolCount, String error) {
    }

    private static final class ReconnectingMCPServerConnection implements MCPServerConnection {
        private final String name;
        private final Config.MCPServerConfig cfg;
        private final String transportType;
        private MCPServerConnection delegate;

        private ReconnectingMCPServerConnection(
                String name,
                Config.MCPServerConfig cfg,
                String transportType,
                MCPServerConnection delegate
        ) {
            this.name = name;
            this.cfg = cfg;
            this.transportType = transportType;
            this.delegate = delegate;
        }

        @Override
        public synchronized MCPClientSession getSession() {
            return delegate.getSession();
        }

        synchronized boolean reconnect() {
            MCPServerConnection next = null;
            try {
                next = connectTransport(name, cfg, transportType);
                if (next == null) {
                    return false;
                }
                next.getSession().initialize();
                MCPServerConnection old = delegate;
                delegate = next;
                closeQuietly(old);
                log.info("MCP 服务器 '{}': 自动重连成功", name);
                return true;
            } catch (Exception e) {
                closeQuietly(next);
                log.warn("MCP 服务器 '{}': 自动重连失败: {}", name, e.getMessage(), e);
                return false;
            }
        }

        @Override
        public synchronized void close() throws Exception {
            if (delegate != null) {
                delegate.close();
            }
        }

        private void closeQuietly(MCPServerConnection connection) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String stringValue(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        String s = TextParsingUtils.normalizeQuoted(String.valueOf(v));
        return s.isBlank() ? def : s;
    }

    private static int intValue(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static List<String> stringListValue(Map<String, Object> map, String key, List<String> def) {
        Object v = map.get(key);
        if (v == null) {
            return def != null ? def : new ArrayList<>();
        }
        List<String> parsed = TextParsingUtils.toStringList(v);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        return def != null ? def : new ArrayList<>();
    }

    private static Map<String, String> stringMapValue(Map<String, Object> map, String key, Map<String, String> def) {
        Object v = map.get(key);
        if (v == null) {
            return def != null ? def : new HashMap<>();
        }
        if (v instanceof Map<?, ?> raw) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                out.put(
                        String.valueOf(e.getKey()),
                        e.getValue() != null ? TextParsingUtils.normalizeQuoted(String.valueOf(e.getValue())) : ""
                );
            }
            return out;
        }
        return def != null ? def : new HashMap<>();
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> raw) {
        return ricbot.infra.common.JsonMapUtils.copyObjectMap(raw);
    }
}
