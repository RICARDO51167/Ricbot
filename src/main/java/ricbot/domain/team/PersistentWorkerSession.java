package ricbot.domain.team;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Independent durable worker identity and lifecycle. */
public record PersistentWorkerSession(
        int schemaVersion,
        String teamSessionId,
        String workerId,
        TeamRole role,
        WorkerSessionStatus status,
        String currentTaskId,
        String parentWorkerId,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public PersistentWorkerSession {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported worker session schema");
        teamSessionId = required(teamSessionId, "teamSessionId");
        workerId = required(workerId, "workerId");
        role = Objects.requireNonNullElse(role, TeamRole.DEVELOPER);
        status = Objects.requireNonNullElse(status, WorkerSessionStatus.CREATED);
        currentTaskId = clean(currentTaskId);
        parentWorkerId = clean(parentWorkerId);
        metadata = metadata != null
                ? java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata))
                : Map.of();
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        updatedAt = Objects.requireNonNullElse(updatedAt, createdAt);
    }

    public static PersistentWorkerSession create(
            String teamSessionId, String workerId, TeamRole role, String parentWorkerId, Map<String, Object> metadata) {
        Instant now = Instant.now();
        return new PersistentWorkerSession(1, teamSessionId, workerId, role, WorkerSessionStatus.CREATED,
                "", parentWorkerId, metadata, now, now);
    }

    public PersistentWorkerSession transition(WorkerSessionStatus next, String taskId) {
        if (!allowed(status, next)) throw new IllegalStateException("illegal worker transition: " + status + " -> " + next);
        return new PersistentWorkerSession(schemaVersion, teamSessionId, workerId, role, next,
                taskId != null ? taskId : currentTaskId, parentWorkerId, metadata, createdAt, Instant.now());
    }

    private static boolean allowed(WorkerSessionStatus from, WorkerSessionStatus to) {
        if (from == to) return true;
        if (from.terminal()) return false;
        return switch (from) {
            case CREATED -> to == WorkerSessionStatus.RUNNING || to == WorkerSessionStatus.CANCELLED;
            case RUNNING -> to == WorkerSessionStatus.PAUSED || to.terminal();
            case PAUSED -> to == WorkerSessionStatus.RUNNING || to == WorkerSessionStatus.CANCELLED;
            default -> false;
        };
    }

    private static String required(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
