package ricbot.domain.agent.graph.interfacep;

import ricbot.domain.agent.graph.dto.GraphExecutionState;
import ricbot.domain.agent.graph.dto.GraphPendingWrite;
import ricbot.domain.agent.graph.dto.GraphRetrySchedule;
import ricbot.domain.agent.graph.dto.GraphRuntimeEvent;
import ricbot.domain.agent.graph.enump.GraphRuntimeEventType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;

/** Crash-safe storage boundary for the unified graph runtime. */
public interface GraphRuntimeStore {
    Optional<GraphExecutionState> loadCheckpoint(String runId);
    void savePending(GraphPendingWrite write);
    List<GraphPendingWrite> pending(String runId, long superstep);
    /** Atomically persists retry attempt/due time, event, and checkpoint. */
    void scheduleRetries(GraphExecutionState state, List<GraphRetrySchedule> retries);
    Optional<Instant> nextRetryAt(String runId);
    GraphExecutionState activateDueRetries(String runId, Instant now);
    void commitCheckpoint(GraphExecutionState state);
    /** Atomically commits the checkpoint projection and its causal event when the backend supports transactions. */
    default GraphRuntimeEvent commit(GraphExecutionState state, GraphRuntimeEventType type,
                                     Map<String, Object> data, String deduplicationId) {
        commitCheckpoint(state);
        return append(state.runId(), state.superstep(), type, data, deduplicationId);
    }
    default GraphRuntimeEvent commit(GraphExecutionState state, GraphRuntimeEventType type,
                                     Map<String, Object> data, String deduplicationId,
                                     List<String> acknowledgedDeliveryIds) {
        return commit(state, type, data, deduplicationId);
    }
    default GraphRuntimeEvent commit(GraphExecutionState state, GraphRuntimeEventType type,
                                     Map<String, Object> data, String deduplicationId,
                                     List<String> acknowledgedDeliveryIds, List<String> consumedSignalIds) {
        return commit(state, type, data, deduplicationId, acknowledgedDeliveryIds);
    }
    GraphRuntimeEvent append(String runId, long superstep, GraphRuntimeEventType type, Map<String, Object> data,
                             String deduplicationId);
    List<GraphRuntimeEvent> events(String runId);
}
