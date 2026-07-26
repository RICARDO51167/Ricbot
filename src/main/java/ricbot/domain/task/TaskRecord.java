package ricbot.domain.task;

import java.time.Instant;
import java.util.Objects;

public record TaskRecord(
        int schemaVersion,
        TaskSpec spec,
        TaskStatus status,
        String childRunId,
        String detail,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant heartbeatAt,
        long version,
        int attempt,
        Instant createdAt,
        Instant updatedAt
) {
    public TaskRecord {
        if (schemaVersion != 2) throw new IllegalArgumentException("unsupported task schema");
        spec = Objects.requireNonNull(spec, "spec");
        status = Objects.requireNonNullElse(status, TaskStatus.PLANNED);
        childRunId = childRunId != null ? childRunId.trim() : "";
        detail = detail != null ? detail : "";
        leaseOwner = leaseOwner != null ? leaseOwner.trim() : "";
        if (version < 0) throw new IllegalArgumentException("version must be non-negative");
        if (attempt < 1) attempt = 1;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        updatedAt = Objects.requireNonNullElse(updatedAt, createdAt);
    }

    public static TaskRecord planned(TaskSpec spec) {
        Instant now = Instant.now();
        return new TaskRecord(2, spec, TaskStatus.PLANNED, "", "", "", null, null,
                0, 1, now, now);
    }

    public TaskRecord transition(TaskStatus next, String nextChildRunId, String nextDetail) {
        if (status.terminal()) {
            if (status == next) return this;
            throw new IllegalStateException("terminal task cannot transition from " + status + " to " + next);
        }
        boolean settled = next.terminal() || next == TaskStatus.CANCEL_REQUESTED
                || next == TaskStatus.WAITING_CONFIRMATION;
        return new TaskRecord(2, spec, next, nextChildRunId != null ? nextChildRunId : childRunId,
                nextDetail, settled ? "" : leaseOwner, settled ? null : leaseExpiresAt,
                settled ? null : heartbeatAt, version + 1, attempt, createdAt, Instant.now());
    }

    public TaskRecord claim(String owner, String runId, Instant now, Instant expiresAt) {
        if (status != TaskStatus.READY) throw new IllegalStateException("only READY tasks can be claimed");
        return new TaskRecord(2, spec, TaskStatus.RUNNING, runId, "worker started", required(owner, "owner"),
                Objects.requireNonNull(expiresAt, "expiresAt"), Objects.requireNonNull(now, "now"),
                version + 1, attempt, createdAt, now);
    }

    public TaskRecord heartbeat(String owner, Instant now, Instant expiresAt) {
        if (status != TaskStatus.RUNNING || !leaseOwner.equals(required(owner, "owner"))) {
            throw new IllegalStateException("task lease is not owned by this runtime");
        }
        return new TaskRecord(2, spec, status, childRunId, detail, leaseOwner, expiresAt, now,
                version + 1, attempt, createdAt, now);
    }

    public TaskRecord recovering(String reason) {
        if (status != TaskStatus.RUNNING && status != TaskStatus.RECOVERING) {
            throw new IllegalStateException("only an interrupted running task can recover");
        }
        return new TaskRecord(2, spec, TaskStatus.RECOVERING, childRunId, reason, "", null, null,
                version + 1, attempt, createdAt, Instant.now());
    }

    public TaskRecord retry() {
        if (!status.terminal() && status != TaskStatus.WAITING_CONFIRMATION) {
            throw new IllegalStateException("only settled tasks can be retried");
        }
        return new TaskRecord(2, spec, TaskStatus.PLANNED, "", "manual retry", "", null, null, version + 1,
                attempt + 1, createdAt, Instant.now());
    }

    public TaskRecord(int schemaVersion, TaskSpec spec, TaskStatus status, String childRunId, String detail,
                      long version, Instant createdAt, Instant updatedAt) {
        this(schemaVersion, spec, status, childRunId, detail, version, 1, createdAt, updatedAt);
    }

    public TaskRecord(int schemaVersion, TaskSpec spec, TaskStatus status, String childRunId, String detail,
                      long version, int attempt, Instant createdAt, Instant updatedAt) {
        this(schemaVersion, spec, status, childRunId, detail, "", null, null, version, attempt,
                createdAt, updatedAt);
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
