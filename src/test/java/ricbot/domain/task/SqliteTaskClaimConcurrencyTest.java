package ricbot.domain.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.task.TaskRole;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteTaskClaimConcurrencyTest {
    @TempDir Path workspace;

    @Test
    void twoSchedulersExecuteOneReadyTaskOnce() throws Exception {
        SqliteRuntimeStore firstStore = new SqliteRuntimeStore(workspace);
        SqliteRuntimeStore secondStore = new SqliteRuntimeStore(workspace);
        TaskSpec spec = new TaskSpec("task", "parent", "activation", 0, 0, TaskRole.EXPLORER,
                "inspect", List.of(), List.of("read"), TaskWorkspaceMode.SHARED_READ,
                TaskFailurePolicy.TOLERATE, false);
        TaskRecord planned = firstStore.create(TaskRecord.planned(spec));
        firstStore.save(planned.transition(TaskStatus.READY, "", "ready"), planned.version());
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        TaskExecutor executor = context -> {
            executions.incrementAndGet();
            completed.countDown();
            return new TaskResult(2, context.task().spec().taskId(), context.task().spec().parentRunId(),
                    context.task().childRunId(), context.task().attempt(), TaskStatus.SUCCEEDED, 0, "done",
                    Map.of(), "", List.of(), List.of("evidence"), "", Instant.now());
        };
        try (LocalTaskScheduler first = new LocalTaskScheduler(workspace, firstStore, firstStore,
                     LocalTaskSchedulerConfig.defaults(), Set.of("read"));
             LocalTaskScheduler second = new LocalTaskScheduler(workspace, secondStore, secondStore,
                     LocalTaskSchedulerConfig.defaults(), Set.of("read"))) {
            first.register(TaskRole.EXPLORER, executor);
            second.register(TaskRole.EXPLORER, executor);
            Thread a = new Thread(first::recover);
            Thread b = new Thread(second::recover);
            a.start(); b.start(); a.join(); b.join();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && firstStore.loadResult("task").isEmpty()) Thread.sleep(10);
            assertEquals(1, executions.get());
            assertEquals(TaskStatus.SUCCEEDED, firstStore.load("task").orElseThrow().status());
            assertEquals(1, firstStore.pending("parent").size());
        }
    }
}
