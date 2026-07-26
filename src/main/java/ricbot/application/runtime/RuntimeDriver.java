package ricbot.application.runtime;

import ricbot.domain.agent.graph.AgentGraphRuntime;
import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.agent.graph.GraphExecutionStatus;

import java.util.Map;

/** Deliberately policy-free driver: execute READY activations and stop at a wait or terminal state. */
public final class RuntimeDriver {
    public GraphExecutionState drive(AgentGraphRuntime runtime) {
        while (runtime.state().status() == GraphExecutionStatus.READY) runtime.executeOne(Map.of());
        return runtime.state();
    }
}
