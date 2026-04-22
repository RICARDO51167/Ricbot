package ricbot.integration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import ricbot.domain.agent.AgentLoop;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.hook.AgentHookContext;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.session.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容 HTTP API 服务。
 *
 * 主要目标：
 * 1. 对外暴露 OpenAI 风格接口
 * 2. 把外部请求转给内部 AgentLoop
 * 3. 维持固定 API session，支持按 session_id 做会话隔离
 *
 * 对应 Python 文件：
 * - /v1/chat/completions
 * - /v1/models
 * - /health
 */
@Slf4j
public class RicbotApiServer {

    /**
     * 默认 API session key
     */
    public static final String API_SESSION_KEY = "api:default";

    /**
     * API channel 下固定 chatId
     */
    public static final String API_CHAT_ID = "default";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 创建并启动 HTTP 服务。
     *
     * @param host 监听主机
     * @param port 请求端口
     * @param agentLoop 已初始化好的 AgentLoop
     * @param modelName 对外报告的模型名
     * @param requestTimeoutMillis 单请求超时毫秒数
     * @param bearerToken Bearer token，留空时仅在非 loopback 监听下强制鉴权
     */
    public static HttpServer createAndStart(
            String host,
            int port,
            AgentLoop agentLoop,
            String modelName,
            long requestTimeoutMillis,
            String bearerToken
    ) throws IOException {
        ApiAppContext appContext = new ApiAppContext(agentLoop, modelName, requestTimeoutMillis, host, bearerToken);

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/v1/chat/completions", new ChatCompletionsHandler(appContext));
        server.createContext("/v1/models", new ModelsHandler(appContext));
        server.createContext("/health", new HealthHandler(appContext));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    /**
     * 应用上下文。
     *
     * 对应 Python app[...] 中存的内容：
     * - agent_loop
     * - model_name
     * - request_timeout
     * - session_locks
     */
    /**
     * 应用上下文。
     *
     * 对应 Python app[...] 中存的内容：
     * - agent_loop
     * - model_name
     * - request_timeout
     * - session_locks
     */
    public static class ApiAppContext {
        // 核心业务逻辑代理，负责处理具体的 Agent 交互
        private final AgentLoop agentLoop;
        // 对外报告的模型名称
        private final String modelName;
        // 单个请求的超时时间（毫秒）
        private final long requestTimeoutMillis;
        // 服务绑定的主机地址
        private final String bindHost;
        // 用于身份验证的 Bearer Token
        private final String bearerToken;
        // 是否强制要求身份验证（取决于 token 是否存在或是否为回环地址）
        private final boolean requireAuth;
        // 会话锁映射表，用于保证同一会话的并发安全
        private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

        /**
         * 构造函数，初始化应用上下文。
         *
         * @param agentLoop 已初始化好的 AgentLoop 实例
         * @param modelName 模型名称，若为空则默认为 "ricbot"
         * @param requestTimeoutMillis 请求超时毫秒数，若小于等于0则默认为 120秒
         * @param bindHost 绑定主机地址，若为空则默认为 "127.0.0.1"
         * @param bearerToken 鉴权 Token，若为空则去除首尾空格
         */
        public ApiAppContext(
                AgentLoop agentLoop,
                String modelName,
                long requestTimeoutMillis,
                String bindHost,
                String bearerToken
        ) {
            this.agentLoop = agentLoop;
            // 如果模型名为空，使用默认值 "ricbot"
            this.modelName = modelName != null ? modelName : "ricbot";
            // 如果超时时间无效，使用默认值 120,000 毫秒 (2分钟)
            this.requestTimeoutMillis = requestTimeoutMillis > 0 ? requestTimeoutMillis : 120_000L;
            // 如果绑定主机为空，使用默认本地地址
            this.bindHost = bindHost != null && !bindHost.isBlank() ? bindHost : "127.0.0.1";
            // 处理 bearerToken，去除首尾空格，若为 null 则置为空串
            this.bearerToken = bearerToken != null ? bearerToken.trim() : "";
            // 确定是否需要鉴权：如果配置了 token 或者绑定的不是回环地址，则需要鉴权
            this.requireAuth = !this.bearerToken.isBlank() || !isLoopbackHost(this.bindHost);
        }

        /**
         * 获取 AgentLoop 实例。
         *
         * @return AgentLoop 对象
         */
        public AgentLoop getAgentLoop() {
            return agentLoop;
        }

        /**
         * 获取模型名称。
         *
         * @return 模型名称字符串
         */
        public String getModelName() {
            return modelName;
        }

        /**
         * 获取请求超时时间。
         *
         * @return 超时毫秒数
         */
        public long getRequestTimeoutMillis() {
            return requestTimeoutMillis;
        }

        /**
         * 检查当前 HTTP 请求是否已通过授权。
         *
         * @param exchange HTTP 交换对象，用于获取请求头
         * @return 如果不需要鉴权或鉴权通过返回 true，否则返回 false
         */
        public boolean isAuthorized(HttpExchange exchange) {
            // 如果配置为不需要鉴权，直接通过
            if (!requireAuth) {
                return true;
            }
            // 如果需要鉴权但 token 为空，则拒绝
            if (bearerToken.isBlank()) {
                return false;
            }

            // 获取 Authorization 请求头
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            // 检查头部是否存在且以 "Bearer " 开头（忽略大小写）
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return false;
            }

            // 提取提供的 token 部分
            String provided = header.substring(7).trim();
            // 使用恒定时间比较算法防止时序攻击，比对配置的 token 和提供的 token
            return MessageDigest.isEqual(
                    bearerToken.getBytes(StandardCharsets.UTF_8),
                    provided.getBytes(StandardCharsets.UTF_8)
            );
        }

        /**
         * 获取指定会话键对应的重入锁。
         * 如果不存在则创建一个新的锁并放入映射表。
         *
         * @param sessionKey 会话唯一标识键
         * @return 对应的 ReentrantLock 实例
         */
        public ReentrantLock getSessionLock(String sessionKey) {
            return sessionLocks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
        }

        /**
         * 判断给定的主机地址是否为回环地址（如 localhost, 127.0.0.1）。
         *
         * @param host 主机地址字符串
         * @return 如果是回环地址返回 true，否则返回 false
         */
        private static boolean isLoopbackHost(String host) {
            // 空字符串视为非回环
            if (host == null || host.isBlank()) {
                return false;
            }
            try {
                // 尝试解析 InetAddress 并判断是否为回环地址
                return InetAddress.getByName(host).isLoopbackAddress();
            } catch (Exception e) {
                // 如果解析失败，兜底判断是否等于 "localhost"
                return "localhost".equalsIgnoreCase(host);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Response helpers
    // ---------------------------------------------------------------------

    /**
     * 统一错误响应。
     *
     * 返回 OpenAI 风格：
     * {
     *   "error": {
     *     "message": "...",
     *     "type": "...",
     *     "code": 400
     *   }
     * }
     */
    public static void writeErrorJson(
            HttpExchange exchange,
            int status,
            String message,
            String errorType
    ) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", errorType != null ? errorType : "invalid_request_error");
        error.put("code", status);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);

        writeJson(exchange, status, body);
    }

    /**
     * 构造标准 chat completion 响应体。
     */
    public static Map<String, Object> chatCompletionResponse(String content, String model) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", 0);
        usage.put("total_tokens", 0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        result.put("object", "chat.completion");
        result.put("created", Instant.now().getEpochSecond());
        result.put("model", model);
        result.put("choices", List.of(choice));
        result.put("usage", usage);

        return result;
    }

    /**
     * 兼容处理 AgentLoop.processDirect(...) 的返回结果。
     *
     * 可能是：
     * - null
     * - OutboundMessage
     * - 其他对象
     */
    public static String responseText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof OutboundMessage msg) {
            return msg.getContent() != null ? msg.getContent() : "";
        }
        return String.valueOf(value);
    }

    /**
     * 统一写 JSON 响应。
     */
    public static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 读取请求体并转字符串。
     */
    public static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 安全读取 Map 字段。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    /**
     * 安全读取 List 字段。
     */
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List<?> ? (List<Object>) value : null;
    }

    // ---------------------------------------------------------------------
    // Route handlers
    // ---------------------------------------------------------------------

    /**
     * POST /v1/chat/completions
     */
    public record ChatCompletionsHandler(ApiAppContext appContext) implements HttpHandler {
            private static final String FALLBACK_RESPONSE = RuntimeConstants.EMPTY_FINAL_RESPONSE_MESSAGE;

            @Override
            public void handle(HttpExchange exchange) throws IOException {
                // 检查HTTP方法是否为POST，如果不是则返回错误
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                    return;
                }
                // 检查认证是否通过，未通过则返回401错误
                if (!appContext.isAuthorized(exchange)) {
                    writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                    return;
                }
                // 尝试解析请求体JSON，失败则返回400错误
                try {
                    Map<String, Object> body = readJsonRequestBody(exchange);
                    handleAuthorizedRequest(exchange, body);
                } catch (Exception e) {
                    if (e instanceof InvalidRequestException invalidRequest) {
                        writeErrorJson(exchange, invalidRequest.status(), invalidRequest.getMessage(), invalidRequest.errorType());
                        return;
                    }
                    log.error("处理 chat completions 请求失败", e);
                    writeErrorJson(exchange, 500, "服务器内部错误", "internal_error");
                }
            }

            private void handleAuthorizedRequest(HttpExchange exchange, Map<String, Object> body) throws IOException {
                List<Object> messages = asList(body.get("messages"));
                if (messages == null || messages.isEmpty()) {
                    throw invalidRequest("messages 必须是非空数组");
                }

                boolean streamEnabled = Boolean.TRUE.equals(body.get("stream"));
                ParsedMessages parsed = parseIncomingMessages(messages);
                String modelName = validateRequestedModel(body.get("model"));
                String sessionKey = resolveSessionKey(body);
                ReentrantLock sessionLock = appContext.getSessionLock(sessionKey);

                log.info("API 请求 sessionKey={} 内容={}", sessionKey, abbreviate(parsed.currentUserContent(), 80));

                sessionLock.lock();
                try {
                    if (streamEnabled) {
                        handleStreaming(exchange, parsed, sessionKey, modelName);
                        return;
                    }

                    syncHistoryIfNeeded(parsed, sessionKey);
                    String responseText = executeCompletionWithRetry(parsed.currentUserContent(), sessionKey);
                    writeJson(exchange, 200, chatCompletionResponse(responseText, modelName));
                } catch (TimeoutException e) {
                    writeErrorJson(
                            exchange,
                            504,
                            "请求超时（" + (appContext.getRequestTimeoutMillis() / 1000.0) + " 秒）",
                            "timeout_error"
                    );
                } catch (Exception e) {
                    log.error("处理 chat completions 同步请求失败: sessionKey={}", sessionKey, e);
                    writeErrorJson(exchange, 500, "服务器内部错误", "internal_error");
                } finally {
                    sessionLock.unlock();
                }
            }

            private Map<String, Object> readJsonRequestBody(HttpExchange exchange) throws IOException {
                try {
                    return MAPPER.readValue(readRequestBody(exchange), Map.class);
                } catch (Exception e) {
                    throw invalidRequest("JSON 请求体无效");
                }
            }

            private ParsedMessages parseIncomingMessages(List<Object> messages) {
                try {
                    return parseMessages(messages);
                } catch (IllegalArgumentException e) {
                    throw invalidRequest(e.getMessage());
                }
            }

            private String validateRequestedModel(Object requestedModel) {
                String modelName = appContext.getModelName();
                if (requestedModel != null && !modelName.equals(String.valueOf(requestedModel))) {
                    throw invalidRequest("仅支持已配置的模型 '" + modelName + "'");
                }
                return modelName;
            }

            private String resolveSessionKey(Map<String, Object> body) {
                Object sessionId = body.get("session_id");
                return sessionId != null ? "api:" + sessionId : API_SESSION_KEY;
            }

            private void syncHistoryIfNeeded(ParsedMessages parsed, String sessionKey) {
                if (!parsed.shouldSyncHistory()) {
                    return;
                }
                Session session = appContext.getAgentLoop().getSessions().getOrCreate(sessionKey);
                session.setMessages(parsed.history());
                appContext.getAgentLoop().getSessions().save(session);
            }

            private String executeCompletionWithRetry(String userContent, String sessionKey) throws Exception {
                String responseText = executeCompletionOnce(userContent, sessionKey);
                if (!isBlankResponse(responseText)) {
                    return responseText;
                }

                log.warn("会话 {} 返回空响应，准备重试", sessionKey);
                responseText = executeCompletionOnce(userContent, sessionKey);
                if (!isBlankResponse(responseText)) {
                    return responseText;
                }

                log.warn("会话 {} 重试后仍为空，使用兜底响应", sessionKey);
                return FALLBACK_RESPONSE;
            }

            private String executeCompletionOnce(String userContent, String sessionKey) throws Exception {
                Object response = runWithTimeout(
                        () -> appContext.getAgentLoop().processDirect(
                                userContent,
                                sessionKey,
                                "api",
                                API_CHAT_ID
                        ),
                        appContext.getRequestTimeoutMillis()
                );
                return RicbotApiServer.responseText(response);
            }

            private boolean isBlankResponse(String responseText) {
                return responseText == null || responseText.isBlank();
            }

            private InvalidRequestException invalidRequest(String message) {
                return new InvalidRequestException(400, message, "invalid_request_error");
            }

            /**
             * 处理流式聊天完成请求。
             *
             * @param exchange HTTP 交换对象
             * @param parsed 解析后的消息对象，包含用户内容和历史记录
             * @param sessionKey 会话键，用于隔离不同会话
             * @param modelName 模型名称
             * @throws IOException 如果发生 I/O 错误
             */
            private void handleStreaming(HttpExchange exchange, ParsedMessages parsed, String sessionKey, String modelName) throws IOException {
                // 设置响应头，指定内容类型为 SSE (Server-Sent Events)
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "text/event-stream; charset=utf-8");
                // 禁用缓存，确保客户端实时接收数据
                headers.set("Cache-Control", "no-cache");
                // 保持连接活跃，支持长连接
                headers.set("Connection", "keep-alive");
                // 发送响应头，状态码 200，内容长度未知（0 表示分块传输）
                exchange.sendResponseHeaders(200, 0);

                // 使用 try-with-resources 确保输出流和写入器正确关闭
                try (OutputStream os = exchange.getResponseBody();
                     Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {

                    // 生成唯一的流 ID，格式为 chatcmpl-xxxxxxxxxxxx
                    String streamId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

                    // 原子布尔值，用于跟踪是否已写入角色信息块
                    AtomicBoolean wroteRole = new AtomicBoolean(false);
                    // 原子布尔值，用于跟踪是否已写入停止信号块
                    AtomicBoolean wroteStop = new AtomicBoolean(false);

                    syncHistoryIfNeeded(parsed, sessionKey);

                    // 创建流式处理的 AgentHook 回调
                    AgentHook streamHook = new AgentHook(true) {
                        @Override
                        public boolean wantsStreaming() {
                            // 声明需要流式输出
                            return true;
                        }

                        @Override
                        public void onStream(AgentHookContext context, String delta) throws Exception {
                            // 确保先写入角色信息块（assistant）
                            ensureRoleChunk(writer, streamId, modelName, wroteRole);
                            // 写入当前增量内容的 SSE 数据块
                            writeSse(writer, streamChunk(streamId, modelName, delta, null));
                        }

                        @Override
                        public void onStreamEnd(AgentHookContext context, boolean resuming) throws Exception {
                            // 如果是恢复状态，则不执行结束逻辑
                            if (resuming) {
                                return;
                            }
                            // 确保先写入角色信息块
                            ensureRoleChunk(writer, streamId, modelName, wroteRole);
                            // 如果尚未写入停止信号，则写入 finish_reason 为 "stop" 的块
                            if (wroteStop.compareAndSet(false, true)) {
                                writeSse(writer, streamChunk(streamId, modelName, "", "stop"));
                            }
                        }
                    };

                    // 调用 AgentLoop 处理直接请求，传入流式钩子
                    appContext.getAgentLoop().processDirect(
                            parsed.currentUserContent(), // 用户当前输入内容
                            sessionKey,                  // 会话键
                            "api",                       // 渠道标识
                            API_CHAT_ID,                 // 聊天 ID
                            Map.of("_wants_stream", true), // 额外参数，标记需要流式
                            List.of(streamHook)          // 注册的钩子列表
                    );

                    // 再次确保角色块已写入（防止没有产生任何流式内容时的情况）
                    ensureRoleChunk(writer, streamId, modelName, wroteRole);
                    // 如果尚未写入停止信号，则补充写入
                    if (wroteStop.compareAndSet(false, true)) {
                        writeSse(writer, streamChunk(streamId, modelName, "", "stop"));
                    }

                    // 写入 SSE 结束标记 [DONE]
                    writer.write("data: [DONE]\n\n");
                    // 刷新缓冲区，确保所有数据发送给客户端
                    writer.flush();
                } catch (Exception e) {
                    log.error("处理流式 chat completions 失败: sessionKey={}", sessionKey, e);
                    throw new IOException("streaming chat failed", e);
                }
            }
        }

    /**
     * GET /v1/models
     */
    public static class ModelsHandler implements HttpHandler {
        private final ApiAppContext appContext;

        public ModelsHandler(ApiAppContext appContext) {
            this.appContext = appContext;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }

            if (!appContext.isAuthorized(exchange)) {
                writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("id", appContext.getModelName());
            model.put("object", "model");
            model.put("created", 0);
            model.put("owned_by", "ricbot");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("object", "list");
            body.put("data", List.of(model));

            writeJson(exchange, 200, body);
        }
    }

    /**
     * GET /health
     */
    public static class HealthHandler implements HttpHandler {
        private final ApiAppContext appContext;

        public HealthHandler(ApiAppContext appContext) {
            this.appContext = appContext;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }

            if (!appContext.isAuthorized(exchange)) {
                writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }

            writeJson(exchange, 200, Map.of("status", "ok"));
        }
    }

    // ---------------------------------------------------------------------
    // Timeout helper
    // ---------------------------------------------------------------------

    /**
     * 给同步调用包一层超时控制。
     *
     * 因为 Python 版是 asyncio.wait_for(...)
     * Java 这里用 Future + timeout 模拟。
     */
    /**
     * 带超时控制的同步任务执行器。
     *
     * 模拟 Python 中的 asyncio.wait_for 行为，用于防止 AgentLoop 处理请求时无限阻塞。
     *
     * @param task 需要执行的任务 callable
     * @param timeoutMillis 超时时间（毫秒）
     * @return 任务执行结果
     * @throws Exception 如果任务执行异常或超时
     */
    public static Object runWithTimeout(CallableTask task, long timeoutMillis) throws Exception {
        // 创建单线程执行器，确保任务在独立线程中运行
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 提交任务并获取 Future 对象，用于后续获取结果或取消任务
            Future<Object> future = executor.submit(task::call);
            try {
                // 等待任务完成，如果在指定时间内未完成则抛出 TimeoutException
                return future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            } finally {
                // 无论任务是否成功，都尝试取消任务（如果仍在运行）
                // true 表示如果任务正在运行，则中断该线程
                future.cancel(true);
            }
        } finally {
            // 确保执行器被关闭，释放线程资源
            // shutdownNow 尝试停止所有正在执行的任务
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface CallableTask {
        Object call() throws Exception;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static final class InvalidRequestException extends RuntimeException {
        private final int status;
        private final String errorType;

        private InvalidRequestException(int status, String message, String errorType) {
            super(message);
            this.status = status;
            this.errorType = errorType;
        }

        private int status() {
            return status;
        }

        private String errorType() {
            return errorType;
        }
    }

    private record ParsedMessages(String currentUserContent, List<Map<String, Object>> history, boolean shouldSyncHistory) {
    }

    public static Map<String, Object> streamChunk(String id, String model, String deltaContent, String finishReason) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (deltaContent != null && !deltaContent.isEmpty()) {
            delta.put("content", deltaContent);
        }

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", Instant.now().getEpochSecond());
        chunk.put("model", model);
        chunk.put("choices", List.of(choice));
        return chunk;
    }

    public static Map<String, Object> streamRoleChunk(String id, String model) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", null);

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", Instant.now().getEpochSecond());
        chunk.put("model", model);
        chunk.put("choices", List.of(choice));
        return chunk;
    }

    private static void ensureRoleChunk(Writer writer, String id, String model, AtomicBoolean wroteRole) throws IOException {
        if (wroteRole.compareAndSet(false, true)) {
            writeSse(writer, streamRoleChunk(id, model));
        }
    }

    private static void writeSse(Writer writer, Object payload) throws IOException {
        writer.write("data: " + MAPPER.writeValueAsString(payload) + "\n\n");
        writer.flush();
    }

    private static ParsedMessages parseMessages(List<Object> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            throw new IllegalArgumentException("messages 必须是非空数组");
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        boolean hasNonUser = false;

        for (Object item : rawMessages) {
            Map<String, Object> msg = asMap(item);
            if (msg == null) {
                throw new IllegalArgumentException("messages[] 的每个元素都必须是对象");
            }
            String role = String.valueOf(msg.getOrDefault("role", "")).trim();
            if (role.isBlank()) {
                throw new IllegalArgumentException("messages[] 的每个元素都必须包含非空 role");
            }

            if (!List.of("system", "user", "assistant", "tool").contains(role)) {
                throw new IllegalArgumentException("不支持的 role：" + role + "。支持：system、user、assistant、tool");
            }

            if (!"user".equals(role)) {
                hasNonUser = true;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("role", role);

            if ("tool".equals(role)) {
                Object toolCallId = msg.get("tool_call_id");
                if (toolCallId != null) {
                    out.put("tool_call_id", String.valueOf(toolCallId));
                }
                Object name = msg.get("name");
                if (name != null) {
                    out.put("name", String.valueOf(name));
                }
            }

            if ("assistant".equals(role)) {
                Object toolCalls = msg.get("tool_calls");
                if (toolCalls instanceof List<?> list) {
                    out.put("tool_calls", list);
                }
            }

            out.put("content", extractTextContent(msg.get("content")));
            normalized.add(out);
        }

        Map<String, Object> last = normalized.get(normalized.size() - 1);
        String lastRole = String.valueOf(last.get("role"));

        String currentUserContent = "";
        List<Map<String, Object>> history;
        boolean shouldSync = normalized.size() > 1 || hasNonUser;

        if ("user".equals(lastRole)) {
            currentUserContent = String.valueOf(last.getOrDefault("content", ""));
            history = new ArrayList<>(normalized.subList(0, normalized.size() - 1));
        } else {
            history = new ArrayList<>(normalized);
        }

        return new ParsedMessages(currentUserContent, history, shouldSync);
    }

    private static String extractTextContent(Object contentObj) {
        if (contentObj == null) {
            return "";
        }
        if (contentObj instanceof String s) {
            return s;
        }
        if (contentObj instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object partObj : parts) {
                Map<String, Object> part = asMap(partObj);
                if (part != null && "text".equals(part.get("type"))) {
                    String text = String.valueOf(part.getOrDefault("text", ""));
                    if (!text.isBlank()) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        }
        return String.valueOf(contentObj);
    }

}
