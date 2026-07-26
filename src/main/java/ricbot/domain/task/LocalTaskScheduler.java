package ricbot.domain.task;

import ricbot.domain.task.TaskRole;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The single local scheduler used by team and spawn workflows. */
public final class LocalTaskScheduler implements AutoCloseable {
    private static final java.time.Duration CLAIM_LEASE = java.time.Duration.ofSeconds(30);
    private static final Map<String, LocalTaskScheduler> ACTIVE_PARENTS = new ConcurrentHashMap<>();
    private final Path workspace;
    private final TaskStore store;
    private final ParentRunWaker waker;
    private final TeamPlanValidator validator;
    private final ThreadPoolExecutor executor;
    private final Map<TaskRole, TaskExecutor> executors = new EnumMap<>(TaskRole.class);
    private final Map<String, Future<?>> running = new ConcurrentHashMap<>();
    private volatile boolean closing;

    public LocalTaskScheduler(Path workspace, TaskStore store, ParentRunWaker waker,
                              LocalTaskSchedulerConfig config, Set<String> allowedTools) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.store = store;
        this.waker = waker;
        LocalTaskSchedulerConfig safe = config != null ? config : LocalTaskSchedulerConfig.defaults();
        this.validator = new TeamPlanValidator(safe.maxTasksPerPlan(), safe.maxDelegationDepth(),
                Set.of(TaskRole.values()), allowedTools != null ? allowedTools : Set.of());
        this.executor = new ThreadPoolExecutor(safe.maxParallel(), safe.maxParallel(), 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(safe.queueCapacity()), new SchedulerThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized LocalTaskScheduler register(TaskRole role, TaskExecutor taskExecutor) {
        if (role == null || taskExecutor == null) throw new IllegalArgumentException("role and executor are required");
        executors.put(role, taskExecutor);
        return this;
    }

    public synchronized List<TaskRecord> submit(TeamPlan untrustedPlan) {
        if (closing) throw new IllegalStateException("scheduler is closing");
        TeamPlan plan = validator.validate(untrustedPlan);
        ACTIVE_PARENTS.put(plan.parentRunId(), this);
        List<TaskRecord> records = plan.tasks().stream().map(TaskRecord::planned).map(store::create).toList();
        scheduleReady(plan.parentRunId());
        return records;
    }

    public List<TaskRecord> tasks(String parentRunId) { return store.listByParent(parentRunId); }
    public TaskRecord task(String taskId) { return store.load(taskId).orElse(null); }
    public TaskResult result(String taskId) { return store.loadResult(taskId).orElse(null); }
    public ParentRunWaker parentRunWaker() { return waker; }
    public int runningCount() { return (int) running.values().stream().filter(future -> !future.isDone()).count(); }

    public synchronized int cancelParent(String parentRunId, String reason) {
        int cancelled = 0;
        for (TaskRecord record : store.listByParent(parentRunId)) {
            if (record.status().terminal()) continue;
            Future<?> future = running.get(record.spec().taskId());
            if (future != null) future.cancel(true);
            settle(record, TaskResult.cancelled(record, reason != null ? reason : "parent run cancelled"));
            cancelled++;
        }
        return cancelled;
    }

    public static int cancelActiveParent(String parentRunId, String reason) {
        LocalTaskScheduler scheduler = ACTIVE_PARENTS.get(parentRunId);
        return scheduler != null ? scheduler.cancelParent(parentRunId, reason) : 0;
    }

    public synchronized boolean cancelTask(String taskId, String reason) {
        TaskRecord record = store.load(taskId).orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        if (record.status().terminal()) return false;
        Future<?> future = running.get(taskId);
        if (future != null) future.cancel(true);
        settle(record, TaskResult.cancelled(record, reason != null ? reason : "task cancelled"));
        scheduleReady(record.spec().parentRunId());
        return true;
    }

    public synchronized void recover() {
        for (TaskRecord record : store.listAll()) {
            if (record.status() == TaskStatus.RUNNING
                    && record.updatedAt().isAfter(Instant.now().minus(CLAIM_LEASE))) {
                // Another live runtime may own this claim. Only an expired lease is recoverable.
                continue;
            }
            if (record.status() == TaskStatus.RUNNING || record.status() == TaskStatus.RECOVERING) {
                TaskRecord recovering = record.status() == TaskStatus.RECOVERING ? record
                        : store.save(record.transition(TaskStatus.RECOVERING, record.childRunId(),
                            "scheduler process restarted"), record.version());
                if (store.loadResult(record.spec().taskId()).isPresent()) {
                    settle(recovering, store.loadResult(record.spec().taskId()).orElseThrow());
                } else if (record.spec().workspaceMode() == TaskWorkspaceMode.SHARED_READ) {
                    store.save(recovering.transition(TaskStatus.READY, recovering.childRunId(), "safe read-only recovery"),
                            recovering.version());
                } else {
                    store.save(recovering.transition(TaskStatus.WAITING_CONFIRMATION, recovering.childRunId(),
                            "write result is unknown; manual confirmation required"), recovering.version());
                }
            }
        }
        store.listAll().stream().map(record -> record.spec().parentRunId()).distinct().forEach(parentRunId -> {
            ACTIVE_PARENTS.put(parentRunId, this);
            scheduleReady(parentRunId);
        });
    }

    private synchronized void scheduleReady(String parentRunId) {
        if (closing) return;
        List<TaskRecord> records = store.listByParent(parentRunId);
        Map<String, TaskRecord> byId = new LinkedHashMap<>();
        records.forEach(record -> byId.put(record.spec().taskId(), record));
        for (TaskRecord record : records) {
            if (record.status() != TaskStatus.PLANNED && record.status() != TaskStatus.READY) continue;
            List<TaskRecord> dependencies = record.spec().dependsOn().stream().map(byId::get).toList();
            if (dependencies.stream().anyMatch(dependency -> dependency == null || !dependency.status().terminal())) continue;
            boolean failedDependency = dependencies.stream().anyMatch(dependency ->
                    dependency.status() == TaskStatus.FAILED || dependency.status() == TaskStatus.CANCELLED
                            || dependency.status() == TaskStatus.SKIPPED);
            if (failedDependency && !record.spec().allowFailedDependencies()) {
                settle(record, TaskResult.skipped(record, "dependency did not succeed"));
                continue;
            }
            TaskRecord ready = record.status() == TaskStatus.READY ? record
                    : store.save(record.transition(TaskStatus.READY, record.childRunId(), "dependencies satisfied"), record.version());
            dispatch(ready);
        }
    }

    private void dispatch(TaskRecord record) {
        if (running.containsKey(record.spec().taskId())) return;
        TaskExecutor taskExecutor = executors.get(record.spec().role());
        if (taskExecutor == null) {
            settle(record, TaskResult.failed(record, new IllegalStateException("no executor for role " + record.spec().role())));
            return;
        }
        Future<?> future = executor.submit(() -> execute(record, taskExecutor));
        running.put(record.spec().taskId(), future);
    }

    private void execute(TaskRecord scheduled, TaskExecutor taskExecutor) {
        TaskRecord runningRecord;
        synchronized (this) {
            TaskRecord current = store.load(scheduled.spec().taskId()).orElseThrow();
            if (current.status() != TaskStatus.READY) return;
            String childRunId = !current.childRunId().isBlank() ? current.childRunId()
                    : "child-" + UUID.nameUUIDFromBytes(current.spec().taskId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                runningRecord = store.save(current.transition(TaskStatus.RUNNING, childRunId, "worker started"),
                        current.version());
            } catch (IllegalStateException claimLost) {
                if (claimLost.getMessage() != null && claimLost.getMessage().startsWith("task version conflict:")) return;
                throw claimLost;
            }
        }
        TaskResult result;
        try {
            List<TaskResult> dependencies = runningRecord.spec().dependsOn().stream()
                    .map(store::loadResult).flatMap(java.util.Optional::stream)
                    .sorted(Comparator.comparingInt(TaskResult::planOrder)).toList();
            result = taskExecutor.execute(new TaskExecutionContext(runningRecord, dependencies, workspace));
            validateResult(runningRecord, result);
        } catch (Throwable failure) {
            result = Thread.currentThread().isInterrupted()
                    ? TaskResult.cancelled(runningRecord, "task interrupted") : TaskResult.failed(runningRecord, failure);
        }
        synchronized (this) {
            running.remove(runningRecord.spec().taskId());
            TaskRecord current = store.load(runningRecord.spec().taskId()).orElseThrow();
            if (closing && !current.status().terminal()) {
                if (current.status() == TaskStatus.RUNNING) {
                    store.save(current.transition(TaskStatus.RECOVERING, current.childRunId(),
                            "scheduler shutdown interrupted task"), current.version());
                }
                return;
            }
            if (!current.status().terminal()) settle(current, result);
            if (result.status() == TaskStatus.FAILED
                    && runningRecord.spec().failurePolicy() == TaskFailurePolicy.FAIL_FAST) {
                cancelNotStartedSiblings(runningRecord.spec().parentRunId(), "critical task failed: " + runningRecord.spec().taskId());
            }
            scheduleReady(runningRecord.spec().parentRunId());
            if (store.listByParent(runningRecord.spec().parentRunId()).stream().allMatch(task -> task.status().terminal())) {
                ACTIVE_PARENTS.remove(runningRecord.spec().parentRunId(), this);
            }
        }
    }

    private void settle(TaskRecord current, TaskResult result) {
        if (store instanceof TransactionalTaskStore transactional) {
            transactional.settleAndDeliver(current, result);
            return;
        }
        TaskResult durable = store.saveResult(result);
        TaskStatus status = durable.status();
        store.save(current.transition(status, durable.childRunId(), durable.error().isBlank() ? durable.summary() : durable.error()),
                current.version());
        waker.deliver(durable);
    }

    private void cancelNotStartedSiblings(String parentRunId, String reason) {
        for (TaskRecord sibling : store.listByParent(parentRunId)) {
            if (sibling.status() == TaskStatus.PLANNED || sibling.status() == TaskStatus.READY) {
                settle(sibling, TaskResult.cancelled(sibling, reason));
            }
        }
    }

    private static void validateResult(TaskRecord record, TaskResult result) {
        if (result == null) throw new IllegalStateException("task executor returned null");
        if (!record.spec().taskId().equals(result.taskId())
                || !record.spec().parentRunId().equals(result.parentRunId())
                || record.spec().planOrder() != result.planOrder()) {
            throw new IllegalStateException("task executor returned mismatched result identity");
        }
    }

    @Override
    public void close() {
        closing = true;
        ACTIVE_PARENTS.entrySet().removeIf(entry -> entry.getValue() == this);
        running.values().forEach(future -> future.cancel(true));
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class SchedulerThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ricbot-task-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
