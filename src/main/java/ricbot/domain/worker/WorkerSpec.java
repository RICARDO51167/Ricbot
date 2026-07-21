package ricbot.domain.worker;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Durable worker identity and intent. Execution details belong to a Run. */
public record WorkerSpec(
        int schemaVersion,
        String workerId,
        String scopeId,
        String role,
        String goal,
        String parentWorkerId,
        String idempotencyKey,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorkerSpec {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported worker spec schema: " + schemaVersion);
        }
        workerId = required(workerId, "workerId");
        scopeId = required(scopeId, "scopeId");
        role = required(role, "role");
        goal = clean(goal);
        parentWorkerId = clean(parentWorkerId);
        idempotencyKey = clean(idempotencyKey);
        metadata = metadata != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
                : Map.of();
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    public static WorkerSpec create(
            String workerId,
            String scopeId,
            String role,
            String goal,
            String parentWorkerId,
            String idempotencyKey,
            Map<String, Object> metadata
    ) {
        return new WorkerSpec(CURRENT_SCHEMA_VERSION, workerId, scopeId, role, goal,
                parentWorkerId, idempotencyKey, metadata, Instant.now());
    }

    private static String required(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
