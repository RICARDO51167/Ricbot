package ricbot.integration.llm.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * LLM Provider 抽象基类，提供共享的请求/响应清洗、错误处理及统一重试框架。
 */
public abstract class LLMProvider {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected static final List<Integer> CHAT_RETRY_DELAYS = List.of(1, 2, 4);

    protected static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(408, 409, 429);

    protected static final Set<String> TRANSIENT_ERROR_KINDS = Set.of("timeout", "connection");

    protected static final Set<String> NON_RETRYABLE_429_ERROR_TOKENS = Set.of(
            "insufficient_quota",
            "quota_exceeded",
            "quota_exhausted",
            "billing_hard_limit_reached",
            "insufficient_balance",
            "credit_balance_too_low",
            "billing_not_active",
            "payment_required"
    );

    protected static final Set<String> RETRYABLE_429_ERROR_TOKENS = Set.of(
            "rate_limit_exceeded",
            "rate_limit_error",
            "too_many_requests",
            "request_limit_exceeded",
            "requests_limit_exceeded",
            "overloaded_error"
    );

    protected static final List<String> NON_RETRYABLE_429_TEXT_MARKERS = List.of(
            "insufficient_quota",
            "insufficient quota",
            "quota exceeded",
            "quota exhausted",
            "billing hard limit",
            "billing_hard_limit_reached",
            "billing not active",
            "insufficient balance",
            "insufficient_balance",
            "credit balance too low",
            "payment required",
            "out of credits",
            "out of quota",
            "exceeded your current quota"
    );

    protected static final List<String> RETRYABLE_429_TEXT_MARKERS = List.of(
            "rate limit",
            "rate_limit",
            "too many requests",
            "retry after",
            "try again in",
            "temporarily unavailable",
            "overloaded",
            "concurrency limit"
    );

    protected String apiKey;

    protected String apiBase;

    protected String defaultModel;

    protected GenerationSettings generation = new GenerationSettings();

    public LLMProvider() {
    }

    public LLMProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public abstract LLMResponse chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            Integer maxTokens,
            Double temperature,
            String reasoningEffort,
            Object toolChoice
    ) throws Exception;

    public LLMResponse chatWithRetry(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model
    ) throws Exception {
        return runWithRetry(() -> chat(messages, tools, model, null, null, null, null));
    }

    public LLMResponse chatStream(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            Integer maxTokens,
            Double temperature,
            String reasoningEffort,
            Object toolChoice,
            StreamDeltaHandler onDelta,
            StreamEndHandler onEnd
    ) throws Exception {
        LLMResponse response = chat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice);
        if (response.getContent() != null && onDelta != null) {
            onDelta.handle(response.getContent());
        }
        if (onEnd != null) {
            onEnd.handle(response);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> sanitizeEmptyContent(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }

        for (Map<String, Object> msg : messages) {
            Map<String, Object> clean = new LinkedHashMap<>(msg);
            Object content = msg.get("content");
            Object role = msg.get("role");

            if (content instanceof String s && s.isEmpty()) {
                if ("assistant".equals(role) && msg.get("tool_calls") != null) {
                    clean.put("content", null);
                } else {
                    clean.put("content", "(空)");
                }
                result.add(clean);
                continue;
            }

            if (content instanceof List<?> list) {
                List<Object> newItems = new ArrayList<>();
                boolean changed = false;

                for (Object item : list) {
                    if (item instanceof Map<?, ?> raw) {
                        Map<String, Object> dict = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : raw.entrySet()) {
                            dict.put(String.valueOf(e.getKey()), e.getValue());
                        }

                        Object type = dict.get("type");
                        if ((Objects.equals(type, "text")
                                || Objects.equals(type, "input_text")
                                || Objects.equals(type, "output_text"))
                                && !dict.containsKey("text")) {
                            changed = true;
                            continue;
                        }

                        if (dict.containsKey("_meta")) {
                            dict.remove("_meta");
                            changed = true;
                        }
                        newItems.add(dict);
                    } else {
                        newItems.add(item);
                    }
                }

                if (changed) {
                    if (!newItems.isEmpty()) {
                        clean.put("content", newItems);
                    } else if ("assistant".equals(role) && msg.get("tool_calls") != null) {
                        clean.put("content", null);
                    } else {
                        clean.put("content", "(空)");
                    }
                }
                result.add(clean);
                continue;
            }

            if (content instanceof Map<?, ?> map) {
                clean.put("content", List.of(new LinkedHashMap<>(map)));
                result.add(clean);
                continue;
            }

            result.add(clean);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    public static String toolName(Map<String, Object> tool) {
        Object name = tool.get("name");
        if (name instanceof String s) {
            return s;
        }
        Object fn = tool.get("function");
        if (fn instanceof Map<?, ?> map) {
            Object fname = ((Map<String, Object>) map).get("name");
            if (fname instanceof String s) {
                return s;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    public static String[] extractErrorTypeCode(Object payload) {
        Map<String, Object> data = null;

        if (payload instanceof Map<?, ?> map) {
            data = (Map<String, Object>) map;
        } else if (payload instanceof String text && !text.isBlank()) {
            try {
                data = MAPPER.readValue(text, new TypeReference<>() {});
            } catch (Exception ignored) {
            }
        }

        if (data == null) {
            return new String[]{null, null};
        }

        Object errorObj = data.get("error");
        Object typeValue = data.get("type");
        Object codeValue = data.get("code");

        if (errorObj instanceof Map<?, ?> errMap) {
            Object t = ((Map<String, Object>) errMap).get("type");
            Object c = ((Map<String, Object>) errMap).get("code");
            if (t != null) typeValue = t;
            if (c != null) codeValue = c;
        }

        return new String[]{normalizeErrorToken(typeValue), normalizeErrorToken(codeValue)};
    }

    public static String normalizeErrorToken(Object value) {
        if (value == null) {
            return null;
        }
        String token = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return token.isBlank() ? null : token;
    }

    public static boolean isRetryable429Response(LLMResponse response) {
        String typeToken = normalizeErrorToken(response.getErrorType());
        String codeToken = normalizeErrorToken(response.getErrorCode());

        Set<String> semanticTokens = new HashSet<>();
        if (typeToken != null) semanticTokens.add(typeToken);
        if (codeToken != null) semanticTokens.add(codeToken);

        for (String token : semanticTokens) {
            if (NON_RETRYABLE_429_ERROR_TOKENS.contains(token)) {
                return false;
            }
        }

        String content = response.getContent() != null
                ? response.getContent().toLowerCase(Locale.ROOT)
                : "";

        for (String marker : NON_RETRYABLE_429_TEXT_MARKERS) {
            if (content.contains(marker)) {
                return false;
            }
        }

        for (String token : semanticTokens) {
            if (RETRYABLE_429_ERROR_TOKENS.contains(token)) {
                return true;
            }
        }

        for (String marker : RETRYABLE_429_TEXT_MARKERS) {
            if (content.contains(marker)) {
                return true;
            }
        }

        return true;
    }

    public static boolean isTransientResponse(LLMResponse response) {
        if (response.getErrorShouldRetry() != null) {
            return Boolean.TRUE.equals(response.getErrorShouldRetry());
        }

        if (response.getErrorStatusCode() != null) {
            int status = response.getErrorStatusCode();
            if (status == 429) {
                return isRetryable429Response(response);
            }
            if (RETRYABLE_STATUS_CODES.contains(status) || status >= 500) {
                return true;
            }
        }

        String kind = response.getErrorKind() != null
                ? response.getErrorKind().trim().toLowerCase(Locale.ROOT)
                : "";

        if (TRANSIENT_ERROR_KINDS.contains(kind)) {
            return true;
        }

        String content = response.getContent() != null
                ? response.getContent().toLowerCase(Locale.ROOT)
                : "";

        return content.contains("429")
                || content.contains("rate limit")
                || content.contains("500")
                || content.contains("502")
                || content.contains("503")
                || content.contains("504")
                || content.contains("overloaded")
                || content.contains("timeout")
                || content.contains("timed out")
                || content.contains("connection")
                || content.contains("server error")
                || content.contains("temporarily unavailable");
    }

    public static Double extractRetryAfterFromHeaders(Map<String, ?> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }

        Object value = headers.get("retry-after");
        if (value == null) value = headers.get("Retry-After");
        if (value == null) return null;

        String s = String.valueOf(value).trim();
        if (s.isBlank()) return null;

        try {
            return Double.parseDouble(s);
        } catch (Exception ignored) {
        }

        try {
            ZonedDateTime dt = ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = (dt.toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000;
            return Math.max(0, (double) seconds);
        } catch (Exception ignored) {
        }

        return null;
    }

    public static Double extractRetryAfter(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String lower = text.toLowerCase(Locale.ROOT);

        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("retry after\\s*(\\d+(?:\\.\\d+)?)").matcher(lower);
        if (m1.find()) {
            return Double.parseDouble(m1.group(1));
        }

        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("try again in\\s*(\\d+(?:\\.\\d+)?)").matcher(lower);
        if (m2.find()) {
            return Double.parseDouble(m2.group(1));
        }

        return null;
    }

    public LLMResponse runWithRetry(Callable<LLMResponse> call) throws Exception {
        LLMResponse last = null;

        for (int i = 0; i <= CHAT_RETRY_DELAYS.size(); i++) {
            last = call.call();
            if (last == null) {
                return null;
            }

            if (!"error".equals(last.getFinishReason()) || !isTransientResponse(last)) {
                return last;
            }

            if (i < CHAT_RETRY_DELAYS.size()) {
                double delay = last.getRetryAfter() != null ? last.getRetryAfter() : CHAT_RETRY_DELAYS.get(i);
                try {
                    Thread.sleep((long) (delay * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return last;
                }
            }
        }

        return last;
    }

    @FunctionalInterface
    public interface StreamDeltaHandler {
        void handle(String delta) throws Exception;
    }

    @FunctionalInterface
    public interface StreamEndHandler {
        void handle(LLMResponse response) throws Exception;
    }
}