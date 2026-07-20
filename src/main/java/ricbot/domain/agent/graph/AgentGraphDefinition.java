package ricbot.domain.agent.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable graph topology; executable behavior is supplied by registries. */
public final class AgentGraphDefinition {
    private final String graphId;
    private final String entryNode;
    private final Set<String> nodes;
    private final Set<String> terminalNodes;
    private final Map<String, List<GraphEdge>> outgoing;

    private AgentGraphDefinition(Builder builder) {
        graphId = required(builder.graphId, "graphId");
        entryNode = required(builder.entryNode, "entryNode");
        nodes = Set.copyOf(builder.nodes);
        terminalNodes = Set.copyOf(builder.terminalNodes);
        if (!nodes.contains(entryNode)) throw new IllegalArgumentException("entry node is not registered");
        if (!nodes.containsAll(terminalNodes)) throw new IllegalArgumentException("terminal node is not registered");
        Map<String, List<GraphEdge>> edges = new LinkedHashMap<>();
        for (GraphEdge edge : builder.edges) {
            if (!nodes.contains(edge.from()) || !nodes.contains(edge.to())) {
                throw new IllegalArgumentException("edge references an unregistered node");
            }
            edges.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
        }
        edges.replaceAll((ignored, value) -> value.stream()
                .sorted(Comparator.comparingInt(GraphEdge::priority).reversed()).toList());
        outgoing = Map.copyOf(edges);
    }

    public String graphId() { return graphId; }
    public String entryNode() { return entryNode; }
    public Set<String> nodes() { return nodes; }
    public boolean terminal(String nodeId) { return terminalNodes.contains(nodeId); }
    public List<GraphEdge> outgoing(String nodeId) { return outgoing.getOrDefault(nodeId, List.of()); }

    public static Builder builder(String graphId, String entryNode) { return new Builder(graphId, entryNode); }

    public static final class Builder {
        private final String graphId;
        private final String entryNode;
        private final Set<String> nodes = new LinkedHashSet<>();
        private final Set<String> terminalNodes = new LinkedHashSet<>();
        private final List<GraphEdge> edges = new ArrayList<>();
        private Builder(String graphId, String entryNode) { this.graphId = graphId; this.entryNode = entryNode; }
        public Builder node(String id) { nodes.add(required(id, "node")); return this; }
        public Builder terminalNode(String id) { node(id); terminalNodes.add(id.trim()); return this; }
        public Builder edge(String from, String outcome, String to) {
            return edge(from, outcome, to, "always", 0);
        }
        public Builder edge(String from, String outcome, String to, String conditionId, int priority) {
            edges.add(new GraphEdge(from, outcome, to, conditionId, priority)); return this;
        }
        public AgentGraphDefinition build() { return new AgentGraphDefinition(this); }
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
