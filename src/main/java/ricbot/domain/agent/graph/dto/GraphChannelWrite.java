package ricbot.domain.agent.graph.dto;

/** A reducer input with deterministic writer identity. */
public record GraphChannelWrite(
        String channel,
        Object value,
        long planOrder,
        String nodeId,
        String activationId
) implements Comparable<GraphChannelWrite> {
    public GraphChannelWrite {
        channel = required(channel, "channel");
        nodeId = required(nodeId, "nodeId");
        activationId = required(activationId, "activationId");
    }

    @Override
    public int compareTo(GraphChannelWrite other) {
        int order = Long.compare(planOrder, other.planOrder);
        if (order != 0) return order;
        order = nodeId.compareTo(other.nodeId);
        if (order != 0) return order;
        order = activationId.compareTo(other.activationId);
        return order != 0 ? order : channel.compareTo(other.channel);
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
