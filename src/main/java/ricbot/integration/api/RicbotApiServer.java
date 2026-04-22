package ricbot.integration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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
    public static class ApiAppContext {
        private final AgentLoop agentLoop;
        private final String modelName;
        private final long requestTimeoutMillis;
        private final String bindHost;
        private final String bearerToken;
        private final boolean requireAuth;
        private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

        public ApiAppContext(
                AgentLoop agentLoop,
                String modelName,
                long requestTimeoutMillis,
                String bindHost,
                String bearerToken
        ) {
            this.agentLoop = agentLoop;
            this.modelName = modelName != null ? modelName : "ricbot";
            this.requestTimeoutMillis = requestTimeoutMillis > 0 ? requestTimeoutMillis : 120_000L;
            this.bindHost = bindHost != null && !bindHost.isBlank() ? bindHost : "127.0.0.1";
            this.bearerToken = bearerToken != null ? bearerToken.trim() : "";
            this.requireAuth = !this.bearerToken.isBlank() || !isLoopbackHost(this.bindHost);
        }

        public AgentLoop getAgentLoop() {
            return agentLoop;
        }

        public String getModelName() {
            return modelName;
        }

        public long getRequestTimeoutMillis() {
            return requestTimeoutMillis;
        }

        public boolean isAuthorized(HttpExchange exchange) {
            if (!requireAuth) {
                return true;
            }
            if (bearerToken.isBlank()) {
                return false;
            }

            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return false;
            }

            String provided = header.substring(7).trim();
            return MessageDigest.isEqual(
                    bearerToken.getBytes(StandardCharsets.UTF_8),
                    provided.getBytes(StandardCharsets.UTF_8)
            );
        }

        public ReentrantLock getSessionLock(String sessionKey) {
            return sessionLocks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
        }

        private static boolean isLoopbackHost(String host) {
            if (host == null || host.isBlank()) {
                return false;
            }
            try {
                return InetAddress.getByName(host).isLoopbackAddress();
            } catch (Exception e) {
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
    public static class ChatCompletionsHandler implements HttpHandler {

        private final ApiAppContext appContext;

        public ChatCompletionsHandler(ApiAppContext appContext) {
            this.appContext = appContext;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
                return;
            }

            if (!appContext.isAuthorized(exchange)) {
                writeErrorJson(exchange, 401, "缺少或无效的 Bearer token", "authentication_error");
                return;
            }

            Map<String, Object> body;
            try {
                String raw = readRequestBody(exchange);
                body = MAPPER.readValue(raw, Map.class);
            } catch (Exception e) {
                writeErrorJson(exchange, 400, "JSON 请求体无效", "invalid_request_error");
                return;
            }

            List<Object> messages = asList(body.get("messages"));
            if (messages == null || messages.isEmpty()) {
                writeErrorJson(exchange, 400, "messages 必须是非空数组", "invalid_request_error");
                return;
            }

            Object stream = body.get("stream");
            boolean streamEnabled = Boolean.TRUE.equals(stream);

            ParsedMessages parsed;
            try {
                parsed = parseMessages(messages);
            } catch (IllegalArgumentException e) {
                writeErrorJson(exchange, 400, e.getMessage(), "invalid_request_error");
                return;
            }
            String userContent = parsed.currentUserContent();

            String modelName = appContext.getModelName();
            Object requestedModel = body.get("model");
            if (requestedModel != null && !modelName.equals(String.valueOf(requestedModel))) {
                writeErrorJson(exchange, 400, "仅支持已配置的模型 '" + modelName + "'", "invalid_request_error");
                return;
            }

            String sessionKey = body.get("session_id") != null
                    ? "api:" + body.get("session_id")
                    : API_SESSION_KEY;

            ReentrantLock sessionLock = appContext.getSessionLock(sessionKey);

            System.out.println("API 请求 sessionKey=" + sessionKey + " 内容=" +
                    userContent.substring(0, Math.min(userContent.length(), 80)));

            final String FALLBACK = RuntimeConstants.EMPTY_FINAL_RESPONSE_MESSAGE;

            try {
                sessionLock.lock();
                try {
                    if (streamEnabled) {
                        handleStreaming(exchange, parsed, sessionKey, modelName);
                        return;
                    }

                    String responseText;

                    try {
                        if (parsed.shouldSyncHistory()) {
                            Session session = appContext.getAgentLoop().getSessions().getOrCreate(sessionKey);
                            session.setMessages(parsed.history());
                            appContext.getAgentLoop().getSessions().save(session);
                        }

                        Object response = runWithTimeout(
                                () -> appContext.getAgentLoop().processDirect(
                                        userContent,
                                        sessionKey,
                                        "api",
                                        API_CHAT_ID
                                ),
                                appContext.getRequestTimeoutMillis()
                        );

                        responseText = RicbotApiServer.responseText(response);

                        // 空响应时自动重试一次
                        if (responseText == null || responseText.isBlank()) {
                            System.out.println("会话 " + sessionKey + " 返回空响应，正在重试");

                            Object retryResponse = runWithTimeout(
                                    () -> appContext.getAgentLoop().processDirect(
                                            userContent,
                                            sessionKey,
                                            "api",
                                            API_CHAT_ID
                                    ),
                                    appContext.getRequestTimeoutMillis()
                            );

                            responseText = RicbotApiServer.responseText(retryResponse);

                            if (responseText == null || responseText.isBlank()) {
                                System.out.println("会话 " + sessionKey + " 重试后仍为空，使用兜底响应");
                                responseText = FALLBACK;
                            }
                        }

                    } catch (TimeoutException e) {
                        writeErrorJson(
                                exchange,
                                504,
                                "请求超时（" + (appContext.getRequestTimeoutMillis() / 1000.0) + " 秒）",
                                "timeout_error"
                        );
                        return;
                    } catch (Exception e) {
                        e.printStackTrace();
                        writeErrorJson(exchange, 500, "服务器内部错误", "internal_error");
                        return;
                    }

                    writeJson(exchange, 200, chatCompletionResponse(responseText, modelName));

                } finally {
                    sessionLock.unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
                writeErrorJson(exchange, 500, "服务器内部错误", "internal_error");
            }
        }

        private void handleStreaming(HttpExchange exchange, ParsedMessages parsed, String sessionKey, String modelName) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-cache");
            headers.set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody();
                 Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                String streamId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                AtomicBoolean wroteRole = new AtomicBoolean(false);
                AtomicBoolean wroteStop = new AtomicBoolean(false);

                if (parsed.shouldSyncHistory()) {
                    Session session = appContext.getAgentLoop().getSessions().getOrCreate(sessionKey);
                    session.setMessages(parsed.history());
                    appContext.getAgentLoop().getSessions().save(session);
                }

                AgentHook streamHook = new AgentHook(true) {
                    @Override
                    public boolean wantsStreaming() {
                        return true;
                    }

                    @Override
                    public void onStream(AgentHookContext context, String delta) throws Exception {
                        ensureRoleChunk(writer, streamId, modelName, wroteRole);
                        writeSse(writer, streamChunk(streamId, modelName, delta, null));
                    }

                    @Override
                    public void onStreamEnd(AgentHookContext context, boolean resuming) throws Exception {
                        if (resuming) {
                            return;
                        }
                        ensureRoleChunk(writer, streamId, modelName, wroteRole);
                        if (wroteStop.compareAndSet(false, true)) {
                            writeSse(writer, streamChunk(streamId, modelName, "", "stop"));
                        }
                    }
                };

                appContext.getAgentLoop().processDirect(
                        parsed.currentUserContent(),
                        sessionKey,
                        "api",
                        API_CHAT_ID,
                        Map.of("_wants_stream", true),
                        List.of(streamHook)
                );

                ensureRoleChunk(writer, streamId, modelName, wroteRole);
                if (wroteStop.compareAndSet(false, true)) {
                    writeSse(writer, streamChunk(streamId, modelName, "", "stop"));
                }
                writer.write("data: [DONE]\n\n");
                writer.flush();
            } catch (Exception e) {
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
    public static Object runWithTimeout(CallableTask task, long timeoutMillis) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Object> future = executor.submit(task::call);
        try {
            return future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } finally {
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface CallableTask {
        Object call() throws Exception;
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
