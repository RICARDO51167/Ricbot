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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Default local implementation of the hard-cut AgentRuntime API. */
public final class LocalAgentRuntime implements AgentRuntime {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    @FunctionalInterface
    public interface GraphFactory {
        AgentGraphRuntime open(RunRequest request, GraphExecutionState checkpoint);
    }

    private final SqliteRuntimeStore store;
    private final RuntimeDriver driver;
    private final GraphFactory graphs;
    private final CopyOnWriteArrayList<RuntimeEventTailer> tailers = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Thread> activeRunThreads =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public LocalAgentRuntime(SqliteRuntimeStore store, RuntimeDriver driver, GraphFactory graphs) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.driver = driver != null ? driver : new RuntimeDriver();
        this.graphs = java.util.Objects.requireNonNull(graphs, "graphs");
    }

    @Override public RunView start(RunRequest request) {
        if (store.loadCheckpoint(request.runId()).isPresent()) throw new IllegalArgumentException("run already exists");
        enterRun(request.runId());
        try (AgentGraphRuntime graph = graphs.open(request, null)) { driver.drive(graph); }
        finally { leaveRun(request.runId()); }
        wakeTailers();
        return view(request.runId());
    }

    @Override public RunView resume(String runId) {
        GraphExecutionState state = require(runId);
        enterRun(runId);
        try (AgentGraphRuntime graph = graphs.open(requestFor(state), state)) {
            List<Map<String, Object>> pending = store.pendingSignals(runId);
            List<ricbot.domain.task.TaskDelivery> deliveries = store.pending(runId);
            if ((state.status() == GraphExecutionStatus.PAUSED || state.status() == GraphExecutionStatus.WAITING
                    || state.status() == GraphExecutionStatus.RECOVERING)
                    && (!pending.isEmpty() || !deliveries.isEmpty())) {
                Map<String, Object> merged = new java.util.LinkedHashMap<>();
                for (Map<String, Object> signal : pending) {
                    String type = String.valueOf(signal.getOrDefault("signalType", ""));
                    if ("APPROVED".equals(type) || "REJECTED".equals(type)) {
                        merged.put("approvalSignal", type);
                    }
                    Object payload = signal.get("payload");
                    if (payload instanceof Map<?, ?> values && values.get("channels") instanceof Map<?, ?> channels) {
                        channels.forEach((key, value) -> merged.put(String.valueOf(key), value));
                    }
                }
                if (!deliveries.isEmpty()) merged.put("workerResults",
                        deliveries.stream().map(ricbot.domain.task.TaskDelivery::result).toList());
                graph.resume(merged, deliveries.stream().map(ricbot.domain.task.TaskDelivery::deliveryId).toList(),
                        pending.stream().map(signal -> String.valueOf(signal.get("signalId"))).toList());
            } else if (state.status() != GraphExecutionStatus.READY
                    && state.status() != GraphExecutionStatus.RETRY_WAIT) {
                throw new IllegalStateException("run requires a signal before it can resume: " + state.status());
            }
            driver.drive(graph);
        } finally { leaveRun(runId); }
        wakeTailers();
        return view(runId);
    }

    /** Startup dispatcher scan for due retries, unconsumed signals and Delivery outbox rows. */
    public void recoverPending() {
        for (GraphExecutionState state : store.listCheckpoints()) {
            if (state.status().terminal()) continue;
            boolean recoverable = state.status() == GraphExecutionStatus.READY
                    || state.status() == GraphExecutionStatus.RETRY_WAIT
                    || !store.pendingSignals(state.runId()).isEmpty()
                    || !store.pending(state.runId()).isEmpty();
            if (!recoverable) continue;
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { resume(state.runId()); }
                catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(LocalAgentRuntime.class)
                            .warn("runtime startup recovery deferred for {}: {}", state.runId(), failure.getMessage());
                }
            });
        }
    }

    @Override public RunView signal(String runId, RuntimeSignal signal) {
        require(runId);
        store.appendSignal(runId, signal.signalId(), signal.type(), signal.payload());
        return resume(runId);
    }

    @Override public RunView cancel(String runId, String reason) {
        String detail = reason != null ? reason : "";
        for (int attempt = 0; attempt < 3; attempt++) {
            GraphExecutionState state = require(runId);
            if (state.status().terminal()) break;
            GraphExecutionState cancelled = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION,
                    state.graphId(), state.runId(), state.superstep(), List.of(), state.channels(), List.of(),
                    state.failures(), GraphExecutionStatus.CANCELLED, state.lastNodeId(), state.transition() + 1,
                    Instant.now());
            try {
                store.commit(cancelled, GraphRuntimeEventType.RUN_CANCELLED, Map.of("reason", detail),
                        "cancel:" + cancelled.transition());
                break;
            } catch (IllegalStateException raced) {
                if (raced.getMessage() == null || !raced.getMessage().startsWith("run transition conflict:")) {
                    throw raced;
                }
                if (attempt == 2) throw raced;
            }
        }
        ricbot.domain.task.LocalTaskScheduler.cancelActiveParent(runId, detail);
        Thread active = activeRunThreads.get(runId);
        if (active != null && active != Thread.currentThread()) active.interrupt();
        wakeTailers();
        return view(runId);
    }

    @Override public ReplayView replay(String runId, long sequence) { return store.replay(runId, sequence); }
    @Override public ReplayView fork(String runId, long sequence, String newRunId) {
        ReplayView source = store.replay(runId, sequence);
        ReplayView fork = store.fork(runId, sequence, newRunId);
        var worktree = new ricbot.domain.task.TaskWorktreeManager(workspace()).integration(newRunId);
        List<RuntimeEventEnvelope> safeEvents = source.events().stream()
                .filter(event -> event.globalSequence() <= source.committedSequence()).toList();
        ricbot.domain.runtime.RuntimeAggregate aggregate = new ricbot.domain.runtime.RuntimeReducer()
                .reduce(runId, safeEvents);
        List<ricbot.domain.task.TaskResult> patchResults = aggregate.taskResults().values().stream().map(node -> {
            try { return MAPPER.treeToValue(node, ricbot.domain.task.TaskResult.class); }
            catch (Exception failure) { throw new IllegalStateException("cannot rebuild fork task result", failure); }
        }).filter(result -> result.patch() != null && !result.patch().isBlank())
                .sorted(java.util.Comparator.comparingInt(ricbot.domain.task.TaskResult::planOrder)
                        .thenComparing(ricbot.domain.task.TaskResult::taskId)).toList();
        List<ricbot.domain.task.PatchApplyResult> patchOutcomes = new ricbot.domain.task.PatchLedgerService(
                workspace(), store).applyOrdered(newRunId, worktree.path(), patchResults);
        GraphExecutionState state = fork.state();
        Map<String, Object> channels = new java.util.LinkedHashMap<>(state.channels());
        channels.put("integrationWorkspace", worktree.path().toString());
        channels.put("forkPatchOutcomes", patchOutcomes);
        GraphExecutionState ready = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, state.graphId(),
                state.runId(), state.superstep(), state.activeNodes(), channels, List.of(), state.failures(),
                GraphExecutionStatus.READY, state.lastNodeId(), state.transition() + 1, Instant.now());
        store.commit(ready, GraphRuntimeEventType.EXTERNAL_SIGNAL_RECEIVED,
                Map.of("forkWorktree", worktree.path().toString(), "requiresExplicitResume", true), "fork-worktree");
        return store.replay(newRunId, Long.MAX_VALUE);
    }
    @Override public AutoCloseable subscribe(RuntimeEventSubscriber subscriber) {
        RuntimeEventSubscriber required = java.util.Objects.requireNonNull(subscriber, "subscriber");
        RuntimeEventTailer tailer = new RuntimeEventTailer(store, required.getClass().getName(), required,
                Duration.ofMillis(100));
        tailers.add(tailer);
        return () -> {
            tailers.remove(tailer);
            tailer.close();
        };
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        tailers.forEach(RuntimeEventTailer::close);
        tailers.clear();
        driver.close();
        if (graphs instanceof AutoCloseable closeable) {
            try { closeable.close(); }
            catch (Exception failure) { throw new IllegalStateException("cannot close runtime graph factory", failure); }
        }
    }

    private RunView view(String runId) {
        GraphExecutionState state = require(runId);
        List<TaskView> tasks = store.listByParent(runId).stream()
                .map(task -> new TaskView(task, store.loadResultHistory(task.spec().taskId()),
                        store.loadResult(task.spec().taskId()).orElse(null))).toList();
        List<RuntimeEventEnvelope> events = store.runtimeEvents(runId, Long.MAX_VALUE);
        long last = events.isEmpty() ? 0 : events.get(events.size() - 1).globalSequence();
        return new RunView(state, tasks, last, RuntimeDigest.sha256(state));
    }

    private void wakeTailers() { tailers.forEach(RuntimeEventTailer::wake); }
    private void enterRun(String runId) {
        Thread current = Thread.currentThread();
        Thread existing = activeRunThreads.putIfAbsent(runId, current);
        if (existing != null && existing != current) {
            throw new IllegalStateException("run is already active in this process: " + runId);
        }
    }
    private void leaveRun(String runId) { activeRunThreads.remove(runId, Thread.currentThread()); }
    private long lastSequence(String runId) {
        List<RuntimeEventEnvelope> events = store.runtimeEvents(runId, Long.MAX_VALUE);
        return events.isEmpty() ? 0 : events.get(events.size() - 1).globalSequence();
    }
    private GraphExecutionState require(String runId) {
        return store.loadCheckpoint(runId).orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
    }
    private RunRequest requestFor(GraphExecutionState state) {
        RunRequest.Mode mode = ricbot.domain.agent.graph.DefaultTeamGraph.GRAPH_ID.equals(state.graphId())
                ? RunRequest.Mode.TEAM : RunRequest.Mode.AGENT;
        return new RunRequest(state.runId(), "", mode,
                String.valueOf(state.channels().getOrDefault("goal", "resume run")),
                workspace(), 128, Map.of());
    }
    private java.nio.file.Path workspace() { return store.database().getParent().getParent(); }
}
