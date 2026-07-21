package ricbot.domain.agent.graph;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Generic embeddable graph interpreter; Ricbot Run ownership remains in GraphRunService. */
public final class AgentGraphRuntime {
    private final AgentGraphDefinition definition;
    private final GraphNodeRegistry nodes;
    private final GraphConditionRegistry conditions;
    private GraphExecutionState state;

    public AgentGraphRuntime(
            AgentGraphDefinition definition,
            GraphNodeRegistry nodes,
            GraphConditionRegistry conditions,
            GraphExecutionState initialState
    ) {
        this.definition = java.util.Objects.requireNonNull(definition, "definition");
        this.nodes = java.util.Objects.requireNonNull(nodes, "nodes");
        this.conditions = conditions != null ? conditions : new GraphConditionRegistry();
        this.state = initialState != null
                ? validate(initialState)
                : GraphExecutionState.initial(definition.graphId(), definition.entryNode(), Map.of());
    }

    public GraphExecutionState executeOne(Map<String, Object> input) {
        if (state.status().terminal()) return state;
        if (state.status() == GraphExecutionStatus.PAUSED) {
            throw new IllegalStateException("paused graph must be resumed explicitly");
        }
        if (definition.terminal(state.nodeId())) {
            state = update(state.nodeId(), GraphExecutionStatus.COMPLETED, state.variables(), "", "");
            return state;
        }
        state = update(state.nodeId(), GraphExecutionStatus.RUNNING, state.variables(), "", "");
        try {
            GraphNodeResult result = nodes.require(state.nodeId()).execute(state, input != null ? input : Map.of());
            Map<String, Object> variables = new LinkedHashMap<>(state.variables());
            variables.putAll(result.variables());
            if (result.pause()) {
                state = update(state.nodeId(), GraphExecutionStatus.PAUSED, variables, result.pauseReason(), "");
                return state;
            }
            Map<String, Object> facts = new LinkedHashMap<>(input != null ? input : Map.of());
            facts.putAll(variables);
            GraphEdge edge = select(result.outcome(), facts);
            GraphExecutionStatus nextStatus = definition.terminal(edge.to())
                    ? GraphExecutionStatus.COMPLETED : GraphExecutionStatus.READY;
            state = update(edge.to(), nextStatus, variables, "", "");
            return state;
        } catch (RuntimeException e) {
            state = update(state.nodeId(), GraphExecutionStatus.FAILED, state.variables(), "", message(e));
            throw e;
        } catch (Exception e) {
            state = update(state.nodeId(), GraphExecutionStatus.FAILED, state.variables(), "", message(e));
            throw new IllegalStateException("graph node failed: " + state.nodeId(), e);
        }
    }

    public GraphExecutionState resume(Map<String, Object> variables) {
        if (state.status() != GraphExecutionStatus.PAUSED) throw new IllegalStateException("graph is not paused");
        Map<String, Object> merged = new LinkedHashMap<>(state.variables());
        if (variables != null) merged.putAll(variables);
        state = update(state.nodeId(), GraphExecutionStatus.READY, merged, "", "");
        return state;
    }

    public GraphExecutionState state() { return state; }

    private GraphEdge select(String outcome, Map<String, Object> facts) {
        return definition.outgoing(state.nodeId()).stream()
                .filter(edge -> edge.outcome().equals(outcome) || "*".equals(edge.outcome()))
                .filter(edge -> conditions.require(edge.conditionId()).matches(state, facts))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no graph edge from " + state.nodeId() + " for outcome " + outcome));
    }

    private GraphExecutionState update(
            String nodeId, GraphExecutionStatus status, Map<String, Object> variables,
            String pauseReason, String error) {
        return new GraphExecutionState(1, definition.graphId(), nodeId,
                state.iteration() + (status == GraphExecutionStatus.RUNNING ? 1 : 0),
                state.transition() + 1, status, variables, pauseReason, error, Instant.now());
    }
    private GraphExecutionState validate(GraphExecutionState candidate) {
        if (!definition.graphId().equals(candidate.graphId())) throw new IllegalArgumentException("graph id mismatch");
        if (!definition.nodes().contains(candidate.nodeId())) throw new IllegalArgumentException("unknown graph node");
        return candidate;
    }
    private static String message(Exception e) {
        return e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName();
    }
}
