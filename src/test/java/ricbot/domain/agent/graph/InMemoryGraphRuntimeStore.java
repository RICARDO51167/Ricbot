package ricbot.domain.agent.graph;

import ricbot.domain.agent.graph.dto.GraphExecutionState;
import ricbot.domain.agent.graph.dto.GraphPendingWrite;
import ricbot.domain.agent.graph.dto.GraphRetrySchedule;
import ricbot.domain.agent.graph.dto.GraphRuntimeEvent;
import ricbot.domain.agent.graph.enump.GraphExecutionStatus;
import ricbot.domain.agent.graph.enump.GraphRuntimeEventType;
import ricbot.domain.agent.graph.interfacep.GraphRuntimeStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test-only graph store. Production always uses SQLite. */
public final class InMemoryGraphRuntimeStore implements GraphRuntimeStore {
    private final Map<String, GraphExecutionState> checkpoints = new LinkedHashMap<>();
    private final Map<String, GraphPendingWrite> writes = new LinkedHashMap<>();
    private final Map<String, List<GraphRuntimeEvent>> journals = new LinkedHashMap<>();
    private final Map<String, GraphRuntimeEvent> deduplicated = new LinkedHashMap<>();
    private final Map<String, Instant> retryDue = new LinkedHashMap<>();

    @Override public synchronized Optional<GraphExecutionState> loadCheckpoint(String runId) {
        return Optional.ofNullable(checkpoints.get(runId));
    }
    @Override public synchronized void savePending(GraphPendingWrite write) {
        writes.putIfAbsent(key(write.runId(), write.superstep(), write.activation().activationId()), write);
    }
    @Override public synchronized List<GraphPendingWrite> pending(String runId, long superstep) {
        return writes.values().stream().filter(write -> write.runId().equals(runId) && write.superstep() == superstep).toList();
    }
    @Override public synchronized void scheduleRetries(GraphExecutionState state, List<GraphRetrySchedule> retries) {
        checkpoints.put(state.runId(), state);
        Instant due = retries.stream().map(GraphRetrySchedule::availableAt).max(Instant::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("retries are required"));
        retryDue.put(state.runId(), due);
        append(state.runId(), state.superstep(), GraphRuntimeEventType.NODE_RETRY_SCHEDULED,
                Map.of("attempts", retries.stream().map(retry -> retry.activation().attempt()).toList(),
                        "availableAt", due.toString()), "retry:" + state.transition());
    }
    @Override public synchronized Optional<Instant> nextRetryAt(String runId) {
        return Optional.ofNullable(retryDue.get(runId));
    }
    @Override public synchronized GraphExecutionState activateDueRetries(String runId, Instant now) {
        GraphExecutionState current = checkpoints.get(runId);
        if (current == null) throw new IllegalArgumentException("run not found: " + runId);
        Instant due = retryDue.get(runId);
        if (current.status() != GraphExecutionStatus.RETRY_WAIT || due == null || due.isAfter(now)) return current;
        GraphExecutionState ready = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION,
                current.graphId(), current.runId(), current.superstep(), current.activeNodes(), current.channels(),
                current.waits(), current.failures(), GraphExecutionStatus.READY, current.lastNodeId(),
                current.transition() + 1, now);
        checkpoints.put(runId, ready);
        retryDue.remove(runId);
        append(runId, ready.superstep(), GraphRuntimeEventType.NODE_RETRY_DUE,
                Map.of("availableAt", due.toString()), "retry-due:" + ready.transition());
        return ready;
    }
    @Override public synchronized void commitCheckpoint(GraphExecutionState state) { checkpoints.put(state.runId(), state); }
    @Override public synchronized GraphRuntimeEvent append(String runId, long superstep, GraphRuntimeEventType type,
                                                            Map<String, Object> data, String deduplicationId) {
        String dedupe = runId + "\u0000" + (deduplicationId != null ? deduplicationId : "");
        if (deduplicationId != null && deduplicated.containsKey(dedupe)) return deduplicated.get(dedupe);
        List<GraphRuntimeEvent> events = journals.computeIfAbsent(runId, ignored -> new ArrayList<>());
        GraphRuntimeEvent event = new GraphRuntimeEvent(runId + ":" + (events.size() + 1), runId,
                events.size() + 1L, superstep, type, data, Instant.now());
        events.add(event);
        if (deduplicationId != null) deduplicated.put(dedupe, event);
        return event;
    }
    @Override public synchronized List<GraphRuntimeEvent> events(String runId) {
        return List.copyOf(journals.getOrDefault(runId, List.of()));
    }
    private static String key(String runId, long step, String activationId) {
        return runId + "\u0000" + step + "\u0000" + activationId;
    }
}

