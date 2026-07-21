package ricbot.domain.worker;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned lifecycle state with an explicit cancel and recovery transition table. */
public record WorkerState(
        int schemaVersion,
        String workerId,
        Status status,
        String currentRunId,
        long version,
        String detail,
        Instant updatedAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final Map<Status, Set<Status>> TRANSITIONS = transitionTable();

    public WorkerState {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported worker state schema: " + schemaVersion);
        }
        workerId = required(workerId, "workerId");
        status = Objects.requireNonNullElse(status, Status.CREATED);
        currentRunId = clean(currentRunId);
        if (version < 0) throw new IllegalArgumentException("version cannot be negative");
        detail = clean(detail);
        updatedAt = Objects.requireNonNullElseGet(updatedAt, Instant::now);
    }

    public static WorkerState created(String workerId) {
        return new WorkerState(CURRENT_SCHEMA_VERSION, workerId, Status.CREATED, "", 0, "", Instant.now());
    }

    public WorkerState transition(Status next, String runId, String transitionDetail) {
        Status target = Objects.requireNonNull(next, "next status is required");
        if (target == status) return this;
        if (!canTransition(status, target)) {
            throw new IllegalStateException("illegal worker transition: " + status + " -> " + target);
        }
        String nextRunId = runId != null ? clean(runId) : currentRunId;
        return new WorkerState(schemaVersion, workerId, target, nextRunId, version + 1,
                transitionDetail, Instant.now());
    }

    public static boolean canTransition(Status from, Status to) {
        if (from == null || to == null) return false;
        if (from == to) return true;
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static Map<Status, Set<Status>> allowedTransitions() {
        return TRANSITIONS;
    }

    private static Map<Status, Set<Status>> transitionTable() {
        EnumMap<Status, Set<Status>> table = new EnumMap<>(Status.class);
        table.put(Status.CREATED, immutable(Status.READY, Status.CANCELLED));
        table.put(Status.READY, immutable(Status.RUNNING, Status.CANCELLED));
        table.put(Status.RUNNING, immutable(Status.WAITING, Status.RECOVERING,
                Status.COMPLETED, Status.FAILED, Status.CANCELLED));
        table.put(Status.WAITING, immutable(Status.RUNNING, Status.RECOVERING,
                Status.FAILED, Status.CANCELLED));
        table.put(Status.RECOVERING, immutable(Status.READY, Status.RUNNING,
                Status.FAILED, Status.CANCELLED));
        table.put(Status.FAILED, immutable(Status.RECOVERING));
        table.put(Status.COMPLETED, Set.of());
        table.put(Status.CANCELLED, Set.of());
        return Collections.unmodifiableMap(table);
    }

    private static Set<Status> immutable(Status first, Status... rest) {
        EnumSet<Status> values = EnumSet.of(first, rest);
        return Collections.unmodifiableSet(values);
    }

    private static String required(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    public enum Status {
        CREATED,
        READY,
        RUNNING,
        WAITING,
        RECOVERING,
        COMPLETED,
        FAILED,
        CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == CANCELLED;
        }
    }
}
