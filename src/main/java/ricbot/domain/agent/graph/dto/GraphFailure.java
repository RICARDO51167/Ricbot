package ricbot.domain.agent.graph.dto;

import java.time.Instant;
import java.util.Objects;

public record GraphFailure(
        String activationId,
        String nodeId,
        String kind,
        String message,
        int attempt,
        boolean tolerated,
        Instant occurredAt
) {
    public GraphFailure {
        activationId = required(activationId, "activationId");
        nodeId = required(nodeId, "nodeId");
        kind = required(kind, "kind");
        message = message != null ? message : "";
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        occurredAt = Objects.requireNonNullElseGet(occurredAt, Instant::now);
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
