package ricbot.domain.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reconstructed model context and durable state at an exact journal boundary. */
public record ResumePoint(
        String sessionKey,
        RunCheckpoint checkpoint,
        RunState runState,
        List<Map<String, Object>> messages,
        Instant reconstructedAt
) {
    public ResumePoint {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("sessionKey is required");
        }
        checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        runState = Objects.requireNonNull(runState, "runState");
        messages = immutableMessages(messages);
        reconstructedAt = Objects.requireNonNullElseGet(reconstructedAt, Instant::now);
    }

    public List<Map<String, Object>> mutableMessages() {
        return messages.stream().map(ResumePoint::mutableMap).toList();
    }

    private static List<Map<String, Object>> immutableMessages(List<Map<String, Object>> source) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> message : source != null ? source : List.<Map<String, Object>>of()) {
            copy.add(immutableMap(message));
        }
        return List.copyOf(copy);
    }

    private static Map<String, Object> immutableMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(String.valueOf(entry.getKey()), immutableValue(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) return immutableMap(map);
        if (value instanceof List<?> list) return list.stream().map(ResumePoint::immutableValue).toList();
        return value;
    }

    private static Map<String, Object> mutableMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) copy.put(String.valueOf(key), mutableValue(value));
        });
        return copy;
    }

    private static Object mutableValue(Object value) {
        if (value instanceof Map<?, ?> map) return mutableMap(map);
        if (value instanceof List<?> list) return list.stream().map(ResumePoint::mutableValue).toList();
        return value;
    }
}
