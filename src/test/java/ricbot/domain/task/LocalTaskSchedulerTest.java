package ricbot.domain.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.graph.InMemoryGraphRuntimeStore;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.domain.task.TaskRole;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTaskSchedulerTest {
    @TempDir Path workspace;

    @Test
    void schedulesDagWithBoundedParallelismAndOrderedDependencies() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        SqliteRuntimeStore waker = store;
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch firstPair = new CountDownLatch(2);
        try (LocalTaskScheduler scheduler = new LocalTaskScheduler(workspace, store, waker,
                new LocalTaskSchedulerConfig(2, 16, 8, 2), Set.of("read"))) {
            scheduler.register(TaskRole.EXPLORER, context -> {
                int active = concurrent.incrementAndGet();
                maximum.accumulateAndGet(active, Math::max);
                firstPair.countDown();
                firstPair.await(2, TimeUnit.SECONDS);
                concurrent.decrementAndGet();
                return success(context.task(), context.task().spec().taskId());
            });
            scheduler.register(TaskRole.REVIEWER, context -> {
                assertEquals(List.of("a", "b"), context.dependencyResults().stream()
                        .map(TaskResult::summary).toList());
                return success(context.task(), "joined");
            });
            scheduler.submit(new TeamPlan("p", "run", 0, List.of(
                    spec("a", 0, TaskRole.EXPLORER, List.of()),
                    spec("b", 1, TaskRole.EXPLORER, List.of()),
                    spec("join", 2, TaskRole.REVIEWER, List.of("a", "b"))
            )));
            awaitTerminal(scheduler, "join");
            assertEquals(2, maximum.get());
            assertEquals("joined", scheduler.result("join").summary());
            assertEquals(List.of("a", "b", "join"), waker.pending("run").stream()
                    .map(TaskDelivery::taskId).toList());
        }
    }

    @Test
    void criticalFailureCancelsPendingTasksAndDeliveryIsUnique() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        SqliteRuntimeStore waker = store;
        try (LocalTaskScheduler scheduler = new LocalTaskScheduler(workspace, store, waker,
                new LocalTaskSchedulerConfig(1, 8, 8, 2), Set.of())) {
            scheduler.register(TaskRole.DEVELOPER, context -> { throw new IllegalStateException("boom"); });
            scheduler.register(TaskRole.VERIFIER, context -> success(context.task(), "must not run"));
            scheduler.submit(new TeamPlan("p", "run", 0, List.of(
                    new TaskSpec("write", "run", "act", 0, 0, TaskRole.DEVELOPER, "write", List.of(),
                            List.of(), TaskWorkspaceMode.ISOLATED_WORKTREE, TaskFailurePolicy.FAIL_FAST, false),
                    new TaskSpec("verify", "run", "act", 1, 0, TaskRole.VERIFIER, "verify", List.of("write"),
                            List.of(), TaskWorkspaceMode.INTEGRATION_WORKTREE, TaskFailurePolicy.FAIL_FAST, false)
            )));
            awaitTerminal(scheduler, "verify");
            assertEquals(TaskStatus.FAILED, scheduler.task("write").status());
            assertEquals(TaskStatus.CANCELLED, scheduler.task("verify").status());
            TaskResult result = scheduler.result("write");
            String first = waker.deliver(result);
            String second = waker.deliver(result);
            assertEquals(first, second);
            assertEquals(2, waker.pending("run").size());
        }
    }

    @Test
    void rejectsCyclesUnauthorizedToolsAndIllegalWriterWorkspace() {
        TeamPlanValidator validator = new TeamPlanValidator(8, 2, Set.of(TaskRole.values()), Set.of("read"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new TeamPlan("p", "run", 0, List.of(
                spec("a", 0, TaskRole.EXPLORER, List.of("b")), spec("b", 1, TaskRole.EXPLORER, List.of("a"))))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new TeamPlan("p", "run", 0, List.of(
                new TaskSpec("a", "run", "act", 0, 0, TaskRole.EXPLORER, "goal", List.of(), List.of("exec"),
                        TaskWorkspaceMode.SHARED_READ, TaskFailurePolicy.TOLERATE, false)))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new TeamPlan("p", "run", 0, List.of(
                spec("write", 0, TaskRole.DEVELOPER, List.of())))));
    }

    @Test
    void rootCancellationInterruptsRunningFutureAndPersistsCancellation() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        SqliteRuntimeStore waker = store;
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (LocalTaskScheduler scheduler = new LocalTaskScheduler(workspace, store, waker,
                new LocalTaskSchedulerConfig(1, 8, 8, 2), Set.of("read"))) {
            scheduler.register(TaskRole.EXPLORER, context -> {
                started.countDown();
                try { Thread.sleep(30_000); }
                catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); throw e; }
                return success(context.task(), "unexpected");
            });
            scheduler.submit(new TeamPlan("p", "cancel-run", 0, List.of(
                    new TaskSpec("long", "cancel-run", "act", 0, 0, TaskRole.EXPLORER, "long", List.of(),
                            List.of("read"), TaskWorkspaceMode.SHARED_READ, TaskFailurePolicy.TOLERATE, false))));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(1, scheduler.cancelParent("cancel-run", "root cancelled"));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(TaskStatus.CANCELLED, scheduler.task("long").status());
            assertEquals(TaskStatus.CANCELLED, scheduler.result("long").status());
        }
    }

    private static TaskSpec spec(String id, int order, TaskRole role, List<String> dependencies) {
        return new TaskSpec(id, "run", "act", order, 0, role, id, dependencies, List.of("read"),
                TaskWorkspaceMode.SHARED_READ, TaskFailurePolicy.TOLERATE, false);
    }
    private static TaskResult success(TaskRecord record, String summary) {
        return new TaskResult(2, record.spec().taskId(), record.spec().parentRunId(), record.childRunId(),
                TaskStatus.SUCCEEDED, record.spec().planOrder(), summary, Map.of(), "", List.of(), List.of(), "", Instant.now());
    }
    private static void awaitTerminal(LocalTaskScheduler scheduler, String taskId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            TaskRecord record = scheduler.task(taskId);
            if (record != null && record.status().terminal()) return;
            Thread.sleep(10);
        }
        assertTrue(scheduler.task(taskId).status().terminal(), "task did not reach terminal status");
    }
}
