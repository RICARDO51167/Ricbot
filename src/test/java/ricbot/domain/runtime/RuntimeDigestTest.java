package ricbot.domain.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ricbot.domain.agent.graph.dto.GraphExecutionState;
import ricbot.domain.runtime.dto.RuntimeDigest;
import ricbot.domain.task.TaskFailurePolicy;
import ricbot.domain.task.TaskRole;
import ricbot.domain.task.TaskSpec;
import ricbot.domain.task.TaskWorkspaceMode;
import ricbot.domain.task.TeamPlan;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeDigestTest {
    @Test
    void graphDigestSurvivesTypedChannelSerialization() throws Exception {
        TaskSpec task = new TaskSpec("task", "run", "plan", 0, "local", "activation", 0, 0,
                TaskRole.EXPLORER, "inspect", List.of(), List.of(), TaskWorkspaceMode.SHARED_READ,
                TaskFailurePolicy.TOLERATE, false, List.of(), List.of());
        TeamPlan plan = new TeamPlan("plan", "run", 0, List.of(task));
        GraphExecutionState state = GraphExecutionState.initial("graph", "run", "node",
                Map.of("teamPlan", plan));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        GraphExecutionState rebuilt = mapper.readValue(mapper.writeValueAsBytes(state), GraphExecutionState.class);

        assertEquals(RuntimeDigest.sha256(state), RuntimeDigest.sha256(rebuilt),
                () -> mapper.valueToTree(state) + "\n" + mapper.valueToTree(rebuilt));
    }
}
