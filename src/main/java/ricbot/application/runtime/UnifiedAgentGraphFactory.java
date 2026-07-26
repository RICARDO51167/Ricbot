package ricbot.application.runtime;

import ricbot.domain.agent.graph.AgentGraphRuntime;
import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.runtime.RunRequest;

/** Hard-cut graph selector behind the single AgentRuntime API. */
final class UnifiedAgentGraphFactory implements LocalAgentRuntime.GraphFactory, AutoCloseable {
    private final LocalAgentRuntime.GraphFactory agent;
    private volatile LocalAgentRuntime.GraphFactory team;

    UnifiedAgentGraphFactory(LocalAgentRuntime.GraphFactory agent) {
        this.agent = java.util.Objects.requireNonNull(agent, "agent graph factory");
    }

    void team(LocalAgentRuntime.GraphFactory team) {
        this.team = java.util.Objects.requireNonNull(team, "team graph factory");
    }

    @Override public AgentGraphRuntime open(RunRequest request, GraphExecutionState checkpoint) {
        boolean teamMode = request.mode() == RunRequest.Mode.TEAM
                || checkpoint != null && ricbot.domain.agent.graph.DefaultTeamGraph.GRAPH_ID.equals(checkpoint.graphId());
        if (!teamMode) return agent.open(request, checkpoint);
        LocalAgentRuntime.GraphFactory available = team;
        if (available == null) throw new IllegalStateException("team graph factory is not initialized");
        return available.open(request, checkpoint);
    }

    @Override public void close() {
        closeFactory(team);
        closeFactory(agent);
    }

    private static void closeFactory(LocalAgentRuntime.GraphFactory factory) {
        if (factory instanceof AutoCloseable closeable) {
            try { closeable.close(); }
            catch (Exception failure) { throw new IllegalStateException("cannot close graph factory", failure); }
        }
    }
}
