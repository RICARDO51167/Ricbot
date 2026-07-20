package ricbot.domain.agent;

import ricbot.domain.agent.graph.AgentGraphDefinition;
import ricbot.domain.agent.graph.GraphEdge;

import java.time.Instant;

/** Default ReAct scheduler backed by the same registered topology as the generic graph runtime. */
public final class AgentNodeScheduler {
    public static final AgentGraphDefinition DEFAULT_GRAPH = AgentGraphDefinition
            .builder("ricbot-react-v1", AgentNodeType.MODEL.name())
            .node(AgentNodeType.MODEL.name())
            .node(AgentNodeType.TOOLS.name())
            .terminalNode(AgentNodeType.TERMINAL.name())
            .edge(AgentNodeType.MODEL.name(), "tools", AgentNodeType.TOOLS.name())
            .edge(AgentNodeType.MODEL.name(), "terminal", AgentNodeType.TERMINAL.name())
            .edge(AgentNodeType.TOOLS.name(), "next", AgentNodeType.MODEL.name())
            .build();

    private final AgentRunController controller;
    private final AgentGraphDefinition graph;
    private AgentNodeState state;

    public AgentNodeScheduler(AgentRunController controller) {
        this(controller, AgentNodeState.initial(), DEFAULT_GRAPH);
    }

    public AgentNodeScheduler(AgentRunController controller, AgentNodeState state) {
        this(controller, state, DEFAULT_GRAPH);
    }

    AgentNodeScheduler(AgentRunController controller, AgentNodeState state, AgentGraphDefinition graph) {
        if (controller == null) throw new IllegalArgumentException("controller is required");
        this.controller = controller;
        this.state = state != null ? state : AgentNodeState.initial();
        this.graph = graph != null ? graph : DEFAULT_GRAPH;
    }

    public boolean canSchedule() {
        return !state.terminal() && controller.canContinue();
    }

    public AgentNodeState startModel() {
        requireNode(AgentNodeType.MODEL);
        controller.recordTurn();
        state = next(AgentNodeType.MODEL,
                Math.max(controller.currentTurn(), state.iteration() + 1), false);
        return state;
    }

    public AgentNodeState tools() {
        requireNode(AgentNodeType.MODEL);
        state = route("tools");
        return state;
    }

    public AgentNodeState nextModel() {
        requireNode(AgentNodeType.TOOLS);
        state = route("next");
        return state;
    }

    public AgentNodeState terminal() {
        if (state.terminal()) return state;
        state = route("terminal");
        return state;
    }

    public AgentNodeState state() { return state; }

    private AgentNodeState route(String outcome) {
        GraphEdge edge = graph.outgoing(state.node().name()).stream()
                .filter(value -> value.outcome().equals(outcome))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no graph edge from " + state.node() + " for outcome " + outcome));
        AgentNodeType nextNode = AgentNodeType.valueOf(edge.to());
        return next(nextNode, state.iteration(), graph.terminal(edge.to()));
    }

    private AgentNodeState next(AgentNodeType node, int iteration, boolean terminal) {
        return new AgentNodeState(1, node, iteration, state.transition() + 1, terminal, Instant.now());
    }

    private void requireNode(AgentNodeType expected) {
        if (state.terminal() || state.node() != expected) {
            throw new IllegalStateException("expected scheduler node " + expected + " but was " + state.node());
        }
    }
}
