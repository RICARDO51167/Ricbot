package ricbot.domain.agent.graph.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Persisted condition that must be satisfied by an external signal. */
public record GraphWait(
        String waitId,
        String activationId,
        String type,
        String reason,
        Map<String, Object> details,
        Instant createdAt
) {
    public GraphWait {
        waitId = required(waitId, "waitId");
        activationId = required(activationId, "activationId");
        type = required(type, "type");
        reason = reason != null ? reason.trim() : "";
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details != null ? details : Map.of()));
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    public static GraphWait external(String waitId, String activationId, String type, String reason,
                                     Map<String, Object> details) {
        return new GraphWait(waitId, activationId, type, reason, details, Instant.now());
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
