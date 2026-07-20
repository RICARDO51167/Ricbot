package ricbot.domain.agent.graph;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Serializable cursor for arbitrary graph execution. */
public record GraphExecutionState(
        int schemaVersion,
        String graphId,
        String nodeId,
        int iteration,
        long transition,
        GraphExecutionStatus status,
        Map<String, Object> variables,
        String pauseReason,
        String error,
        Instant updatedAt
) {
    public GraphExecutionState {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported graph state schema");
        graphId = required(graphId, "graphId");
        nodeId = required(nodeId, "nodeId");
        iteration = Math.max(0, iteration);
        transition = Math.max(0, transition);
        status = Objects.requireNonNullElse(status, GraphExecutionStatus.READY);
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables != null ? variables : Map.of()));
        pauseReason = clean(pauseReason);
        error = clean(error);
        updatedAt = Objects.requireNonNullElseGet(updatedAt, Instant::now);
    }

    public static GraphExecutionState initial(String graphId, String entryNode, Map<String, Object> variables) {
        return new GraphExecutionState(1, graphId, entryNode, 0, 0,
                GraphExecutionStatus.READY, variables, "", "", Instant.now());
    }

    private static String required(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
