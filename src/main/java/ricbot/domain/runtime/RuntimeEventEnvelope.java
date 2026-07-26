package ricbot.domain.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/** The sole durable fact emitted by the production runtime. Payloads are explicitly type-tagged. */
public record RuntimeEventEnvelope(
        int schemaVersion,
        long globalSequence,
        long streamSequence,
        String eventId,
        String streamId,
        String eventType,
        String payloadType,
        String runId,
        String sessionId,
        String taskId,
        String activationId,
        String causationId,
        String correlationId,
        String traceParent,
        Instant occurredAt,
        JsonNode payload
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RuntimeEventEnvelope {
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        if (globalSequence < 0 || streamSequence < 1) throw new IllegalArgumentException("invalid event sequence");
        eventId = required(eventId, "eventId");
        streamId = required(streamId, "streamId");
        eventType = required(eventType, "eventType");
        payloadType = required(payloadType, "payloadType");
        runId = clean(runId);
        sessionId = clean(sessionId);
        taskId = clean(taskId);
        activationId = clean(activationId);
        causationId = clean(causationId);
        correlationId = clean(correlationId);
        traceParent = clean(traceParent);
        occurredAt = Objects.requireNonNullElseGet(occurredAt, Instant::now);
        payload = Objects.requireNonNull(payload, "payload");
    }

    private static String required(String value, String field) {
        String result = clean(value);
        if (result.isBlank()) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
