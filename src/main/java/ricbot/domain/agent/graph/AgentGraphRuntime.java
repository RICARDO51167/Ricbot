package ricbot.domain.agent.graph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Deterministic bulk-synchronous graph runtime with durable node writes. */
public final class AgentGraphRuntime implements AutoCloseable {
    private final AgentGraphDefinition definition;
    private final GraphNodeRegistry nodes;
    private final GraphConditionRegistry conditions;
    private final GraphStateSchema schema;
    private final GraphRuntimeStore store;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private GraphExecutionState state;

    public AgentGraphRuntime(AgentGraphDefinition definition, GraphNodeRegistry nodes,
                             GraphConditionRegistry conditions, GraphStateSchema schema,
                             GraphRuntimeStore store, ExecutorService executor,
                             GraphExecutionState initialState) {
        this(definition, nodes, conditions, schema, store, executor, false, initialState);
    }

    private AgentGraphRuntime(AgentGraphDefinition definition, GraphNodeRegistry nodes,
                              GraphConditionRegistry conditions, GraphStateSchema schema,
                              GraphRuntimeStore store, ExecutorService executor, boolean ownsExecutor,
                              GraphExecutionState initialState) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.conditions = conditions != null ? conditions : new GraphConditionRegistry();
        this.schema = Objects.requireNonNull(schema, "schema");
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
        definition.validateExecutors(nodes);
        GraphExecutionState seed = initialState != null ? validate(initialState)
                : GraphExecutionState.initial(definition.graphId(), definition.entryNode(), Map.of());
        this.state = store.loadCheckpoint(seed.runId()).map(this::validate).orElse(seed);
        if (store.loadCheckpoint(seed.runId()).isEmpty()) {
            commit(GraphRuntimeEventType.RUN_STARTED, Map.of("graphId", definition.graphId()), "run-started");
        }
    }

    /** Executes exactly one superstep. */
    public synchronized GraphExecutionState executeOne(Map<String, Object> input) {
        if (state.status().terminal()) return state;
        if (state.status() == GraphExecutionStatus.PAUSED || state.status() == GraphExecutionStatus.WAITING) {
            throw new IllegalStateException("paused graph must be resumed explicitly");
        }
        if (state.superstep() >= definition.maxSupersteps()) return failGraph("maximum supersteps exceeded", null);

        Map<String, Object> invocationInput = input != null ? Map.copyOf(input) : Map.of();
        List<NodeActivation> activations = state.activeNodes().stream().sorted().toList();
        if (activations.isEmpty()) return complete(state.lastNodeId(), state.channels(), state.failures());
        if (activations.stream().allMatch(activation -> definition.terminal(activation.nodeId()))) {
            return complete(activations.get(activations.size() - 1).nodeId(), state.channels(), state.failures());
        }

        event(GraphRuntimeEventType.SUPERSTEP_STARTED,
                Map.of("activeNodes", activations.stream().map(NodeActivation::activationId).toList()),
                "superstep-start:" + state.superstep());
        GraphExecutionState snapshot = state;
        Map<String, GraphPendingWrite> completed = new LinkedHashMap<>();
        store.pending(state.runId(), state.superstep()).forEach(write ->
                completed.put(write.activation().activationId(), write));

        Map<NodeActivation, Future<GraphPendingWrite>> futures = new LinkedHashMap<>();
        for (NodeActivation activation : activations) {
            if (definition.terminal(activation.nodeId()) || completed.containsKey(activation.activationId())) continue;
            futures.put(activation, executor.submit(() -> invoke(snapshot, activation, invocationInput)));
        }
        List<GraphRetrySchedule> retries = new ArrayList<>();
        for (Map.Entry<NodeActivation, Future<GraphPendingWrite>> entry : futures.entrySet()) {
            NodeActivation activation = entry.getKey();
            GraphNodeSpec spec = definition.nodeSpec(activation.nodeId());
            GraphPendingWrite write;
            try {
                write = entry.getValue().get(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                entry.getValue().cancel(true);
                GraphRetrySchedule retry = retrySchedule(activation, spec, "timeout",
                        "node timed out after " + spec.timeout());
                if (retry != null) {
                    retries.add(retry);
                    continue;
                }
                write = failedWrite(activation, "timeout", "node timed out after " + spec.timeout(), e);
            } catch (InterruptedException e) {
                entry.getValue().cancel(true);
                Thread.currentThread().interrupt();
                write = failedWrite(activation, "cancelled", "node execution interrupted", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RetryableNodeFailure retryable) {
                    retries.add(retryable.schedule());
                    continue;
                }
                if (e.getCause() instanceof GraphFatalFailure && e.getCause() instanceof RuntimeException fatal) {
                    throw fatal;
                }
                write = failedWrite(activation, "execution", message(e.getCause()), e.getCause());
            }
            store.savePending(write);
            event(GraphRuntimeEventType.NODE_WRITE_SAVED,
                    Map.of("activationId", activation.activationId(), "nodeId", activation.nodeId()),
                    "write:" + state.superstep() + ":" + activation.activationId());
            completed.put(activation.activationId(), write);
        }

        if (!retries.isEmpty()) {
            Instant commonDue = retries.stream().map(GraphRetrySchedule::availableAt).max(Instant::compareTo)
                    .orElseThrow();
            Map<String, GraphRetrySchedule> byActivation = new LinkedHashMap<>();
            retries.forEach(retry -> byActivation.put(retry.activation().activationId(),
                    new GraphRetrySchedule(retry.activation(), commonDue, retry.failureKind(), retry.message())));
            List<NodeActivation> retrying = activations.stream().map(activation -> {
                GraphRetrySchedule retry = byActivation.get(activation.activationId());
                return retry != null ? retry.activation().nextAttempt() : activation;
            }).sorted().toList();
            state = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, snapshot.graphId(), snapshot.runId(),
                    snapshot.superstep(), retrying, snapshot.channels(), List.of(), snapshot.failures(),
                    GraphExecutionStatus.RETRY_WAIT, snapshot.lastNodeId(), snapshot.transition() + 1, Instant.now());
            store.scheduleRetries(state, List.copyOf(byActivation.values()));
            return state;
        }

        List<GraphPendingWrite> writes = activations.stream().filter(a -> !definition.terminal(a.nodeId()))
                .map(a -> completed.get(a.activationId())).filter(Objects::nonNull)
                .sorted(Comparator.comparing(GraphPendingWrite::activation)).toList();
        try {
            return commitSuperstep(snapshot, writes, activations);
        } catch (RuntimeException failure) {
            if (state.status() == GraphExecutionStatus.FAILED) throw failure;
            return failGraph("superstep commit failed: " + message(failure), failure);
        }
    }

    public synchronized GraphExecutionState resume(Map<String, Object> signals) {
        return resume(signals, List.of());
    }

    public synchronized GraphExecutionState resume(Map<String, Object> signals,
                                                    List<String> acknowledgedDeliveryIds) {
        return resume(signals, acknowledgedDeliveryIds, List.of());
    }

    public synchronized GraphExecutionState resume(Map<String, Object> signals,
                                                    List<String> acknowledgedDeliveryIds,
                                                    List<String> consumedSignalIds) {
        if (state.status() != GraphExecutionStatus.PAUSED && state.status() != GraphExecutionStatus.WAITING
                && state.status() != GraphExecutionStatus.RECOVERING) {
            throw new IllegalStateException("graph is not paused");
        }
        Map<String, Object> merged = new LinkedHashMap<>(state.channels());
        if (signals != null) {
            List<GraphChannelWrite> writes = new ArrayList<>();
            signals.forEach((channel, value) -> writes.add(new GraphChannelWrite(channel, value, Long.MAX_VALUE,
                    "external-signal", "external-signal:" + state.transition())));
            mergeChannels(merged, writes);
        }
        state = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, state.graphId(), state.runId(),
                state.superstep(), state.activeNodes(), merged, List.of(), state.failures(), GraphExecutionStatus.READY,
                state.lastNodeId(), state.transition() + 1, Instant.now());
        store.commit(state, GraphRuntimeEventType.EXTERNAL_SIGNAL_RECEIVED,
                Map.of("channels", signals != null ? signals.keySet() : Set.of()),
                "external-signal:" + state.transition(),
                acknowledgedDeliveryIds != null ? acknowledgedDeliveryIds : List.of(),
                consumedSignalIds != null ? consumedSignalIds : List.of());
        return state;
    }

    public synchronized GraphExecutionState cancel(String reason) {
        if (state.status().terminal()) return state;
        state = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, state.graphId(), state.runId(),
                state.superstep(), List.of(), state.channels(), List.of(), state.failures(),
                GraphExecutionStatus.CANCELLED, state.lastNodeId(), state.transition() + 1, Instant.now());
        commit(GraphRuntimeEventType.RUN_CANCELLED, Map.of("reason", reason != null ? reason : ""), "cancel");
        return state;
    }

    public GraphExecutionState state() { return state; }

    /** Makes a persisted retry runnable only after its durable due time. */
    public synchronized GraphExecutionState activateDueRetries(Instant now) {
        state = store.activateDueRetries(state.runId(), now != null ? now : Instant.now());
        return state;
    }

    public java.util.Optional<Instant> nextRetryAt() { return store.nextRetryAt(state.runId()); }

    private GraphPendingWrite invoke(GraphExecutionState snapshot, NodeActivation original,
                                     Map<String, Object> invocationInput) {
        GraphNodeSpec spec = definition.nodeSpec(original.nodeId());
        try {
            Map<String, Object> input = new LinkedHashMap<>(invocationInput);
            input.putAll(original.input());
            GraphNodeResult result = nodes.require(spec.executorId()).execute(snapshot, Map.copyOf(input));
            if (result == null) throw new IllegalStateException("node returned null result");
            return new GraphPendingWrite(snapshot.runId(), snapshot.superstep(), original,
                    normalizeWait(result, original), null, Instant.now());
        } catch (Throwable failure) {
            if (failure instanceof GraphFatalFailure && failure instanceof RuntimeException fatal) throw fatal;
            if (failure instanceof GraphNonRetryableException) {
                return failedWrite(original, "non_retryable", message(failure), failure);
            }
            GraphRetrySchedule retry = retrySchedule(original, spec, "execution", message(failure));
            if (retry != null) throw new RetryableNodeFailure(retry, failure);
            return failedWrite(original, "execution", message(failure), failure);
        }
    }

    private static GraphRetrySchedule retrySchedule(NodeActivation activation, GraphNodeSpec spec,
                                                    String kind, String message) {
        if (activation.attempt() >= spec.retryPolicy().maxAttempts()
                || !spec.retryPolicy().sideEffectSafe()) return null;
        java.time.Duration backoff = spec.retryPolicy().backoffForAttempt(activation.attempt());
        return new GraphRetrySchedule(activation, Instant.now().plus(backoff), kind, message);
    }

    private GraphExecutionState commitSuperstep(GraphExecutionState snapshot, List<GraphPendingWrite> pending,
                                                List<NodeActivation> activations) {
        List<GraphFailure> failures = new ArrayList<>(snapshot.failures());
        boolean failStop = false;
        for (GraphPendingWrite write : pending) {
            if (write.failure() == null) continue;
            GraphNodeSpec spec = definition.nodeSpec(write.activation().nodeId());
            boolean tolerated = spec.failurePolicy() == GraphFailurePolicy.COLLECT;
            GraphFailure failure = new GraphFailure(write.failure().activationId(), write.failure().nodeId(),
                    write.failure().kind(), write.failure().message(), write.failure().attempt(), tolerated,
                    write.failure().occurredAt());
            failures.add(failure);
            failStop |= !tolerated;
        }
        if (failStop) {
            state = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, snapshot.graphId(), snapshot.runId(),
                    snapshot.superstep(), snapshot.activeNodes(), snapshot.channels(), List.of(), failures,
                    GraphExecutionStatus.FAILED, snapshot.lastNodeId(), snapshot.transition() + 1, Instant.now());
            commit(GraphRuntimeEventType.RUN_FAILED, Map.of("failures", failures.size()),
                    "failed:" + snapshot.superstep());
            throw new IllegalStateException("graph superstep failed: " + failures.get(failures.size() - 1).message());
        }

        Map<String, Object> channels = new LinkedHashMap<>(snapshot.channels());
        List<GraphChannelWrite> channelWrites = pending.stream().flatMap(write -> write.channelWrites().stream())
                .sorted().toList();
        try { mergeChannels(channels, channelWrites); }
        catch (RuntimeException e) { return failGraph("state reduction failed: " + e.getMessage(), e); }

        List<GraphWait> waits = pending.stream().filter(write -> write.result() != null && write.result().waitCondition() != null)
                .map(write -> write.result().waitCondition()).toList();
        List<NodeActivation> next = new ArrayList<>();
        String lastNode = snapshot.lastNodeId();
        int ordinal = 0;
        for (GraphPendingWrite write : pending) {
            if (write.result() == null || write.result().waitCondition() != null) continue;
            lastNode = write.activation().nodeId();
            List<String> staticTargets = selectTargets(write.activation(), write.result(), channels);
            for (String target : staticTargets) {
                lastNode = target;
                if (!definition.terminal(target)) next.add(activationFor(snapshot, write.activation(), target, ordinal++, Map.of(), "edge"));
            }
            for (GraphSend send : write.result().sends()) {
                if (!definition.nodes().contains(send.nodeId())) throw new IllegalStateException("dynamic send targets unknown node: " + send.nodeId());
                lastNode = send.nodeId();
                if (!definition.terminal(send.nodeId())) {
                    next.add(activationFor(snapshot, write.activation(), send.nodeId(), ordinal++, send.input(), send.key()));
                }
            }
        }
        ensureUniqueActivations(next);
        GraphExecutionStatus status = !waits.isEmpty() ? GraphExecutionStatus.PAUSED
                : next.isEmpty() ? GraphExecutionStatus.COMPLETED : GraphExecutionStatus.READY;
        List<NodeActivation> active = !waits.isEmpty()
                ? pending.stream().filter(write -> write.result() != null && write.result().waitCondition() != null)
                    .map(GraphPendingWrite::activation).sorted().toList()
                : next.stream().sorted().toList();
        state = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, snapshot.graphId(), snapshot.runId(),
                snapshot.superstep() + 1, active, channels, waits, failures, status, lastNode,
                snapshot.transition() + 1, Instant.now());
        commit(GraphRuntimeEventType.SUPERSTEP_COMMITTED,
                Map.of("nextNodes", active.stream().map(NodeActivation::activationId).toList()),
                "commit:" + snapshot.superstep());
        if (status == GraphExecutionStatus.PAUSED) event(GraphRuntimeEventType.RUN_PAUSED,
                Map.of("waitIds", waits.stream().map(GraphWait::waitId).toList()), "pause:" + snapshot.superstep());
        if (status == GraphExecutionStatus.COMPLETED) event(GraphRuntimeEventType.RUN_COMPLETED,
                Map.of("lastNode", lastNode), "complete");
        return state;
    }

    private List<String> selectTargets(NodeActivation activation, GraphNodeResult result,
                                       Map<String, Object> channels) {
        Map<String, Object> facts = new LinkedHashMap<>(channels);
        facts.putAll(activation.input());
        List<GraphEdge> matches = definition.outgoing(activation.nodeId()).stream()
                .filter(edge -> edge.outcome().equals(result.outcome()) || "*".equals(edge.outcome()))
                .filter(edge -> conditions.require(edge.conditionId()).matches(state, facts)).toList();
        if (matches.isEmpty()) {
            if (!result.sends().isEmpty()) return List.of();
            throw new IllegalStateException("no graph edge from " + activation.nodeId() + " for outcome " + result.outcome());
        }
        if (matches.get(0).mode() == GraphEdgeMode.FIRST_MATCH) return List.of(matches.get(0).to());
        return matches.stream().filter(edge -> edge.mode() == GraphEdgeMode.FAN_OUT).map(GraphEdge::to).toList();
    }

    private void mergeChannels(Map<String, Object> channels, Collection<GraphChannelWrite> writes) {
        Map<String, List<GraphChannelWrite>> grouped = new LinkedHashMap<>();
        writes.stream().sorted().forEach(write -> grouped.computeIfAbsent(write.channel(), ignored -> new ArrayList<>()).add(write));
        grouped.forEach((channel, channelGroup) -> channels.put(channel,
                schema.reduce(channel, channels.get(channel), channelGroup)));
    }

    private NodeActivation activationFor(GraphExecutionState snapshot, NodeActivation parent, String target,
                                         int ordinal, Map<String, Object> input, String key) {
        String suffix = key != null && !key.isBlank() ? key : Integer.toString(ordinal);
        String id = parent.activationId() + "/" + target + ":" + suffix;
        long order = Math.multiplyExact(snapshot.superstep() + 1, 1_000_000L) + ordinal;
        return new NodeActivation(id, target, order, 1, input);
    }

    private GraphNodeResult normalizeWait(GraphNodeResult result, NodeActivation activation) {
        if (result.waitCondition() == null) return result;
        GraphWait wait = result.waitCondition();
        return new GraphNodeResult(result.outcome(), result.updates(), result.sends(), wait);
    }

    private GraphPendingWrite failedWrite(NodeActivation activation, String kind, String detail, Throwable ignored) {
        GraphFailure failure = new GraphFailure(activation.activationId(), activation.nodeId(), kind,
                detail != null ? detail : "unknown failure", activation.attempt(), false, Instant.now());
        return new GraphPendingWrite(state.runId(), state.superstep(), activation, null, failure, Instant.now());
    }

    private GraphExecutionState complete(String lastNode, Map<String, Object> channels, List<GraphFailure> failures) {
        state = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, state.graphId(), state.runId(),
                state.superstep(), List.of(), channels, List.of(), failures, GraphExecutionStatus.COMPLETED,
                lastNode, state.transition() + 1, Instant.now());
        commit(GraphRuntimeEventType.RUN_COMPLETED, Map.of("lastNode", lastNode), "complete");
        return state;
    }

    private GraphExecutionState failGraph(String detail, Throwable failure) {
        NodeActivation activation = state.activeNodes().isEmpty()
                ? new NodeActivation(state.runId() + ":runtime", "runtime", 0, 1, Map.of()) : state.activeNodes().get(0);
        List<GraphFailure> failures = new ArrayList<>(state.failures());
        failures.add(new GraphFailure(activation.activationId(), activation.nodeId(), "runtime", detail, 1, false, Instant.now()));
        state = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, state.graphId(), state.runId(),
                state.superstep(), state.activeNodes(), state.channels(), List.of(), failures,
                GraphExecutionStatus.FAILED, state.lastNodeId(), state.transition() + 1, Instant.now());
        commit(GraphRuntimeEventType.RUN_FAILED, Map.of("message", detail), "failed:" + state.superstep());
        if (failure != null) throw new IllegalStateException(detail, failure);
        throw new IllegalStateException(detail);
    }

    private GraphExecutionState validate(GraphExecutionState candidate) {
        if (!definition.graphId().equals(candidate.graphId())) throw new IllegalArgumentException("graph id mismatch");
        for (NodeActivation activation : candidate.activeNodes()) {
            if (!definition.nodes().contains(activation.nodeId())) throw new IllegalArgumentException("unknown graph node");
        }
        return candidate;
    }

    private void event(GraphRuntimeEventType type, Map<String, Object> data, String dedupe) {
        store.append(state.runId(), state.superstep(), type, data, dedupe);
    }
    private void commit(GraphRuntimeEventType type, Map<String, Object> data, String dedupe) {
        store.commit(state, type, data, dedupe);
    }
    private static void ensureUniqueActivations(List<NodeActivation> activations) {
        Set<String> ids = new LinkedHashSet<>();
        for (NodeActivation activation : activations) {
            if (!ids.add(activation.activationId())) throw new IllegalStateException("duplicate activation id: " + activation.activationId());
        }
    }
    private static String message(Throwable failure) {
        if (failure == null) return "unknown failure";
        return failure.getMessage() != null && !failure.getMessage().isBlank()
                ? failure.getMessage() : failure.getClass().getSimpleName();
    }
    private static final class RetryableNodeFailure extends RuntimeException {
        private final GraphRetrySchedule schedule;
        private RetryableNodeFailure(GraphRetrySchedule schedule, Throwable cause) {
            super(schedule.message(), cause);
            this.schedule = schedule;
        }
        private GraphRetrySchedule schedule() { return schedule; }
    }

    @Override
    public void close() {
        if (ownsExecutor) executor.shutdownNow();
    }
}
