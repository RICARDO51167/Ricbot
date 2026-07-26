package ricbot.domain.agent.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete, side-effect-free description of an activation's graph writes and routing. */
public record GraphNodeResult(
        String outcome,
        Map<String, Object> updates,
        List<GraphSend> sends,
        GraphWait waitCondition
) {
    public GraphNodeResult {
        outcome = outcome != null && !outcome.isBlank() ? outcome.trim() : "next";
        updates = Collections.unmodifiableMap(new LinkedHashMap<>(updates != null ? updates : Map.of()));
        sends = List.copyOf(sends != null ? sends : List.of());
    }

    public static GraphNodeResult next(String outcome, Map<String, Object> updates) {
        return new GraphNodeResult(outcome, updates, List.of(), null);
    }

    public static GraphNodeResult send(String outcome, Map<String, Object> updates, List<GraphSend> sends) {
        return new GraphNodeResult(outcome, updates, sends, null);
    }

    public static GraphNodeResult waitFor(String reason, GraphWait wait, Map<String, Object> updates) {
        return new GraphNodeResult("wait", updates, List.of(), wait);
    }

    /** Compatibility helper; runtime replaces the placeholder activation id with the current activation. */
    public static GraphNodeResult pause(String reason, Map<String, Object> updates) {
        GraphWait wait = GraphWait.external("legacy-wait", "legacy-activation", "external", reason, Map.of());
        return waitFor(reason, wait, updates);
    }

    public Map<String, Object> variables() { return updates; }
    public boolean pause() { return waitCondition != null; }
    public String pauseReason() { return waitCondition != null ? waitCondition.reason() : ""; }
}
