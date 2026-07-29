package ricbot.domain.agent.graph.dto;

import ricbot.domain.agent.graph.enump.GraphEdgeMode;

import java.util.Objects;

public record GraphEdge(
        String from,
        String outcome,
        String to,
        String conditionId,
        int priority,
        GraphEdgeMode mode
) {
    public GraphEdge {
        from = required(from, "from");
        outcome = required(outcome, "outcome");
        to = required(to, "to");
        conditionId = conditionId != null && !conditionId.isBlank() ? conditionId.trim() : "always";
        mode = Objects.requireNonNullElse(mode, GraphEdgeMode.FIRST_MATCH);
    }

    public GraphEdge(String from, String outcome, String to, String conditionId, int priority) {
        this(from, outcome, to, conditionId, priority, GraphEdgeMode.FIRST_MATCH);
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
