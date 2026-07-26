package ricbot.domain.agent.graph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryGraphRuntimeStore implements GraphRuntimeStore {
    private final Map<String, GraphExecutionState> checkpoints = new LinkedHashMap<>();
    private final Map<String, GraphPendingWrite> writes = new LinkedHashMap<>();
    private final Map<String, List<GraphRuntimeEvent>> journals = new LinkedHashMap<>();
    private final Map<String, GraphRuntimeEvent> deduplicated = new LinkedHashMap<>();

    @Override
    public synchronized Optional<GraphExecutionState> loadCheckpoint(String runId) {
        return Optional.ofNullable(checkpoints.get(runId));
    }
    @Override
    public synchronized void savePending(GraphPendingWrite write) {
        writes.putIfAbsent(key(write.runId(), write.superstep(), write.activation().activationId()), write);
    }
    @Override
    public synchronized List<GraphPendingWrite> pending(String runId, long superstep) {
        return writes.values().stream().filter(write -> write.runId().equals(runId) && write.superstep() == superstep)
                .toList();
    }
    @Override
    public synchronized void commitCheckpoint(GraphExecutionState state) { checkpoints.put(state.runId(), state); }
    @Override
    public synchronized GraphRuntimeEvent append(String runId, long superstep, GraphRuntimeEventType type,
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
    @Override
    public synchronized List<GraphRuntimeEvent> events(String runId) {
        return List.copyOf(journals.getOrDefault(runId, List.of()));
    }
    private static String key(String runId, long step, String activationId) {
        return runId + "\u0000" + step + "\u0000" + activationId;
    }
}
