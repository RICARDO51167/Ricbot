package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import ricbot.domain.session.Session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskStateTest {

    @Test
    void persistsStructuredStepsWhileKeepingPlanCompatibility() {
        Session session = new Session("cli:direct");
        TaskState state = TaskState.fromSession(session);

        state.beginTurn("优化上下文管理");
        state.markToolStart("grep", Map.of("pattern", "TaskState"));
        state.markToolFinish(Map.of("name", "grep", "status", "ok", "detail", "found files"));
        state.markCompleted("done");
        state.persist(session);

        Object rawObj = session.getMetadata().get(SessionRuntimeKeys.TASK_STATE_KEY);
        assertTrue(rawObj instanceof Map<?, ?>);
        Map<?, ?> raw = (Map<?, ?>) rawObj;
        assertEquals("completed", raw.get("status"));
        assertTrue(raw.get("plan") instanceof List<?>);
        assertTrue(raw.get("steps") instanceof List<?>);

        TaskState restored = TaskState.fromMap(copyObjectMap(raw));
        assertFalse(restored.plan().isEmpty());
        assertFalse(restored.steps().isEmpty());
        assertTrue(restored.steps().stream().allMatch(step -> "completed".equals(step.status())));
        assertFalse(restored.transitions().isEmpty());
        assertTrue(restored.steps().stream().anyMatch(step -> step.evidence() != null && step.evidence().contains("done")));
    }

    @Test
    void derivesStepsFromLegacyPlan() {
        TaskState state = TaskState.fromMap(Map.of(
                "goal", "legacy task",
                "plan", List.of("read", "write"),
                "status", "active"
        ));

        assertEquals(List.of("read", "write"), state.plan());
        assertEquals(2, state.steps().size());
        assertEquals("read", state.steps().get(0).title());
    }

    @Test
    void recordsBlockedTransitionWithEvidence() {
        TaskState state = TaskState.fromMap(Map.of(
                "goal", "fix bug",
                "plan", List.of("inspect"),
                "status", "active"
        ));

        state.markBlocked("missing credentials");

        assertEquals("blocked", state.status());
        assertFalse(state.transitions().isEmpty());
        TaskState.TaskTransition last = state.transitions().get(state.transitions().size() - 1);
        assertEquals("blocked", last.event());
        assertEquals("missing credentials", last.evidence());
        assertTrue(state.steps().stream().anyMatch(step -> "missing credentials".equals(step.evidence())));
    }

    @Test
    void recordsParallelSpawnTasks() {
        TaskState state = TaskState.fromMap(Map.of(
                "goal", "main task",
                "plan", List.of("coordinate"),
                "status", "active"
        ));

        state.markToolStart("spawn", Map.of("task", "检查 MCP 健康状态", "label", "MCP 检查"));

        assertEquals(1, state.parallelTasks().size());
        assertEquals("MCP 检查", state.parallelTasks().get(0).label());
        assertTrue(state.transitions().stream().anyMatch(t -> "parallel_start".equals(t.event())));
        assertTrue(state.toMap().get("parallel_tasks") instanceof List<?>);
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
