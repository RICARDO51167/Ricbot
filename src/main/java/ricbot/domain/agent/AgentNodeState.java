package ricbot.domain.agent;

import java.time.Instant;
import java.util.Objects;

/** Serializable scheduler state, independent from model/tool implementation details. */
public record AgentNodeState(
        int schemaVersion,
        AgentNodeType node,
        int iteration,
        long transition,
        boolean terminal,
        Instant updatedAt
) {
    public AgentNodeState {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported node state schema");
        node = Objects.requireNonNull(node, "node");
        iteration = Math.max(0, iteration);
        transition = Math.max(0, transition);
        terminal = terminal || node == AgentNodeType.TERMINAL;
        updatedAt = Objects.requireNonNullElseGet(updatedAt, Instant::now);
    }

    public static AgentNodeState initial() {
        return new AgentNodeState(1, AgentNodeType.MODEL, 0, 0, false, Instant.now());
    }
}
