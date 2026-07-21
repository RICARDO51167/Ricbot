package ricbot.domain.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates durable worker identity, lifecycle and mailbox only.
 * Model/tool execution, checkpoints and journals remain owned by Run services.
 */
public final class WorkerRuntime {
    private final WorkerStore store;

    public WorkerRuntime(Path workspace) {
        this(new WorkerStore(workspace));
    }

    WorkerRuntime(WorkerStore store) {
        this.store = store;
    }

    public WorkerStore.StoredWorker create(
            String scopeId,
            String role,
            String goal,
            String parentWorkerId,
            String idempotencyKey,
            Map<String, Object> metadata
    ) {
        String workerId = stableWorkerId(scopeId, idempotencyKey);
        return create(WorkerSpec.create(workerId, scopeId, role, goal, parentWorkerId,
                idempotencyKey, metadata));
    }

    public WorkerStore.StoredWorker create(WorkerSpec spec) {
        return store.create(spec);
    }

    public Optional<WorkerStore.StoredWorker> worker(String workerId) {
        return store.load(workerId);
    }

    public List<WorkerStore.StoredWorker> workers() {
        return store.list();
    }

    public WorkerState transition(
            String workerId,
            WorkerState.Status status,
            String runId,
            String detail
    ) {
        WorkerStore.StoredWorker worker = store.load(workerId).orElseThrow(() ->
                new IllegalArgumentException("worker does not exist: " + workerId));
        WorkerState current = worker.state();
        WorkerState next = current.transition(status, runId, detail);
        if (next == current) return current;
        return store.saveState(next, current.version());
    }

    public WorkerState prepare(String workerId) {
        return transition(workerId, WorkerState.Status.READY, null, "prepared");
    }

    public WorkerState start(String workerId, String runId) {
        return transition(workerId, WorkerState.Status.RUNNING, runId, "run started");
    }

    public WorkerState awaitMessage(String workerId, String detail) {
        return transition(workerId, WorkerState.Status.WAITING, null, detail);
    }

    public WorkerState complete(String workerId, String detail) {
        return transition(workerId, WorkerState.Status.COMPLETED, null, detail);
    }

    public WorkerState fail(String workerId, String detail) {
        return transition(workerId, WorkerState.Status.FAILED, null, detail);
    }

    public WorkerState cancel(String workerId, String detail) {
        return transition(workerId, WorkerState.Status.CANCELLED, null, detail);
    }

    public WorkerState recover(String workerId, String detail) {
        return transition(workerId, WorkerState.Status.RECOVERING, null, detail);
    }

    public WorkerStore.MailboxMessage send(
            String fromWorkerId,
            String toWorkerId,
            WorkerStore.MessageKind kind,
            String correlationId,
            Map<String, Object> payload
    ) {
        return store.send(fromWorkerId, toWorkerId, kind, correlationId, payload);
    }

    public List<WorkerStore.MailboxMessage> inbox(
            String workerId, long afterSequence, boolean includeAcknowledged) {
        return store.inbox(workerId, afterSequence, includeAcknowledged);
    }

    public void acknowledge(String workerId, String messageId) {
        store.acknowledge(workerId, messageId);
    }

    private static String stableWorkerId(String scopeId, String idempotencyKey) {
        String scope = required(scopeId, "scopeId");
        String key = idempotencyKey != null ? idempotencyKey.trim() : "";
        UUID id = key.isBlank()
                ? UUID.randomUUID()
                : UUID.nameUUIDFromBytes((scope + "\u0000" + key).getBytes(StandardCharsets.UTF_8));
        return "worker-" + id;
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
