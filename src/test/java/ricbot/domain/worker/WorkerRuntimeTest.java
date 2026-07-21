package ricbot.domain.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRuntimeTest {
    @Test
    void createsIdempotentStableWorkerAndRestoresIt(@TempDir Path workspace) {
        WorkerRuntime runtime = new WorkerRuntime(workspace);
        WorkerStore.StoredWorker first = runtime.create(
                "team-1", "developer", "implement", "leader", "spawn-request-1", Map.of("task", "t-1"));
        WorkerStore.StoredWorker duplicate = runtime.create(
                "team-1", "developer", "implement", "leader", "spawn-request-1", Map.of("task", "t-1"));

        assertEquals(first.spec().workerId(), duplicate.spec().workerId());
        assertEquals(1, runtime.workers().size());

        WorkerRuntime restarted = new WorkerRuntime(workspace);
        assertEquals(first, restarted.worker(first.spec().workerId()).orElseThrow());
    }

    @Test
    void concurrentRetriesCreateOneWorker(@TempDir Path workspace) {
        List<String> ids = IntStream.range(0, 20).parallel()
                .mapToObj(ignored -> new WorkerRuntime(workspace).create(
                        "team", "developer", "implement", "leader", "same-request", Map.of())
                        .spec().workerId())
                .distinct()
                .toList();

        assertEquals(1, ids.size());
        assertEquals(1, new WorkerRuntime(workspace).workers().size());
    }

    @Test
    void enforcesExplicitCancelAndRecoverTransitions(@TempDir Path workspace) {
        WorkerRuntime runtime = new WorkerRuntime(workspace);
        String recoverable = runtime.create(
                "team", "reviewer", "review", "leader", "request-recover", Map.of()).spec().workerId();
        runtime.prepare(recoverable);
        runtime.start(recoverable, "run-1");
        runtime.fail(recoverable, "provider unavailable");
        assertEquals(WorkerState.Status.RECOVERING, runtime.recover(recoverable, "restart").status());
        assertEquals(WorkerState.Status.READY,
                runtime.transition(recoverable, WorkerState.Status.READY, null, "checkpoint loaded").status());

        String cancelled = runtime.create(
                "team", "tester", "test", "leader", "request-cancel", Map.of()).spec().workerId();
        runtime.prepare(cancelled);
        assertEquals(WorkerState.Status.CANCELLED, runtime.cancel(cancelled, "user cancelled").status());
        assertThrows(IllegalStateException.class, () -> runtime.recover(cancelled, "must stay terminal"));
    }

    @Test
    void persistsResultInMailboxWithoutInMemoryNotification(@TempDir Path workspace) {
        WorkerRuntime runtime = new WorkerRuntime(workspace);
        String leader = runtime.create(
                "team", "leader", "coordinate", "", "leader", Map.of()).spec().workerId();
        String worker = runtime.create(
                "team", "developer", "implement", leader, "worker", Map.of()).spec().workerId();

        WorkerStore.MailboxMessage result = runtime.send(
                worker, leader, WorkerStore.MessageKind.RESULT, "run-1", Map.of("summary", "done"));

        WorkerRuntime restarted = new WorkerRuntime(workspace);
        assertEquals(List.of(result), restarted.inbox(leader, 0, false));
        restarted.acknowledge(leader, result.messageId());
        assertTrue(restarted.inbox(leader, 0, false).isEmpty());
        assertFalse(restarted.inbox(leader, 0, true).isEmpty());
    }

    @Test
    void exposesCompleteTransitionTable() {
        assertEquals(WorkerState.Status.values().length, WorkerState.allowedTransitions().size());
        assertTrue(WorkerState.canTransition(WorkerState.Status.RUNNING, WorkerState.Status.CANCELLED));
        assertTrue(WorkerState.canTransition(WorkerState.Status.FAILED, WorkerState.Status.RECOVERING));
        assertFalse(WorkerState.canTransition(WorkerState.Status.COMPLETED, WorkerState.Status.RECOVERING));
        assertFalse(WorkerState.canTransition(WorkerState.Status.CANCELLED, WorkerState.Status.RUNNING));
    }
}
