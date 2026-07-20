package ricbot.domain.agent.graph;

public record GraphEdge(String from, String outcome, String to, String conditionId, int priority) {
    public GraphEdge {
        from = required(from, "from");
        outcome = required(outcome, "outcome");
        to = required(to, "to");
        conditionId = conditionId != null && !conditionId.isBlank() ? conditionId.trim() : "always";
    }
    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
