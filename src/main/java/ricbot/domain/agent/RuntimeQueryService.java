package ricbot.domain.agent;

import ricbot.domain.agent.graph.dto.GraphExecutionState;
import ricbot.domain.agent.graph.enump.GraphExecutionStatus;
import ricbot.domain.agent.graph.dto.GraphRuntimeEvent;
import ricbot.domain.runtime.dto.ReplayView;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TaskResult;
import ricbot.domain.task.TaskStatus;
import ricbot.domain.task.LocalTaskScheduler;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import ricbot.domain.agent.usage.UsageLedger;
import ricbot.domain.agent.graph.AgentGraphRuntimeFactory;

/** Read-only CLI facade over unified runtime materialized projections. */
public final class RuntimeQueryService {
    private final SqliteRuntimeStore runtime;

    public RuntimeQueryService(Path workspace) {
        runtime = ricbot.app.bootstrap.RuntimeStoreRegistry.shared(workspace.toAbsolutePath().normalize());
    }

    public List<GraphExecutionState> runs() { return runtime.listCheckpoints(); }
    public GraphExecutionState run(String runId) { return runtime.loadCheckpoint(runId).orElse(null); }
    public List<GraphRuntimeEvent> events(String runId) { return runtime.events(runId); }
    public List<TaskRecord> tasks(String runId) { return runtime.listByParent(runId); }
    public TaskRecord task(String taskId) { return runtime.load(taskId).orElse(null); }
    public TaskResult result(String taskId) { return runtime.loadResult(taskId).orElse(null); }
    public ReplayView replay(String runId, long sequence) { return runtime.replay(runId, sequence); }
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

    public Map<String, Object> report(String runId) {
        GraphExecutionState state = run(runId);
        if (state == null) throw new IllegalArgumentException("run not found: " + runId);
        List<TaskRecord> records = tasks(runId);
        Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("runId", runId);
        report.put("graphId", state.graphId());
        report.put("compatibility", AgentGraphRuntimeFactory.LEGACY_AGENT_GRAPH_ID.equals(state.graphId())
                ? "legacy_read_only" : "current");
        report.put("status", state.status().name());
        report.put("superstep", state.superstep());
        report.put("activeNodes", state.activeNodes());
        report.put("waits", state.waits());
        report.put("failures", state.failures());
        report.put("tasks", records.size());
        report.put("completedTasks", records.stream().filter(record -> record.status().terminal()).count());
        UsageLedger local = UsageLedger.from(state.channels().get("usageLedger"));
        UsageLedger aggregate = local;
        for (TaskRecord record : records) {
            if (record.childRunId() == null || record.childRunId().isBlank()) continue;
            GraphExecutionState child = run(record.childRunId());
            if (child != null) aggregate = aggregate.plus(UsageLedger.from(child.channels().get("usageLedger")));
        }
        report.put("usage", Map.of("local", local, "aggregate", aggregate));
        report.put("budget", state.channels().getOrDefault("budgetState", Map.of()));
        Object artifacts = state.channels().getOrDefault("artifactRefs", List.of());
        report.put("artifacts", artifacts);
        report.put("artifactCount", artifacts instanceof java.util.Collection<?> values ? values.size() : 0);
        report.put("artifactBytes", artifactBytes(artifacts));
        report.put("runtimeHints", state.channels().getOrDefault("runtimeHints", Map.of()));
        report.put("middleware", state.channels().getOrDefault("middlewareState", Map.of()));
        report.put("tools", state.channels().getOrDefault("toolExposure", Map.of()));
        List<GraphRuntimeEvent> runEvents = events(runId);
        Map<String, Long> eventCounts = runEvents.stream().collect(java.util.stream.Collectors.groupingBy(
                event -> event.type().name(), java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
        report.put("eventCounts", eventCounts);
        report.put("budgetReservations", Map.of(
                "reserved", eventCounts.getOrDefault("BUDGET_RESERVED", 0L),
                "settled", eventCounts.getOrDefault("BUDGET_SETTLED", 0L),
                "exhausted", eventCounts.getOrDefault("BUDGET_EXHAUSTED", 0L)));
        java.util.Set<String> relatedRuns = new java.util.LinkedHashSet<>();
        relatedRuns.add(runId);
        records.stream().map(TaskRecord::childRunId).filter(value -> value != null && !value.isBlank())
                .forEach(relatedRuns::add);
        var approvals = runtime.listApprovals().stream()
                .filter(value -> value.binding() != null && relatedRuns.contains(value.binding().runId())).toList();
        report.put("approvals", Map.of("count", approvals.size(), "byStatus", approvals.stream().collect(
                java.util.stream.Collectors.groupingBy(value -> value.status().name(),
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()))));
        var effects = runtime.listSideEffectRecords().stream().filter(value -> relatedRuns.contains(value.runId())).toList();
        report.put("sideEffects", Map.of("count", effects.size(), "byStatus", effects.stream().collect(
                java.util.stream.Collectors.groupingBy(value -> value.status().name(),
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()))));
        report.put("taskSummary", records.stream().collect(java.util.stream.Collectors.groupingBy(
                value -> value.status().name(), java.util.LinkedHashMap::new, java.util.stream.Collectors.counting())));
        report.put("verifier", state.channels().getOrDefault("verification", Map.of()));
        return Map.copyOf(report);
    }

    private static long artifactBytes(Object raw) {
        if (!(raw instanceof java.util.Collection<?> values)) return 0;
        long total = 0;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Object bytes = map.containsKey("byteSize") ? map.get("byteSize") : map.get("bytes");
            if (bytes instanceof Number number) {
                try { total = Math.addExact(total, Math.max(0, number.longValue())); }
                catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
            }
        }
        return total;
    }
}
