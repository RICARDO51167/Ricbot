package ricbot.domain.agent;

import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.agent.graph.GraphExecutionStatus;
import ricbot.domain.agent.graph.GraphRuntimeEvent;
import ricbot.domain.runtime.ReplayView;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TaskResult;
import ricbot.domain.task.TaskStatus;
import ricbot.domain.task.LocalTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only CLI facade over unified runtime materialized projections. */
public final class RuntimeQueryService {
    private final Path workspace;
    private final SqliteRuntimeStore runtime;

    public RuntimeQueryService(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        runtime = new SqliteRuntimeStore(this.workspace);
    }

    public List<GraphExecutionState> runs() { return runtime.listCheckpoints(); }
    public GraphExecutionState run(String runId) { return runtime.loadCheckpoint(runId).orElse(null); }
    public List<GraphRuntimeEvent> events(String runId) { return runtime.events(runId); }
    public List<TaskRecord> tasks(String runId) { return runtime.listByParent(runId); }
    public TaskRecord task(String taskId) { return runtime.load(taskId).orElse(null); }
    public TaskResult result(String taskId) { return runtime.loadResult(taskId).orElse(null); }
    public ReplayView replay(String runId, long sequence) { return runtime.replay(runId, sequence); }
    public ReplayView fork(String runId, long sequence, String newRunId) {
        ReplayView fork = runtime.fork(runId, sequence, newRunId);
        ricbot.domain.task.TaskWorkspaceLease worktree = new ricbot.domain.task.TaskWorktreeManager(workspace)
                .integration(newRunId);
        GraphExecutionState state = fork.state();
        Map<String, Object> channels = new java.util.LinkedHashMap<>(state.channels());
        channels.put("integrationWorkspace", worktree.path().toString());
        GraphExecutionState enriched = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION,
                state.graphId(), state.runId(), state.superstep(), state.activeNodes(), channels, List.of(),
                state.failures(), GraphExecutionStatus.READY, state.lastNodeId(), state.transition() + 1, Instant.now());
        runtime.commit(enriched, ricbot.domain.agent.graph.GraphRuntimeEventType.EXTERNAL_SIGNAL_RECEIVED,
                Map.of("forkWorktree", worktree.path().toString(), "requiresExplicitResume", true), "fork-worktree");
        return runtime.replay(newRunId, Long.MAX_VALUE);
    }

    public GraphExecutionState cancelRun(String runId, String reason) {
        LocalTaskScheduler.cancelActiveParent(runId, reason);
        GraphExecutionState current = runtime.loadCheckpoint(runId).orElseThrow(() ->
                new IllegalArgumentException("run not found: " + runId));
        if (current.status().terminal()) return current;
        GraphExecutionState cancelled = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION,
                current.graphId(), current.runId(), current.superstep(), List.of(), current.channels(), List.of(),
                current.failures(), GraphExecutionStatus.CANCELLED, current.lastNodeId(), current.transition() + 1,
                Instant.now());
        runtime.commit(cancelled, ricbot.domain.agent.graph.GraphRuntimeEventType.RUN_CANCELLED,
                Map.of("reason", reason != null ? reason : ""), "cancel");
        for (TaskRecord task : runtime.listByParent(runId)) cancelTask(task.spec().taskId(), reason);
        return cancelled;
    }

    public TaskRecord cancelTask(String taskId, String reason) {
        TaskRecord current = runtime.load(taskId).orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        if (current.status().terminal()) return current;
        TaskResult result = TaskResult.cancelled(current, reason != null ? reason : "cancelled from CLI");
        return runtime.settleAndDeliver(current, result);
    }

    public TaskRecord retryTask(String taskId) {
        TaskRecord current = runtime.load(taskId).orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        if (!current.status().terminal()) throw new IllegalStateException("only a terminal task can be retried");
        return runtime.save(current.retry(), current.version());
    }

    public boolean hasLegacyData() {
        Path ricbot = workspace.resolve(".ricbot");
        return Files.isDirectory(ricbot.resolve("run-journal")) || Files.isDirectory(ricbot.resolve("worker-runtime"));
    }

    public Map<String, Object> report(String runId) {
        GraphExecutionState state = run(runId);
        if (state == null) throw new IllegalArgumentException("run not found: " + runId);
        List<TaskRecord> records = tasks(runId);
        return Map.of("runId", runId, "graphId", state.graphId(), "status", state.status().name(),
                "superstep", state.superstep(), "activeNodes", state.activeNodes(), "waits", state.waits(),
                "failures", state.failures(), "tasks", records.size(), "completedTasks",
                records.stream().filter(record -> record.status().terminal()).count());
    }
}
