package ricbot.domain.agent.graph;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, validated graph topology and node execution policy. */
public final class AgentGraphDefinition {
    private final String graphId;
    private final String entryNode;
    private final Map<String, GraphNodeSpec> nodeSpecs;
    private final Set<String> terminalNodes;
    private final Map<String, List<GraphEdge>> outgoing;
    private final int maxSupersteps;

    private AgentGraphDefinition(Builder builder) {
        graphId = required(builder.graphId, "graphId");
        entryNode = required(builder.entryNode, "entryNode");
        nodeSpecs = Map.copyOf(builder.nodeSpecs);
        terminalNodes = Set.copyOf(builder.terminalNodes);
        maxSupersteps = builder.maxSupersteps;
        if (!nodeSpecs.containsKey(entryNode)) throw new IllegalArgumentException("entry node is not registered");
        if (terminalNodes.isEmpty()) throw new IllegalArgumentException("graph requires at least one terminal node");
        if (!nodeSpecs.keySet().containsAll(terminalNodes)) throw new IllegalArgumentException("terminal node is not registered");
        if (maxSupersteps < 1) throw new IllegalArgumentException("maxSupersteps must be positive");

        Map<String, List<GraphEdge>> edges = new LinkedHashMap<>();
        Set<String> exactEdges = new LinkedHashSet<>();
        Map<String, GraphEdgeMode> routeModes = new LinkedHashMap<>();
        for (GraphEdge edge : builder.edges) {
            if (!nodeSpecs.containsKey(edge.from()) || !nodeSpecs.containsKey(edge.to())) {
                throw new IllegalArgumentException("edge references an unregistered node");
            }
            if (terminalNodes.contains(edge.from())) throw new IllegalArgumentException("terminal node has outgoing edge: " + edge.from());
            String identity = edge.from() + "\u0000" + edge.outcome() + "\u0000" + edge.to() + "\u0000" + edge.conditionId()
                    + "\u0000" + edge.priority() + "\u0000" + edge.mode();
            if (!exactEdges.add(identity)) throw new IllegalArgumentException("conflicting graph edge from " + edge.from());
            String route = edge.from() + "\u0000" + edge.outcome();
            GraphEdgeMode priorMode = routeModes.putIfAbsent(route, edge.mode());
            if (priorMode != null && priorMode != edge.mode()) {
                throw new IllegalArgumentException("route mixes FIRST_MATCH and FAN_OUT edges: " + edge.from()
                        + " outcome=" + edge.outcome());
            }
            edges.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
        }
        edges.replaceAll((ignored, value) -> value.stream()
                .sorted(Comparator.comparingInt(GraphEdge::priority).reversed().thenComparing(GraphEdge::to))
                .toList());
        outgoing = Map.copyOf(edges);
        validateReachability();
    }

    public String graphId() { return graphId; }
    public String entryNode() { return entryNode; }
    public Set<String> nodes() { return nodeSpecs.keySet(); }
    public GraphNodeSpec nodeSpec(String nodeId) {
        GraphNodeSpec spec = nodeSpecs.get(nodeId);
        if (spec == null) throw new IllegalStateException("unknown graph node: " + nodeId);
        return spec;
    }
    public boolean terminal(String nodeId) { return terminalNodes.contains(nodeId); }
    public List<GraphEdge> outgoing(String nodeId) { return outgoing.getOrDefault(nodeId, List.of()); }
    public int maxSupersteps() { return maxSupersteps; }

    public void validateExecutors(GraphNodeRegistry registry) {
        for (GraphNodeSpec spec : nodeSpecs.values()) {
            if (!spec.terminal()) registry.require(spec.executorId());
        }
    }

    private void validateReachability() {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> remaining = new ArrayDeque<>();
        remaining.add(entryNode);
        while (!remaining.isEmpty()) {
            String node = remaining.removeFirst();
            if (!reachable.add(node)) continue;
            outgoing(node).forEach(edge -> remaining.addLast(edge.to()));
        }
        Set<String> unreachable = new LinkedHashSet<>(nodeSpecs.keySet());
        unreachable.removeAll(reachable);
        if (!unreachable.isEmpty()) throw new IllegalArgumentException("unreachable graph nodes: " + unreachable);
        if (terminalNodes.stream().noneMatch(reachable::contains)) {
            throw new IllegalArgumentException("graph has no reachable terminal node");
        }
        for (String node : reachable) {
            if (!terminalNodes.contains(node) && outgoing(node).isEmpty()) {
                throw new IllegalArgumentException("non-terminal node has no outgoing edge: " + node);
            }
        }
    }

    public static Builder builder(String graphId, String entryNode) { return new Builder(graphId, entryNode); }

    public static final class Builder {
        private final String graphId;
        private final String entryNode;
        private final Map<String, GraphNodeSpec> nodeSpecs = new LinkedHashMap<>();
        private final Set<String> terminalNodes = new LinkedHashSet<>();
        private final List<GraphEdge> edges = new ArrayList<>();
        private int maxSupersteps = 128;

        private Builder(String graphId, String entryNode) { this.graphId = graphId; this.entryNode = entryNode; }

        public Builder node(String id) {
            String clean = required(id, "node");
            nodeSpecs.putIfAbsent(clean, GraphNodeSpec.standard(clean));
            return this;
        }

        public Builder node(String id, String executorId, Duration timeout, GraphRetryPolicy retry,
                            GraphFailurePolicy failurePolicy) {
            String clean = required(id, "node");
            nodeSpecs.put(clean, new GraphNodeSpec(clean, executorId, timeout, retry, failurePolicy, false));
            return this;
        }

        public Builder terminalNode(String id) {
            String clean = required(id, "node");
            nodeSpecs.put(clean, new GraphNodeSpec(clean, clean, Duration.ofMinutes(5), GraphRetryPolicy.none(),
                    GraphFailurePolicy.FAIL_STOP, true));
            terminalNodes.add(clean);
            return this;
        }

        public Builder edge(String from, String outcome, String to) {
            return edge(from, outcome, to, "always", 0, GraphEdgeMode.FIRST_MATCH);
        }
        public Builder edge(String from, String outcome, String to, String conditionId, int priority) {
            return edge(from, outcome, to, conditionId, priority, GraphEdgeMode.FIRST_MATCH);
        }
        public Builder fanOutEdge(String from, String outcome, String to) {
            return edge(from, outcome, to, "always", 0, GraphEdgeMode.FAN_OUT);
        }
        public Builder edge(String from, String outcome, String to, String conditionId, int priority,
                            GraphEdgeMode mode) {
            edges.add(new GraphEdge(from, outcome, to, conditionId, priority, mode));
            return this;
        }
        public Builder maxSupersteps(int value) { maxSupersteps = value; return this; }
        public AgentGraphDefinition build() { return new AgentGraphDefinition(this); }
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
