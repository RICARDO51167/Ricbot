package ricbot.domain.agent.graph;

import java.util.Map;

@FunctionalInterface
public interface GraphCondition {
    boolean matches(GraphExecutionState state, Map<String, Object> facts);
}
