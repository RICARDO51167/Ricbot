package ricbot.domain.runtime;

import ricbot.domain.agent.graph.GraphExecutionState;

import java.util.List;

public record RunView(GraphExecutionState state, List<TaskView> tasks, long lastEventSequence,
                      String projectionDigest) {
    public RunView { tasks = List.copyOf(tasks != null ? tasks : List.of()); }
}
