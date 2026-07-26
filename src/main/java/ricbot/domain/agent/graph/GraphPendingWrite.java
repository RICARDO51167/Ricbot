package ricbot.domain.agent.graph;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Durable output of one activation, written before a superstep is committed. */
public record GraphPendingWrite(
        String runId,
        long superstep,
        NodeActivation activation,
        GraphNodeResult result,
        GraphFailure failure,
        Instant completedAt
) {
    public GraphPendingWrite {
        runId = required(runId, "runId");
        if (superstep < 0) throw new IllegalArgumentException("superstep must be non-negative");
        activation = Objects.requireNonNull(activation, "activation");
        if (result == null && failure == null) throw new IllegalArgumentException("result or failure is required");
        completedAt = Objects.requireNonNullElseGet(completedAt, Instant::now);
    }

    public List<GraphChannelWrite> channelWrites() {
        if (result == null) return List.of();
        return result.updates().entrySet().stream()
                .map(entry -> new GraphChannelWrite(entry.getKey(), entry.getValue(), activation.planOrder(),
                        activation.nodeId(), activation.activationId()))
                .toList();
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
