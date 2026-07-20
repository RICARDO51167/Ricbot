package ricbot.domain.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A typed, append-only state transition in the durable agent runtime. */
public record RunEvent(
        int schemaVersion,
        String eventId,
        long sequence,
        String runId,
        String sessionKey,
        int iteration,
        RunEventType type,
        RunStatus status,
        ToolInvocationRecord toolInvocation,
        Map<String, Object> details,
        Instant occurredAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunEvent {
        if (schemaVersion <= 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported run event schema version: " + schemaVersion);
        }
        eventId = requireText(eventId, "eventId");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        runId = requireText(runId, "runId");
        sessionKey = requireText(sessionKey, "sessionKey");
        iteration = Math.max(0, iteration);
        type = Objects.requireNonNull(type, "type");
        status = Objects.requireNonNull(status, "status");
        details = details != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(details))
                : Map.of();
        occurredAt = Objects.requireNonNullElseGet(occurredAt, Instant::now);
    }

    public static RunEvent create(
            long sequence,
            String runId,
            String sessionKey,
            int iteration,
            RunEventType type,
            RunStatus status,
            ToolInvocationRecord toolInvocation,
            Map<String, Object> details
    ) {
        return new RunEvent(
                CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                sequence,
                runId,
                sessionKey,
                iteration,
                type,
                status,
                toolInvocation,
                details,
                Instant.now()
        );
    }

    private static String requireText(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return clean;
    }
}
