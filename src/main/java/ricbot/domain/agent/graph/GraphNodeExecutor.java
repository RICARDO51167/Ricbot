package ricbot.domain.agent.graph;

import java.util.Map;

@FunctionalInterface
public interface GraphNodeExecutor {
    GraphNodeResult execute(GraphExecutionState state, Map<String, Object> input) throws Exception;
}
