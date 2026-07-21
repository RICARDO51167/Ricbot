package ricbot.integration.mcp;

import ricbot.infra.config.Config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP Transport 工厂，占位版。
 *
 * 你后面可以分别实现：
 * - stdio
 * - sse
 * - streamableHttp
 */
public final class MCPTransportFactory {

    // 私有构造函数，防止实例化
    private MCPTransportFactory() {
    }

    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {
    };

    /**
     * 创建基于标准输入输出（stdio）的 MCP 服务器连接
     *
     * @param cfg MCP 服务器配置
     * @return MCPServerConnection 实例
     */
    public static MCPServerConnection connectStdio(Config.MCPServerConfig cfg) {
        // 检查配置和命令是否有效
        if (cfg == null || cfg.getCommand() == null || cfg.getCommand().isBlank()) {
            throw new IllegalArgumentException("stdio MCP 需要配置命令");
        }
        // 返回 stdio 连接实例
        return new StdioMcpServerConnection(cfg);
    }


    /**
     * 创建基于 Streamable HTTP 的 MCP 服务器连接
     *
     * @param cfg MCP 服务器配置
     * @return MCPServerConnection 实例
     */
    public static MCPServerConnection connectStreamableHttp(Config.MCPServerConfig cfg) {
        if (cfg == null || cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            throw new IllegalArgumentException("streamableHttp MCP 需要配置 URL");
        }
        return new StreamableHttpMcpServerConnection(cfg);
    }

    /**
     * 标准输入输出（stdio）MCP 服务器连接实现
     */
    private static final class StdioMcpServerConnection implements MCPServerConnection {
        // 持有的会话对象
        private final StdioMcpClientSession session;

        /**
         * 构造函数
         *
         * @param cfg MCP 服务器配置
         */
        private StdioMcpServerConnection(Config.MCPServerConfig cfg) {
            // 初始化会话
            this.session = new StdioMcpClientSession(cfg);
        }

        /**
         * 获取客户端会话
         *
         * @return MCPClientSession 实例
         */
        @Override
        public MCPClientSession getSession() {
            return session;
        }

        /**
         * 关闭连接
         *
         * @throws Exception 关闭异常
         */
        @Override
        public void close() throws Exception {
            session.close();
        }
    }

    /**
     * 标准输入输出（stdio）MCP 客户端会话实现
     */
    private static final class StdioMcpClientSession implements MCPClientSession, AutoCloseable {
        // JSON 映射器
        private static final ObjectMapper MAPPER = new ObjectMapper();

        // 服务器配置
        private final Config.MCPServerConfig cfg;
        // ID 生成器
        private final AtomicLong idGen = new AtomicLong(1);

        // 子进程
        private Process process;
        // 写入流
        private BufferedWriter writer;
        // 读取流
        private BufferedReader reader;

        /**
         * 构造函数
         *
         * @param cfg MCP 服务器配置
         */
        private StdioMcpClientSession(Config.MCPServerConfig cfg) {
            this.cfg = cfg;
        }

        /**
         * 初始化会话
         *
         * @throws Exception 初始化异常
         */
        @Override
        public synchronized void initialize() throws Exception {
            // 确保进程已启动
            ensureProcess();
            // 构建初始化参数
            Map<String, Object> params = new HashMap<>();
            params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", Collections.emptyMap());
            params.put("clientInfo", Map.of("name", "ricbot-java", "version", "0.1.0"));

            // 调用 initialize 方法
            call("initialize", params, cfg.getToolTimeout());
            // 发送已初始化通知
            sendNotification("notifications/initialized", Collections.emptyMap());
        }

        /**
         * 调用工具
         *
         * @param toolName  工具名称
         * @param arguments 工具参数
         * @return MCPToolResult 结果
         * @throws Exception 调用异常
         */
        @Override
        public MCPToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
            // 构建调用参数
            Map<String, Object> params = new HashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments != null ? arguments : Collections.emptyMap());
            // 调用 tools/call 方法
            Map<String, Object> result = call("tools/call", params, cfg.getToolTimeout());
            // 转换结果为 MCPToolResult
            return MAPPER.convertValue(result, MCPToolResult.class);
        }

        /**
         * 读取资源
         *
         * @param uri 资源 URI
         * @return MCPResourceResult 结果
         * @throws Exception 读取异常
         */
        @Override
        public MCPResourceResult readResource(String uri) throws Exception {
            // 构建读取参数
            Map<String, Object> params = new HashMap<>();
            params.put("uri", uri);
            // 调用 resources/read 方法
            Map<String, Object> result = call("resources/read", params, cfg.getToolTimeout());
            // 转换结果为 MCPResourceResult
            return MAPPER.convertValue(result, MCPResourceResult.class);
        }

        /**
         * 获取提示词
         *
         * @param promptName 提示词名称
         * @param arguments  提示词参数
         * @return MCPPromptResult 结果
         * @throws Exception 获取异常
         */
        @Override
        public MCPPromptResult getPrompt(String promptName, Map<String, Object> arguments) throws Exception {
            // 构建获取参数
            Map<String, Object> params = new HashMap<>();
            params.put("name", promptName);
            params.put("arguments", arguments != null ? arguments : Collections.emptyMap());
            // 调用 prompts/get 方法
            Map<String, Object> result = call("prompts/get", params, cfg.getToolTimeout());
            // 转换结果为 MCPPromptResult
            return MAPPER.convertValue(result, MCPPromptResult.class);
        }

        /**
         * 列出所有工具
         *
         * @return MCPToolDefinition 列表
         * @throws Exception 列出异常
         */
        @Override
        public List<MCPToolDefinition> listTools() throws Exception {
            // 调用 tools/list 方法
            Map<String, Object> result = call("tools/list", Collections.emptyMap(), cfg.getToolTimeout());
            // 获取 tools 字段
            Object tools = result.get("tools");
            // 检查是否为列表
            if (!(tools instanceof List<?> list)) {
                return List.of();
            }
            // 转换列表元素
            List<MCPToolDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPToolDefinition.class));
                }
            }
            return out;
        }

        /**
         * 列出所有资源
         *
         * @return MCPResourceDefinition 列表
         * @throws Exception 列出异常
         */
        @Override
        public List<MCPResourceDefinition> listResources() throws Exception {
            // 调用 resources/list 方法
            Map<String, Object> result = call("resources/list", Collections.emptyMap(), cfg.getToolTimeout());
            // 获取 resources 字段
            Object resources = result.get("resources");
            // 检查是否为列表
            if (!(resources instanceof List<?> list)) {
                return List.of();
            }
            // 转换列表元素
            List<MCPResourceDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPResourceDefinition.class));
                }
            }
            return out;
        }

        /**
         * 列出所有提示词
         *
         * @return MCPPromptDefinition 列表
         * @throws Exception 列出异常
         */
        @Override
        public List<MCPPromptDefinition> listPrompts() throws Exception {
            // 调用 prompts/list 方法
            Map<String, Object> result = call("prompts/list", Collections.emptyMap(), cfg.getToolTimeout());
            // 获取 prompts 字段
            Object prompts = result.get("prompts");
            // 检查是否为列表
            if (!(prompts instanceof List<?> list)) {
                return List.of();
            }
            // 转换列表元素
            List<MCPPromptDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPPromptDefinition.class));
                }
            }
            return out;
        }

        /**
         * 确保子进程已启动
         *
         * @throws Exception 启动异常
         */
        private synchronized void ensureProcess() throws Exception {
            // 如果进程已存在且存活，则直接返回
            if (process != null && process.isAlive()) {
                return;
            }

            // 构建命令列表
            List<String> cmd = new ArrayList<>();
            cmd.add(cfg.getCommand());
            cmd.addAll(cfg.getArgs() != null ? cfg.getArgs() : List.of());

            // 创建进程构建器
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 设置环境变量
            if (cfg.getEnv() != null && !cfg.getEnv().isEmpty()) {
                pb.environment().putAll(cfg.getEnv());
            }
            // 继承错误流
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            // 启动进程
            this.process = pb.start();
            // 初始化写入流
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            // 初始化读取流
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        /**
         * 发送通知
         *
         * @param method 方法名
         * @param params 参数
         * @throws Exception 发送异常
         */
        private synchronized void sendNotification(String method, Map<String, Object> params) throws Exception {
            // 确保进程已启动
            ensureProcess();
            // 构建请求对象
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("method", method);
            request.put("params", params);

            // 写入 JSON 字符串
            writer.write(MAPPER.writeValueAsString(request));
            // 写入换行符
            writer.newLine();
            // 刷新缓冲区
            writer.flush();
        }

        /**
         * 调用方法并等待响应
         *
         * @param method         方法名
         * @param params         参数
         * @param timeoutSeconds 超时时间（秒）
         * @return 响应结果
         * @throws Exception 调用异常
         */
        private synchronized Map<String, Object> call(String method, Map<String, Object> params, int timeoutSeconds) throws Exception {
            // 确保进程已启动
            ensureProcess();
            // 生成请求 ID
            long id = idGen.getAndIncrement();
            // 构建请求对象
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.put("params", params);

            // 写入请求
            writer.write(MAPPER.writeValueAsString(request));
            writer.newLine();
            writer.flush();

            // 计算截止时间
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            while (true) {
                // 计算剩余时间
                long remaining = deadline - System.nanoTime();
                // 检查是否超时
                if (remaining <= 0) {
                    throw new java.util.concurrent.TimeoutException("MCP stdio 调用超时: " + method);
                }

                // 检查读取流是否就绪
                if (!reader.ready()) {
                    // 短暂休眠
                    Thread.sleep(Math.min(50, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    continue;
                }

                // 读取一行
                String line = reader.readLine();
                // 检查流是否关闭
                if (line == null) {
                    throw new IllegalStateException("等待 id=" + id + " 时 MCP 服务器关闭了标准输出");
                }
                // 跳过空行
                if (line.trim().isEmpty()) {
                    continue;
                }

                // 解析 JSON 响应
                Map<String, Object> response;
                try {
                    response = MAPPER.readValue(line, JSON_OBJECT_TYPE);
                } catch (Exception ignored) {
                    // 解析失败，继续读取
                    continue;
                }

                // 检查是否包含 ID
                if (!response.containsKey("id")) {
                    continue;
                }

                // 检查 ID 是否匹配
                Object respId = response.get("id");
                if (respId != null && Long.parseLong(String.valueOf(respId)) == id) {
                    // 检查是否包含错误
                    if (response.containsKey("error")) {
                        throw new IllegalStateException("MCP 错误: " + response.get("error"));
                    }
                    // 获取结果
                    Object result = response.get("result");
                    // 返回结果映射
                    if (result instanceof Map<?, ?> map) {
                        return copyObjectMap(map);
                    }
                    return new LinkedHashMap<>();
                }
            }
        }

        /**
         * 关闭会话
         *
         * @throws Exception 关闭异常
         */
        @Override
        public synchronized void close() throws Exception {
            // 关闭写入流
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
            // 关闭读取流
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            // 销毁进程
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private static final class StreamableHttpMcpServerConnection implements MCPServerConnection {
        private final StreamableHttpMcpClientSession session;

        private StreamableHttpMcpServerConnection(Config.MCPServerConfig cfg) {
            this.session = new StreamableHttpMcpClientSession(cfg);
        }

        @Override
        public MCPClientSession getSession() {
            return session;
        }

        @Override
        public void close() {
            session.close();
        }
    }

    private static final class StreamableHttpMcpClientSession implements MCPClientSession, AutoCloseable {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final Config.MCPServerConfig cfg;
        private final HttpClient httpClient;
        private final AtomicLong idGen = new AtomicLong(1);
        private final URI endpoint;

        private StreamableHttpMcpClientSession(Config.MCPServerConfig cfg) {
            this.cfg = cfg;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(5, cfg.getToolTimeout())))
                    .build();
            this.endpoint = parseUri(cfg.getUrl());
        }

        @Override
        public void initialize() throws Exception {
            Map<String, Object> params = new HashMap<>();
            params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", Collections.emptyMap());
            params.put("clientInfo", Map.of("name", "ricbot-java", "version", "0.1.0"));

            call("initialize", params, cfg.getToolTimeout());
            sendNotification("notifications/initialized", Collections.emptyMap());
        }

        @Override
        public MCPToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
            Map<String, Object> params = new HashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments != null ? arguments : Collections.emptyMap());
            return MAPPER.convertValue(call("tools/call", params, cfg.getToolTimeout()), MCPToolResult.class);
        }

        @Override
        public MCPResourceResult readResource(String uri) throws Exception {
            Map<String, Object> params = new HashMap<>();
            params.put("uri", uri);
            return MAPPER.convertValue(call("resources/read", params, cfg.getToolTimeout()), MCPResourceResult.class);
        }

        @Override
        public MCPPromptResult getPrompt(String promptName, Map<String, Object> arguments) throws Exception {
            Map<String, Object> params = new HashMap<>();
            params.put("name", promptName);
            params.put("arguments", arguments != null ? arguments : Collections.emptyMap());
            return MAPPER.convertValue(call("prompts/get", params, cfg.getToolTimeout()), MCPPromptResult.class);
        }

        @Override
        public List<MCPToolDefinition> listTools() throws Exception {
            Object tools = call("tools/list", Collections.emptyMap(), cfg.getToolTimeout()).get("tools");
            if (!(tools instanceof List<?> list)) {
                return List.of();
            }
            List<MCPToolDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPToolDefinition.class));
                }
            }
            return out;
        }

        @Override
        public List<MCPResourceDefinition> listResources() throws Exception {
            Object resources = call("resources/list", Collections.emptyMap(), cfg.getToolTimeout()).get("resources");
            if (!(resources instanceof List<?> list)) {
                return List.of();
            }
            List<MCPResourceDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPResourceDefinition.class));
                }
            }
            return out;
        }

        @Override
        public List<MCPPromptDefinition> listPrompts() throws Exception {
            Object prompts = call("prompts/list", Collections.emptyMap(), cfg.getToolTimeout()).get("prompts");
            if (!(prompts instanceof List<?> list)) {
                return List.of();
            }
            List<MCPPromptDefinition> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(MAPPER.convertValue(map, MCPPromptDefinition.class));
                }
            }
            return out;
        }

        private void sendNotification(String method, Map<String, Object> params) throws Exception {
            postJson(Map.of(
                    "jsonrpc", "2.0",
                    "method", method,
                    "params", params
            ), cfg.getToolTimeout());
        }

        private Map<String, Object> call(String method, Map<String, Object> params, int timeoutSeconds) throws Exception {
            long id = idGen.getAndIncrement();
            Map<String, Object> response = postJson(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "method", method,
                    "params", params
            ), timeoutSeconds);

            if (response.containsKey("error")) {
                throw new IllegalStateException("MCP 错误: " + response.get("error"));
            }

            Object result = response.get("result");
            if (result instanceof Map<?, ?> map) {
                return copyObjectMap(map);
            }
            return new LinkedHashMap<>();
        }

        private Map<String, Object> postJson(Object request, int timeoutSeconds) throws Exception {
            byte[] body = MAPPER.writeValueAsBytes(request);
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new IllegalStateException("MCP HTTP 请求失败: status=" + res.statusCode());
            }

            String bodyText = res.body() != null ? res.body().trim() : "";
            if (bodyText.isBlank()) {
                return new LinkedHashMap<>();
            }
            return MAPPER.readValue(bodyText, JSON_OBJECT_TYPE);
        }

        @Override
        public void close() {
        }
    }

    private static URI parseUri(String raw) {
        String normalized = normalizeUriString(raw);
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URI: raw=" + raw + ", normalized=" + normalized, e);
        }
        if (uri.getScheme() == null || uri.getScheme().isBlank()) {
            throw new IllegalArgumentException("URI with undefined scheme: raw=" + raw + ", normalized=" + normalized);
        }
        return uri;
    }

    private static String normalizeUriString(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw
                .replace('\u00A0', ' ')
                .replace("\u200B", "")
                .replace("\uFEFF", "")
                .trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length() - 1).trim();
            }
        }
        return value.replace("`", "").trim();
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> raw) {
        return ricbot.infra.common.JsonMapUtils.copyObjectMap(raw);
    }
}
