package ricbot.domain.agent.graph;

import java.util.Map;

public record GraphNodeResult(String outcome, Map<String, Object> variables, boolean pause, String pauseReason) {
    public GraphNodeResult {
        outcome = outcome != null && !outcome.isBlank() ? outcome.trim() : "next";
        variables = variables != null
                ? java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(variables))
                : Map.of();
        pauseReason = pauseReason != null ? pauseReason.trim() : "";
    }
    public static GraphNodeResult next(String outcome, Map<String, Object> variables) {
        return new GraphNodeResult(outcome, variables, false, "");
    }
    public static GraphNodeResult pause(String reason, Map<String, Object> variables) {
        return new GraphNodeResult("pause", variables, true, reason);
    }
}
