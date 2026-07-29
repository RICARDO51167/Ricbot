package ricbot.domain.agent.graph.dto;

import ricbot.domain.agent.graph.enump.GraphExecutionStatus;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable BSP graph state. All activations in a superstep observe the same channels snapshot. */
public record GraphExecutionState(
        int schemaVersion,
        String graphId,
        String runId,
        long superstep,
        List<NodeActivation> activeNodes,
        Map<String, Object> channels,
        List<GraphWait> waits,
        List<GraphFailure> failures,
        GraphExecutionStatus status,
        String lastNodeId,
        long transition,
        Instant updatedAt
) {
    public static final int SCHEMA_VERSION = 2;

    public GraphExecutionState {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported graph state schema");
        graphId = required(graphId, "graphId");
        runId = required(runId, "runId");
        if (superstep < 0 || transition < 0) throw new IllegalArgumentException("graph counters must be non-negative");
        activeNodes = List.copyOf(activeNodes != null ? activeNodes : List.of());
        channels = Collections.unmodifiableMap(new LinkedHashMap<>(channels != null ? channels : Map.of()));
        waits = List.copyOf(waits != null ? waits : List.of());
        failures = List.copyOf(failures != null ? failures : List.of());
        status = Objects.requireNonNullElse(status, GraphExecutionStatus.READY);
        lastNodeId = lastNodeId != null ? lastNodeId.trim() : "";
        updatedAt = Objects.requireNonNullElseGet(updatedAt, Instant::now);
    }

    public static GraphExecutionState initial(String graphId, String entryNode, Map<String, Object> channels) {
        return initial(graphId, "graph_" + java.util.UUID.randomUUID().toString().replace("-", ""), entryNode, channels);
    }

    public static GraphExecutionState initial(String graphId, String runId, String entryNode,
                                              Map<String, Object> channels) {
        NodeActivation entry = new NodeActivation(runId + ":0:" + entryNode, entryNode, 0, 1, Map.of());
        return new GraphExecutionState(SCHEMA_VERSION, graphId, runId, 0, List.of(entry), channels,
                List.of(), List.of(), GraphExecutionStatus.READY, entryNode, 0, Instant.now());
    }

    /** Convenience cursor over the current durable activation set. */
    public String nodeId() {
        return !activeNodes.isEmpty() ? activeNodes.get(0).nodeId() : lastNodeId;
    }
    public int iteration() { return Math.toIntExact(Math.min(Integer.MAX_VALUE, superstep)); }
    public Map<String, Object> variables() { return channels; }
    public String pauseReason() { return waits.isEmpty() ? "" : waits.get(0).reason(); }
    public String error() { return failures.isEmpty() ? "" : failures.get(failures.size() - 1).message(); }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
