package ricbot.application.runtime;

import ricbot.domain.agent.graph.AgentGraphRuntime;
import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.agent.graph.GraphExecutionStatus;
import ricbot.domain.agent.graph.GraphRuntimeEventType;
import ricbot.domain.runtime.AgentRuntime;
import ricbot.domain.runtime.ReplayView;
import ricbot.domain.runtime.RunRequest;
import ricbot.domain.runtime.RunView;
import ricbot.domain.runtime.RuntimeDigest;
import ricbot.domain.runtime.RuntimeEventEnvelope;
import ricbot.domain.runtime.RuntimeEventSubscriber;
import ricbot.domain.runtime.RuntimeSignal;
import ricbot.domain.runtime.TaskView;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Default local implementation of the hard-cut AgentRuntime API. */
public final class LocalAgentRuntime implements AgentRuntime {
    @FunctionalInterface
    public interface GraphFactory {
        AgentGraphRuntime open(RunRequest request, GraphExecutionState checkpoint);
    }

    private final SqliteRuntimeStore store;
    private final RuntimeDriver driver;
    private final GraphFactory graphs;
    private final CopyOnWriteArrayList<RuntimeEventSubscriber> subscribers = new CopyOnWriteArrayList<>();

    public LocalAgentRuntime(SqliteRuntimeStore store, RuntimeDriver driver, GraphFactory graphs) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.driver = driver != null ? driver : new RuntimeDriver();
        this.graphs = java.util.Objects.requireNonNull(graphs, "graphs");
    }

    @Override public RunView start(RunRequest request) {
        if (store.loadCheckpoint(request.runId()).isPresent()) throw new IllegalArgumentException("run already exists");
        long before = lastSequence(request.runId());
        try (AgentGraphRuntime graph = graphs.open(request, null)) { driver.drive(graph); }
        publishSince(request.runId(), before);
        return view(request.runId());
    }

    @Override public RunView resume(String runId) {
        GraphExecutionState state = require(runId);
        long before = lastSequence(runId);
        try (AgentGraphRuntime graph = graphs.open(requestFor(state), state)) {
            List<Map<String, Object>> pending = store.pendingSignals(runId);
            if ((state.status() == GraphExecutionStatus.PAUSED || state.status() == GraphExecutionStatus.WAITING)
                    && !pending.isEmpty()) {
                Map<String, Object> merged = new java.util.LinkedHashMap<>();
                pending.forEach(merged::putAll);
                graph.resume(merged, List.of(), pending.stream().map(signal -> String.valueOf(signal.get("signalId"))).toList());
            } else if (state.status() != GraphExecutionStatus.READY) {
                throw new IllegalStateException("run requires a signal before it can resume: " + state.status());
            }
            driver.drive(graph);
        }
        publishSince(runId, before);
        return view(runId);
    }

    @Override public RunView signal(String runId, RuntimeSignal signal) {
        require(runId);
        store.appendSignal(runId, signal.signalId(), signal.type(), signal.payload());
        return resume(runId);
    }

    @Override public RunView cancel(String runId, String reason) {
        GraphExecutionState state = require(runId);
        if (!state.status().terminal()) {
            GraphExecutionState cancelled = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION,
                    state.graphId(), state.runId(), state.superstep(), List.of(), state.channels(), List.of(),
                    state.failures(), GraphExecutionStatus.CANCELLED, state.lastNodeId(), state.transition() + 1,
                    Instant.now());
            store.commit(cancelled, GraphRuntimeEventType.RUN_CANCELLED,
                    Map.of("reason", reason != null ? reason : ""), "cancel");
        }
        publishSince(runId, 0);
        return view(runId);
    }

    @Override public ReplayView replay(String runId, long sequence) { return store.replay(runId, sequence); }
    @Override public ReplayView fork(String runId, long sequence, String newRunId) {
        ReplayView fork = store.fork(runId, sequence, newRunId);
        var worktree = new ricbot.domain.task.TaskWorktreeManager(workspace()).integration(newRunId);
        GraphExecutionState state = fork.state();
        Map<String, Object> channels = new java.util.LinkedHashMap<>(state.channels());
        channels.put("integrationWorkspace", worktree.path().toString());
        GraphExecutionState ready = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, state.graphId(),
                state.runId(), state.superstep(), state.activeNodes(), channels, List.of(), state.failures(),
                GraphExecutionStatus.READY, state.lastNodeId(), state.transition() + 1, Instant.now());
        store.commit(ready, GraphRuntimeEventType.EXTERNAL_SIGNAL_RECEIVED,
                Map.of("forkWorktree", worktree.path().toString(), "requiresExplicitResume", true), "fork-worktree");
        return store.replay(newRunId, Long.MAX_VALUE);
    }
    @Override public AutoCloseable subscribe(RuntimeEventSubscriber subscriber) {
        subscribers.add(java.util.Objects.requireNonNull(subscriber, "subscriber"));
        return () -> subscribers.remove(subscriber);
    }

    private RunView view(String runId) {
        GraphExecutionState state = require(runId);
        List<TaskView> tasks = store.listByParent(runId).stream()
                .map(task -> new TaskView(task, store.loadResult(task.spec().taskId()).orElse(null))).toList();
        List<RuntimeEventEnvelope> events = store.runtimeEvents(runId, Long.MAX_VALUE);
        long last = events.isEmpty() ? 0 : events.get(events.size() - 1).globalSequence();
        return new RunView(state, tasks, last, RuntimeDigest.sha256(state));
    }

    private void publishSince(String runId, long sequence) {
        for (RuntimeEventEnvelope event : store.runtimeEvents(runId, Long.MAX_VALUE)) {
            if (event.globalSequence() <= sequence) continue;
            for (RuntimeEventSubscriber subscriber : subscribers) subscriber.onEvent(event);
        }
    }
    private long lastSequence(String runId) {
        List<RuntimeEventEnvelope> events = store.runtimeEvents(runId, Long.MAX_VALUE);
        return events.isEmpty() ? 0 : events.get(events.size() - 1).globalSequence();
    }
    private GraphExecutionState require(String runId) {
        return store.loadCheckpoint(runId).orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
    }
    private RunRequest requestFor(GraphExecutionState state) {
        return new RunRequest(state.runId(), "", RunRequest.Mode.AGENT,
                String.valueOf(state.channels().getOrDefault("goal", "resume run")),
                workspace(), 128, Map.of());
    }
    private java.nio.file.Path workspace() { return store.database().getParent().getParent(); }
}
