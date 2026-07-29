package ricbot.domain.agent.graph.interfacep;

import ricbot.domain.agent.graph.dto.GraphExecutionState;

import java.util.Map;

@FunctionalInterface
public interface GraphCondition {
    boolean matches(GraphExecutionState state, Map<String, Object> facts);
}
