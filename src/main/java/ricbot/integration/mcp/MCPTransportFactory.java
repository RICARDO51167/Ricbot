package ricbot.integration.mcp;

import ricbot.infra.config.Config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
     * 创建基于服务器发送事件（SSE）的 MCP 服务器连接
     *
     * @param cfg MCP 服务器配置
     * @return MCPServerConnection 实例
     */
    public static MCPServerConnection connectSse(Config.MCPServerConfig cfg) {
        // 检查配置和 URL 是否有效
        if (cfg == null || cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            throw new IllegalArgumentException("sse MCP 需要配置 URL");
        }
        // 返回 SSE 连接实例
        return new SseMcpServerConnection(cfg);
    }

    /**
     * 创建基于可流式 HTTP 的 MCP 服务器连接（暂未实现）
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
        @SuppressWarnings("unchecked")
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
        @SuppressWarnings("unchecked")
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
        @SuppressWarnings("unchecked")
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
        @SuppressWarnings("unchecked")
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
                    response = MAPPER.readValue(line, new TypeReference<>() {});
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
                        return (Map<String, Object>) map;
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

    /**
     * 服务器发送事件（SSE）MCP 服务器连接实现
     */
    private static final class SseMcpServerConnection implements MCPServerConnection {
        // 持有的会话对象
        private final SseMcpClientSession session;

        /**
         * 构造函数
         *
         * @param cfg MCP 服务器配置
         */
        private SseMcpServerConnection(Config.MCPServerConfig cfg) {
            // 初始化会话
            this.session = new SseMcpClientSession(cfg);
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

    /**
     * 服务器发送事件（SSE）MCP 客户端会话实现
     */
    private static final class SseMcpClientSession implements MCPClientSession, AutoCloseable {
        // JSON 映射器
        private static final ObjectMapper MAPPER = new ObjectMapper();

        // 服务器配置
        private final Config.MCPServerConfig cfg;
        // HTTP 客户端
        private final HttpClient httpClient;
        // ID 生成器
        private final AtomicLong idGen = new AtomicLong(1);
        // 入站 JSON 行队列
        private final BlockingQueue<String> inboundJsonLines = new LinkedBlockingQueue<>();
        // 关闭标志
        private final AtomicBoolean closed = new AtomicBoolean(false);

        // POST 端点
        private volatile URI postEndpoint;
        private volatile String endpointWaitError;
        // SSE 监听线程
        private Thread sseThread;

        /**
         * 构造函数
         *
         * @param cfg MCP 服务器配置
         */
        private SseMcpClientSession(Config.MCPServerConfig cfg) {
            this.cfg = cfg;
            // 构建 HTTP 客户端
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(5, cfg.getToolTimeout())))
                    .build();
        }

        /**
         * 初始化会话
         *
         * @throws Exception 初始化异常
         */
        @Override
        public synchronized void initialize() throws Exception {
            // 确保 SSE 循环已启动
            ensureSseLoop();
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
        @SuppressWarnings("unchecked")
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
        @SuppressWarnings("unchecked")
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
        @SuppressWarnings("unchecked")
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
         * 确保 SSE 循环已启动
         */
        private synchronized void ensureSseLoop() {
            // 如果线程已存在且存活，则直接返回
            if (sseThread != null && sseThread.isAlive()) {
                return;
            }
            // 创建 SSE URI
            URI sseUri = parseUri(cfg.getUrl());
            // 创建并启动 SSE 监听线程
            sseThread = new Thread(() -> runSseLoop(sseUri), "mcp-sse-" + System.identityHashCode(this));
            sseThread.setDaemon(true);
            sseThread.start();
        }

        /**
         * 运行 SSE 循环
         *
         * @param sseUri SSE URI
         */
        private void runSseLoop(URI sseUri) {
            try {
                // 构建 HTTP 请求
                HttpRequest req = HttpRequest.newBuilder(sseUri)
                        .header("Accept", "text/event-stream")
                        .timeout(Duration.ofSeconds(Math.max(10, cfg.getToolTimeout())))
                        .GET()
                        .build();

                // 发送请求并获取响应
                HttpResponse<InputStream> res = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    String snippet;
                    try (InputStream in = res.body()) {
                        byte[] bytes = in.readNBytes(2048);
                        snippet = new String(bytes, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        snippet = "";
                    }
                    endpointWaitError = "MCP SSE 连接失败：status=" + res.statusCode() + " url=" + sseUri + (snippet.isBlank() ? "" : " body=" + snippet);
                    return;
                }
                // 读取响应流
                try (BufferedReader br = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                    String event = null;
                    StringBuilder data = new StringBuilder();
                    // 循环读取行
                    while (!closed.get()) {
                        String line = br.readLine();
                        // 检查流是否结束
                        if (line == null) {
                            break;
                        }
                        // 处理空行（事件结束）
                        if (line.isEmpty()) {
                            if (data.length() > 0) {
                                String payload = data.toString();
                                // 处理 endpoint 事件
                                if ("endpoint".equals(event)) {
                                    try {
                                        postEndpoint = parseEndpointPayload(payload, sseUri);
                                    } catch (Exception e) {
                                        endpointWaitError = "解析 MCP SSE endpoint 失败：payload=" + payload + " url=" + sseUri + " error=" + e.getMessage();
                                    }
                                } else {
                                    // 将数据加入队列
                                    inboundJsonLines.offer(payload);
                                }
                            }
                            // 重置状态
                            event = null;
                            data.setLength(0);
                            continue;
                        }
                        // 处理 event 字段
                        if (line.startsWith("event:")) {
                            event = line.substring("event:".length()).trim();
                            continue;
                        }
                        // 处理 data 字段
                        if (line.startsWith("data:")) {
                            if (data.length() > 0) {
                                data.append('\n');
                            }
                            data.append(line.substring("data:".length()).trim());
                        }
                    }
                }
            } catch (Exception e) {
                endpointWaitError = "MCP SSE 连接异常：url=" + sseUri + " error=" + e.getMessage();
            }
        }

        private static URI parseEndpointPayload(String payload, URI sseUri) {
            String p = payload != null ? payload.trim() : "";
            if (p.isBlank()) {
                throw new IllegalArgumentException("empty payload");
            }
            if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) {
                p = p.substring(1, p.length() - 1).trim();
            }
            if (p.startsWith("{") && p.endsWith("}")) {
                try {
                    Map<?, ?> map = MAPPER.readValue(p, Map.class);
                    Object v = map.get("endpoint");
                    if (v == null) v = map.get("url");
                    if (v == null) v = map.get("uri");
                    if (v != null) {
                        return resolveEndpointUri(String.valueOf(v), sseUri);
                    }
                } catch (Exception ignored) {
                }
            }
            return resolveEndpointUri(p, sseUri);
        }

        private static URI resolveEndpointUri(String raw, URI sseUri) {
            String normalized = normalizeUriString(raw);
            URI uri;
            try {
                uri = URI.create(normalized);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid endpoint URI: raw=" + raw + ", normalized=" + normalized, e);
            }
            if (uri.getScheme() != null && !uri.getScheme().isBlank()) {
                return uri;
            }
            return sseUri.resolve(uri);
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
            String s = normalizeWhitespace(raw);
            if (s.length() >= 2) {
                char first = s.charAt(0);
                char last = s.charAt(s.length() - 1);
                if ((first == '`' && last == '`') || (first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    s = s.substring(1, s.length() - 1).trim();
                }
            }
            s = normalizeWhitespace(s.replace("`", ""));
            return s;
        }

        private static String normalizeWhitespace(String raw) {
            if (raw == null) {
                return "";
            }
            String s = raw
                    .replace('\u00A0', ' ')
                    .replace("\u200B", "")
                    .replace("\uFEFF", "");
            return s.trim();
        }


        /**
         * 发送通知
         *
         * @param method 方法名
         * @param params 参数
         * @throws Exception 发送异常
         */
        private void sendNotification(String method, Map<String, Object> params) throws Exception {
            // 构建通知对象并发送
            postJson(Map.of(
                    "jsonrpc", "2.0",
                    "method", method,
                    "params", params
            ), cfg.getToolTimeout());
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
        @SuppressWarnings("unchecked")
        private Map<String, Object> call(String method, Map<String, Object> params, int timeoutSeconds) throws Exception {
            // 确保 SSE 循环已启动
            ensureSseLoop();
            // 生成请求 ID
            long id = idGen.getAndIncrement();
            // 构建请求对象
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.put("params", params);

            // 发送请求
            postJson(request, timeoutSeconds);

            // 计算截止时间
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            while (true) {
                // 计算剩余时间
                long remaining = deadline - System.nanoTime();
                // 检查是否超时
                if (remaining <= 0) {
                    throw new java.util.concurrent.TimeoutException("MCP sse 调用超时: " + method);
                }

                // 从队列中获取响应
                String line = inboundJsonLines.poll(Math.min(500, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS);
                // 检查是否获取到数据
                if (line == null) {
                    continue;
                }

                // 解析 JSON 响应
                Map<String, Object> response;
                try {
                    response = MAPPER.readValue(line, new TypeReference<>() {});
                } catch (Exception ignored) {
                    // 解析失败，继续获取
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
                        return (Map<String, Object>) map;
                    }
                    return new LinkedHashMap<>();
                }
            }
        }

        /**
         * 发送 JSON 请求
         *
         * @param request        请求对象
         * @param timeoutSeconds 超时时间（秒）
         * @throws Exception 发送异常
         */
        private void postJson(Object request, int timeoutSeconds) throws Exception {
            // 等待 POST 端点
            URI endpoint = waitForPostEndpoint(timeoutSeconds);
            // 序列化请求
            byte[] body = MAPPER.writeValueAsBytes(request);
            // 构建 HTTP 请求
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            // 发送请求
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        }

        /**
         * 等待 POST 端点
         *
         * @param timeoutSeconds 超时时间（秒）
         * @return POST 端点 URI
         * @throws Exception 等待异常
         */
        private URI waitForPostEndpoint(int timeoutSeconds) throws Exception {
            // 获取当前端点
            URI endpoint = postEndpoint;
            // 如果端点已存在，直接返回
            if (endpoint != null) {
                return endpoint;
            }

            // 计算截止时间
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            // 循环等待端点
            while (endpoint == null) {
                String err = endpointWaitError;
                if (err != null && !err.isBlank()) {
                    throw new IllegalStateException(err);
                }
                // 检查是否超时
                if (System.nanoTime() > deadline) {
                    throw new java.util.concurrent.TimeoutException("未收到 MCP SSE 端点");
                }
                // 短暂休眠
                Thread.sleep(50);
                // 重新获取端点
                endpoint = postEndpoint;
            }
            return endpoint;
        }

        /**
         * 关闭会话
         */
        @Override
        public void close() {
            // 设置关闭标志
            closed.set(true);
            // 中断 SSE 线程
            if (sseThread != null) {
                try {
                    sseThread.interrupt();
                } catch (Exception ignored) {
                }
            }
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
            this.endpoint = SseMcpClientSession.parseUri(cfg.getUrl());
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
        @SuppressWarnings("unchecked")
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
        @SuppressWarnings("unchecked")
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
        @SuppressWarnings("unchecked")
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

        @SuppressWarnings("unchecked")
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
                return (Map<String, Object>) map;
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
            return MAPPER.readValue(bodyText, new TypeReference<>() {});
        }

        @Override
        public void close() {
        }
    }
}
