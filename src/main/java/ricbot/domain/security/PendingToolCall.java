package ricbot.domain.security;

import java.time.Instant;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PendingToolCall(
        String requestId,
        String toolName,
        Map<String, Object> arguments,
        String sessionId,
        String createdAt,
        RiskAssessment riskAssessment,
        boolean consumed
) {
    public PendingToolCall {
        arguments = arguments != null ? Collections.unmodifiableMap(sanitizeMap(arguments)) : Map.of();
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : Instant.now().toString();
    }

    public static PendingToolCall create(
            String requestId,
            String toolName,
            Map<String, Object> arguments,
            String sessionId,
            RiskAssessment riskAssessment
    ) {
        return new PendingToolCall(requestId, toolName, arguments, sessionId, Instant.now().toString(), riskAssessment, false);
    }

    public PendingToolCall withRequestId(String nextRequestId) {
        return new PendingToolCall(nextRequestId, toolName, arguments, sessionId, createdAt, riskAssessment, consumed);
    }

    public PendingToolCall markConsumed() {
        return new PendingToolCall(requestId, toolName, arguments, sessionId, createdAt, riskAssessment, true);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("toolName", toolName);
        out.put("arguments", arguments);
        out.put("sessionId", sessionId);
        out.put("createdAt", createdAt);
        out.put("riskAssessment", riskAssessment != null ? riskAssessment.toMap() : Map.of());
        out.put("consumed", consumed);
        return out;
    }

    private static Map<String, Object> sanitizeMap(Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            out.put(String.valueOf(entry.getKey()), sanitizeValue(entry.getValue()));
        }
        return out;
    }

    private static Object sanitizeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : iterable) {
                out.add(sanitizeValue(item));
            }
            return List.copyOf(out);
        }
        if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                out.add(sanitizeValue(Array.get(value, i)));
            }
            return List.copyOf(out);
        }
        return String.valueOf(value);
    }
}
