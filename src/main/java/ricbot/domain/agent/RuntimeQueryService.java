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
    private final ricbot.infra.runtime.ArchivedRuntimeCatalog archived;

    public RuntimeQueryService(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        runtime = ricbot.app.bootstrap.RuntimeStoreRegistry.shared(this.workspace);
        archived = new ricbot.infra.runtime.ArchivedRuntimeCatalog(this.workspace);
    }

    public List<GraphExecutionState> runs() { return runtime.listCheckpoints(); }
    public List<Object> runListing() {
        List<Object> result = new java.util.ArrayList<>();
        for (GraphExecutionState state : runs()) result.add(Map.of("source", "LIVE", "executionEligible", true,
                "runId", state.runId(), "state", state));
        result.addAll(archived.runs());
        return List.copyOf(result);
    }
    public GraphExecutionState run(String runId) { return runtime.loadCheckpoint(runId).orElse(null); }
    public List<GraphRuntimeEvent> events(String runId) { return runtime.events(runId); }
    public Object eventsAny(String runId) {
        return run(runId) != null ? events(runId) : archived.events(runId, Long.MAX_VALUE);
    }
    public List<TaskRecord> tasks(String runId) { return runtime.listByParent(runId); }
    public TaskRecord task(String taskId) { return runtime.load(taskId).orElse(null); }
    public TaskResult result(String taskId) { return runtime.loadResult(taskId).orElse(null); }
    public ReplayView replay(String runId, long sequence) { return runtime.replay(runId, sequence); }
    public Object replayAny(String runId, long sequence) {
        return run(runId) != null ? replay(runId, sequence) : archived.replay(runId, sequence);
    }
    public boolean archived(String runId) { return run(runId) == null && archived.status(runId) != null; }
    public void requireExecutionEligible(String runId) {
        if (archived(runId)) throw new IllegalStateException(
                "archived runs are query-only (executionEligible=false): " + runId);
    }
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
        if (LocalTaskScheduler.cancelActiveTask(current.spec().parentRunId(), taskId, reason)) {
            return runtime.load(taskId).orElseThrow();
        }
        TaskRecord requested = runtime.save(current.transition(TaskStatus.CANCEL_REQUESTED, current.childRunId(),
                reason != null ? reason : "cancelled from CLI"), current.version());
        TaskResult result = TaskResult.cancelled(requested, reason != null ? reason : "cancelled from CLI");
        return runtime.settleAndDeliver(requested, result);
    }

    public TaskRecord retryTask(String taskId) {
        TaskRecord current = runtime.load(taskId).orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        if (!current.status().terminal()) throw new IllegalStateException("only a terminal task can be retried");
        GraphExecutionState parent = runtime.loadCheckpoint(current.spec().parentRunId()).orElseThrow(() ->
                new IllegalArgumentException("parent run not found: " + current.spec().parentRunId()));
        if (parent.status().terminal()) {
            throw new IllegalStateException("terminal parent runs cannot retry tasks; use /run fork");
        }
        return runtime.save(current.retry(), current.version());
    }

    public boolean hasLegacyData() {
        Path ricbot = workspace.resolve(".ricbot");
        return Files.isDirectory(ricbot.resolve("run-journal")) || Files.isDirectory(ricbot.resolve("worker-runtime"));
    }

    public Map<String, Object> report(String runId) {
        GraphExecutionState state = run(runId);
        if (state == null) {
            Map<String, Object> archivedStatus = archived.status(runId);
            if (archivedStatus != null) return archivedStatus;
            throw new IllegalArgumentException("run not found: " + runId);
        }
        List<TaskRecord> records = tasks(runId);
        Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("runId", runId);
        report.put("source", "LIVE");
        report.put("executionEligible", true);
        report.put("graphId", state.graphId());
        report.put("status", state.status().name());
        report.put("superstep", state.superstep());
        report.put("activeNodes", state.activeNodes());
        report.put("waits", state.waits());
        report.put("failures", state.failures());
        report.put("tasks", records.size());
        report.put("completedTasks", records.stream().filter(record -> record.status().terminal()).count());
        return Map.copyOf(report);
    }
}
