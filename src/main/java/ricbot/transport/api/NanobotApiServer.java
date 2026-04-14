package ricbot.transport.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import ricbot.core.agent.AgentLoop;
import ricbot.core.message.OutboundMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

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
public class NanobotApiServer {

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
     * @param port 请求端口
     * @param agentLoop 已初始化好的 AgentLoop
     * @param modelName 对外报告的模型名
     * @param requestTimeoutMillis 单请求超时毫秒数
     */
    public static HttpServer createAndStart(
            int port,
            AgentLoop agentLoop,
            String modelName,
            long requestTimeoutMillis
    ) throws IOException {
        ApiAppContext appContext = new ApiAppContext(agentLoop, modelName, requestTimeoutMillis);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/v1/chat/completions", new ChatCompletionsHandler(appContext));
        server.createContext("/v1/models", new ModelsHandler(appContext));
        server.createContext("/health", new HealthHandler());
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
        private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

        public ApiAppContext(AgentLoop agentLoop, String modelName, long requestTimeoutMillis) {
            this.agentLoop = agentLoop;
            this.modelName = modelName != null ? modelName : "oldricbot";
            this.requestTimeoutMillis = requestTimeoutMillis > 0 ? requestTimeoutMillis : 120_000L;
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

        public ReentrantLock getSessionLock(String sessionKey) {
            return sessionLocks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
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
                writeErrorJson(exchange, 405, "Method not allowed", "invalid_request_error");
                return;
            }

            Map<String, Object> body;
            try {
                String raw = readRequestBody(exchange);
                body = MAPPER.readValue(raw, Map.class);
            } catch (Exception e) {
                writeErrorJson(exchange, 400, "Invalid JSON body", "invalid_request_error");
                return;
            }

            List<Object> messages = asList(body.get("messages"));
            if (messages == null || messages.size() != 1) {
                writeErrorJson(exchange, 400, "Only a single user message is supported", "invalid_request_error");
                return;
            }

            Object stream = body.get("stream");
            if (Boolean.TRUE.equals(stream)) {
                writeErrorJson(exchange, 400, "stream=true is not supported yet. Set stream=false or omit it.", "invalid_request_error");
                return;
            }

            Map<String, Object> message = asMap(messages.get(0));
            if (message == null || !"user".equals(message.get("role"))) {
                writeErrorJson(exchange, 400, "Only a single user message is supported", "invalid_request_error");
                return;
            }

            Object userContentObj = message.getOrDefault("content", "");
            String userContent;

            // 支持多模态 content 数组，但这里只提取 text 部分
            if (userContentObj instanceof List<?> parts) {
                StringBuilder sb = new StringBuilder();
                for (Object partObj : parts) {
                    Map<String, Object> part = asMap(partObj);
                    if (part != null && "text".equals(part.get("type"))) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(String.valueOf(part.getOrDefault("text", "")));
                    }
                }
                userContent = sb.toString();
            } else {
                userContent = String.valueOf(userContentObj);
            }

            String modelName = appContext.getModelName();
            Object requestedModel = body.get("model");
            if (requestedModel != null && !modelName.equals(String.valueOf(requestedModel))) {
                writeErrorJson(exchange, 400, "Only configured model '" + modelName + "' is available", "invalid_request_error");
                return;
            }

            String sessionKey = body.get("session_id") != null
                    ? "api:" + body.get("session_id")
                    : API_SESSION_KEY;

            ReentrantLock sessionLock = appContext.getSessionLock(sessionKey);

            System.out.println("API request sessionKey=" + sessionKey + " content=" +
                    userContent.substring(0, Math.min(userContent.length(), 80)));

            final String FALLBACK = RuntimeConstants.EMPTY_FINAL_RESPONSE_MESSAGE;

            try {
                sessionLock.lock();
                try {
                    String responseText;

                    try {
                        Object response = runWithTimeout(
                                () -> appContext.getAgentLoop().processDirect(
                                        userContent,
                                        sessionKey,
                                        "api",
                                        API_CHAT_ID
                                ),
                                appContext.getRequestTimeoutMillis()
                        );

                        responseText = NanobotApiServer.responseText(response);

                        // 空响应时自动重试一次
                        if (responseText == null || responseText.isBlank()) {
                            System.out.println("Empty response for session " + sessionKey + ", retrying");

                            Object retryResponse = runWithTimeout(
                                    () -> appContext.getAgentLoop().processDirect(
                                            userContent,
                                            sessionKey,
                                            "api",
                                            API_CHAT_ID
                                    ),
                                    appContext.getRequestTimeoutMillis()
                            );

                            responseText = NanobotApiServer.responseText(retryResponse);

                            if (responseText == null || responseText.isBlank()) {
                                System.out.println("Empty response after retry for session " + sessionKey + ", using fallback");
                                responseText = FALLBACK;
                            }
                        }

                    } catch (TimeoutException e) {
                        writeErrorJson(
                                exchange,
                                504,
                                "Request timed out after " + (appContext.getRequestTimeoutMillis() / 1000.0) + "s",
                                "invalid_request_error"
                        );
                        return;
                    } catch (Exception e) {
                        e.printStackTrace();
                        writeErrorJson(exchange, 500, "Internal server error", "server_error");
                        return;
                    }

                    writeJson(exchange, 200, chatCompletionResponse(responseText, modelName));

                } finally {
                    sessionLock.unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
                writeErrorJson(exchange, 500, "Internal server error", "server_error");
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
                writeErrorJson(exchange, 405, "Method not allowed", "invalid_request_error");
                return;
            }

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("id", appContext.getModelName());
            model.put("object", "model");
            model.put("created", 0);
            model.put("owned_by", "oldricbot");

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
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeErrorJson(exchange, 405, "Method not allowed", "invalid_request_error");
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
}