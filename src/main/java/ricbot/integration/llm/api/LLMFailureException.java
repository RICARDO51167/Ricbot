package ricbot.integration.llm.api;

import java.util.Locale;

/** Typed provider failure. Provider-specific text never drives graph policy directly. */
public final class LLMFailureException extends Exception {
    private final LLMFailureKind kind;
    private final Integer statusCode;
    private final Double retryAfterSeconds;

    public LLMFailureException(LLMFailureKind kind, String message, Integer statusCode,
                               Double retryAfterSeconds, Throwable cause) {
        super(message != null && !message.isBlank() ? message : kind.name(), cause);
        this.kind = kind != null ? kind : LLMFailureKind.PERMANENT;
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public LLMFailureKind kind() { return kind; }
    public Integer statusCode() { return statusCode; }
    public Double retryAfterSeconds() { return retryAfterSeconds; }

    public static LLMResponse requireSuccess(LLMResponse response) throws LLMFailureException {
        if (response == null) throw new LLMFailureException(LLMFailureKind.PERMANENT,
                "provider returned no response", null, null, null);
        if (!"error".equalsIgnoreCase(response.getFinishReason())) return response;
        throw fromResponse(response);
    }

    public static LLMFailureException classify(Throwable failure) {
        if (failure instanceof LLMFailureException typed) return typed;
        String message = failure != null && failure.getMessage() != null ? failure.getMessage() : "provider failure";
        String lower = message.toLowerCase(Locale.ROOT);
        LLMFailureKind kind = containsOverflow(lower) ? LLMFailureKind.CONTEXT_OVERFLOW
                : containsAuth(lower) ? LLMFailureKind.AUTH
                : containsRateLimit(lower) ? LLMFailureKind.RATE_LIMIT
                : containsTransient(lower) ? LLMFailureKind.TRANSIENT : LLMFailureKind.PERMANENT;
        return new LLMFailureException(kind, message, null, null, failure);
    }

    private static LLMFailureException fromResponse(LLMResponse response) {
        int status = response.getErrorStatusCode() != null ? response.getErrorStatusCode() : 0;
        String semantic = join(response.getErrorType(), response.getErrorCode(), response.getErrorKind(),
                response.getContent()).toLowerCase(Locale.ROOT);
        LLMFailureKind kind = containsOverflow(semantic) || status == 413 ? LLMFailureKind.CONTEXT_OVERFLOW
                : status == 401 || status == 403 || containsAuth(semantic) ? LLMFailureKind.AUTH
                : status == 429 || containsRateLimit(semantic) ? LLMFailureKind.RATE_LIMIT
                : LLMProvider.isTransientResponse(response) ? LLMFailureKind.TRANSIENT
                : LLMFailureKind.PERMANENT;
        return new LLMFailureException(kind, response.getContent(), response.getErrorStatusCode(),
                response.getRetryAfter(), null);
    }

    private static boolean containsOverflow(String value) {
        return value.contains("context_length_exceeded") || value.contains("context window")
                || value.contains("maximum context") || value.contains("too many tokens")
                || value.contains("prompt is too long") || value.contains("input length");
    }
    private static boolean containsAuth(String value) {
        return value.contains("unauthorized") || value.contains("invalid api key")
                || value.contains("authentication") || value.contains("permission denied");
    }
    private static boolean containsRateLimit(String value) {
        return value.contains("rate limit") || value.contains("rate_limit") || value.contains("too many requests");
    }
    private static boolean containsTransient(String value) {
        return value.contains("timeout") || value.contains("timed out") || value.contains("connection")
                || value.contains("temporarily unavailable") || value.contains("overloaded")
                || value.contains(" 500") || value.contains(" 502") || value.contains(" 503")
                || value.contains(" 504");
    }
    private static String join(Object... values) {
        StringBuilder out = new StringBuilder();
        for (Object value : values) if (value != null) out.append(value).append(' ');
        return out.toString();
    }
}
