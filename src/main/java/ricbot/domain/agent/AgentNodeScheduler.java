package ricbot.domain.agent;

import java.time.Instant;

/** Validating scheduler for MODEL -> TOOLS -> MODEL / TERMINAL transitions. */
public final class AgentNodeScheduler {
    private final AgentRunController controller;
    private AgentNodeState state;

    public AgentNodeScheduler(AgentRunController controller) {
        this(controller, AgentNodeState.initial());
    }

    public AgentNodeScheduler(AgentRunController controller, AgentNodeState state) {
        if (controller == null) throw new IllegalArgumentException("controller is required");
        this.controller = controller;
        this.state = state != null ? state : AgentNodeState.initial();
    }

    public boolean canSchedule() {
        return !state.terminal() && controller.canContinue();
    }

    public AgentNodeState startModel() {
        requireNode(AgentNodeType.MODEL);
        controller.recordTurn();
        state = next(AgentNodeType.MODEL, controller.currentTurn(), false);
        return state;
    }

    public AgentNodeState tools() {
        requireNode(AgentNodeType.MODEL);
        state = next(AgentNodeType.TOOLS, state.iteration(), false);
        return state;
    }

    public AgentNodeState nextModel() {
        requireNode(AgentNodeType.TOOLS);
        state = next(AgentNodeType.MODEL, state.iteration(), false);
        return state;
    }

    public AgentNodeState terminal() {
        if (state.terminal()) return state;
        state = next(AgentNodeType.TERMINAL, state.iteration(), true);
        return state;
    }

    public AgentNodeState state() { return state; }

    private AgentNodeState next(AgentNodeType node, int iteration, boolean terminal) {
        return new AgentNodeState(1, node, iteration, state.transition() + 1, terminal, Instant.now());
    }

    private void requireNode(AgentNodeType expected) {
        if (state.terminal() || state.node() != expected) {
            throw new IllegalStateException("expected scheduler node " + expected + " but was " + state.node());
        }
    }
}
