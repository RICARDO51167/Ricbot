package ricbot.domain.task;

import java.time.Instant;
import java.util.Objects;

public record TaskRecord(
        int schemaVersion,
        TaskSpec spec,
        TaskStatus status,
        String childRunId,
        String detail,
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
        if (version < 0) throw new IllegalArgumentException("version must be non-negative");
        if (attempt < 1) attempt = 1;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        updatedAt = Objects.requireNonNullElse(updatedAt, createdAt);
    }

    public static TaskRecord planned(TaskSpec spec) {
        Instant now = Instant.now();
        return new TaskRecord(2, spec, TaskStatus.PLANNED, "", "", 0, 1, now, now);
    }

    public TaskRecord transition(TaskStatus next, String nextChildRunId, String nextDetail) {
        if (status.terminal()) {
            if (status == next) return this;
            throw new IllegalStateException("terminal task cannot transition from " + status + " to " + next);
        }
        return new TaskRecord(2, spec, next, nextChildRunId != null ? nextChildRunId : childRunId,
                nextDetail, version + 1, attempt, createdAt, Instant.now());
    }

    public TaskRecord retry() {
        if (!status.terminal() && status != TaskStatus.WAITING_CONFIRMATION) {
            throw new IllegalStateException("only settled tasks can be retried");
        }
        return new TaskRecord(2, spec, TaskStatus.PLANNED, "", "manual retry", version + 1,
                attempt + 1, createdAt, Instant.now());
    }

    public TaskRecord(int schemaVersion, TaskSpec spec, TaskStatus status, String childRunId, String detail,
                      long version, Instant createdAt, Instant updatedAt) {
        this(schemaVersion, spec, status, childRunId, detail, version, 1, createdAt, updatedAt);
    }
}
