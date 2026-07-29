package ricbot.domain.agent.graph;

import ricbot.domain.agent.graph.dto.GraphEdge;
import ricbot.domain.agent.graph.dto.GraphNodeSpec;
import ricbot.domain.agent.graph.dto.GraphRetryPolicy;
import ricbot.domain.agent.graph.enump.GraphEdgeMode;
import ricbot.domain.agent.graph.enump.GraphFailurePolicy;

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
        
        // Validate basic structural constraints
        if (!nodeSpecs.containsKey(entryNode)) throw new IllegalArgumentException("entry node is not registered: " + entryNode);
        if (terminalNodes.isEmpty()) throw new IllegalArgumentException("graph requires at least one terminal node");
        if (!nodeSpecs.keySet().containsAll(terminalNodes)) {
            throw new IllegalArgumentException("unregistered terminal nodes: " + (new LinkedHashSet<>(terminalNodes).stream()
                    .filter(n -> !nodeSpecs.containsKey(n)).toList()));
        }
        if (maxSupersteps < 1) throw new IllegalArgumentException("maxSupersteps must be positive, got: " + maxSupersteps);

        // Build and validate edge structure
        Map<String, List<GraphEdge>> edges = new LinkedHashMap<>();
        Set<String> exactEdges = new LinkedHashSet<>();
        Map<String, GraphEdgeMode> routeModes = new LinkedHashMap<>();
        
        for (GraphEdge edge : builder.edges) {
            if (!nodeSpecs.containsKey(edge.from())) {
                throw new IllegalArgumentException("edge references unregistered source node: " + edge.from());
            }
            if (!nodeSpecs.containsKey(edge.to())) {
                throw new IllegalArgumentException("edge references unregistered target node: " + edge.to());
            }
            if (terminalNodes.contains(edge.from())) {
                throw new IllegalArgumentException("terminal node cannot have outgoing edges: " + edge.from());
            }
            
            // Check for duplicate exact edges
            String identity = edge.from() + "\u0000" + edge.outcome() + "\u0000" + edge.to() + "\u0000" 
                            + edge.conditionId() + "\u0000" + edge.priority() + "\u0000" + edge.mode();
            if (!exactEdges.add(identity)) {
                throw new IllegalArgumentException("duplicate edge definition found for: " + edge.from());
            }
            
            // Check for mode consistency on the same route (from + outcome)
            String route = edge.from() + "\u0000" + edge.outcome();
            GraphEdgeMode priorMode = routeModes.putIfAbsent(route, edge.mode());
            if (priorMode != null && priorMode != edge.mode()) {
                throw new IllegalArgumentException("route mixes FIRST_MATCH and FAN_OUT modes: " + route);
            }
            
            edges.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
        }
        
        // Sort edges by priority (descending) then by target node name
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

    /** Validates that all non-terminal nodes have registered executors. */
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
        if (!unreachable.isEmpty()) {
            throw new IllegalArgumentException("unreachable graph nodes detected: " + unreachable);
        }
        
        if (terminalNodes.stream().noneMatch(reachable::contains)) {
            throw new IllegalArgumentException("no reachable terminal node exists in the graph");
        }
        
        for (String node : reachable) {
            if (!terminalNodes.contains(node) && outgoing(node).isEmpty()) {
                throw new IllegalArgumentException("non-terminal node has no outgoing edges: " + node);
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

        private Builder(String graphId, String entryNode) { 
            this.graphId = required(graphId, "graphId"); 
            this.entryNode = required(entryNode, "entryNode"); 
        }

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
        
        public Builder maxSupersteps(int value) { 
            if (value < 1) throw new IllegalArgumentException("maxSupersteps must be positive");
            this.maxSupersteps = value; 
            return this; 
        }
        
        public AgentGraphDefinition build() { return new AgentGraphDefinition(this); }
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
