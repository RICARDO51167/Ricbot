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
 * 负责连接 MCP Server，并把 MCP 的 tool/resource/prompt 包装成 nanobot Tool。
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
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (CancellationException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            future.cancel(true);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Config.MCPServerConfig> parseMcpServers(Map<String, Object> raw) {
        Map<String, Config.MCPServerConfig> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }

        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Config.MCPServerConfig typed) {
                out.put(name, typed);
                continue;
            }
            if (!(value instanceof Map<?, ?> map)) {
                continue;
            }

            Map<String, Object> cfg = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                cfg.put(String.valueOf(e.getKey()), e.getValue());
            }

            Config.MCPServerConfig server = new Config.MCPServerConfig();
            server.setType(stringValue(cfg, "type", server.getType()));
            server.setUrl(stringValue(cfg, "url", server.getUrl()));
            server.setCommand(stringValue(cfg, "command", server.getCommand()));
            server.setArgs(stringListValue(cfg, "args", server.getArgs()));
            server.setEnv(stringMapValue(cfg, "env", server.getEnv()));
            server.setEnabledTools(stringListValue(
                    cfg,
                    "enabled_tools",
                    stringListValue(cfg, "enabledTools", server.getEnabledTools())
            ));
            server.setToolTimeout(intValue(cfg, "tool_timeout", intValue(cfg, "toolTimeout", server.getToolTimeout())));

            out.put(name, server);
        }

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

        @Override
        public Object execute(Map<String, Object> kwargs) {
            try {
                MCPToolResult result = awaitMcpCall(() -> session.callTool(originalName, kwargs), toolTimeout);

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
                MCPResourceResult result = awaitMcpCall(() -> session.readResource(uri), resourceTimeout);

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
                MCPPromptResult result = awaitMcpCall(() -> session.getPrompt(promptName, kwargs), promptTimeout);

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
            } catch (Exception e) {
                log.error("MCP 服务器 '{}' 连接任务失败: {}", name, e.getMessage(), e);
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
                    log.warn("MCP 服务器 '{}': 未配置 command 或 url，已跳过", name);
                    return new ServerConnectResult(name, null);
                }
            }

            connection = switch (transportType) {
                case "stdio" -> MCPTransportFactory.connectStdio(cfg);
                case "sse" -> MCPTransportFactory.connectSse(cfg);
                case "streamableHttp" -> MCPTransportFactory.connectStreamableHttp(cfg);
                default -> {
                    log.warn("MCP 服务器 '{}': 未知的传输类型：'{}'", name, transportType);
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
            boolean allowAllTools = enabledTools.isEmpty() || enabledTools.contains("*");
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
                    log.debug("MCP：跳过工具 '{}'（来自服务器 '{}'，不在 enabledTools 中）", wrappedName, name);
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
                }
            }

            try {
                for (MCPResourceDefinition resource : session.listResources()) {
                    MCPResourceWrapper wrapper = new MCPResourceWrapper(
                            session, name, resource, cfg.getToolTimeout()
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
                            session, name, prompt, cfg.getToolTimeout()
                    );
                    registry.register(wrapper);
                    registeredCount++;
                    log.debug("MCP：已注册 prompt '{}'（来自服务器 '{}'）", wrapper.getName(), name);
                }
            } catch (Exception e) {
                log.debug("MCP 服务器 '{}': prompts 不支持或失败：{}", name, e.getMessage());
            }

            log.info("MCP 服务器 '{}': 已连接，已注册能力数：{}", name, registeredCount);
            return new ServerConnectResult(name, connection);

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
                out.put(
                        String.valueOf(e.getKey()),
                        e.getValue() != null ? TextParsingUtils.normalizeQuoted(String.valueOf(e.getValue())) : ""
                );
            }
            return out;
        }
        return def != null ? def : new HashMap<>();
    }
}
