package ricbot.domain.agent.graph;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record GraphRuntimeEvent(
        String eventId,
        String runId,
        long sequence,
        long superstep,
        GraphRuntimeEventType type,
        Map<String, Object> data,
        Instant occurredAt
) {
    public GraphRuntimeEvent {
        eventId = required(eventId, "eventId");
        runId = required(runId, "runId");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        if (superstep < 0) throw new IllegalArgumentException("superstep must be non-negative");
        type = Objects.requireNonNull(type, "type");
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data != null ? data : Map.of()));
        occurredAt = Objects.requireNonNullElseGet(occurredAt, Instant::now);
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
