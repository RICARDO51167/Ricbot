package ricbot.domain.agent.graph.interfacep;

import ricbot.domain.agent.graph.dto.GraphExecutionState;
import ricbot.domain.agent.graph.dto.GraphNodeResult;

import java.util.Map;

@FunctionalInterface
public interface GraphNodeExecutor {
    GraphNodeResult execute(GraphExecutionState state, Map<String, Object> input) throws Exception;
}
