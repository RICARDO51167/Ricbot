package ricbot.domain.agent.graph.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One deterministic invocation of a graph node. */
public record NodeActivation(
        String activationId,
        String nodeId,
        long planOrder,
        int attempt,
        Map<String, Object> input
) implements Comparable<NodeActivation> {
    public NodeActivation {
        activationId = required(activationId, "activationId");
        nodeId = required(nodeId, "nodeId");
        if (planOrder < 0) throw new IllegalArgumentException("planOrder must be non-negative");
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input != null ? input : Map.of()));
    }

    public NodeActivation nextAttempt() {
        return new NodeActivation(activationId, nodeId, planOrder, attempt + 1, input);
    }

    @Override
    public int compareTo(NodeActivation other) {
        int order = Long.compare(planOrder, other.planOrder);
        if (order != 0) return order;
        order = nodeId.compareTo(other.nodeId);
        return order != 0 ? order : activationId.compareTo(other.activationId);
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
