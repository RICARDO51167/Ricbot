package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskSummaryServiceTest {
    @Test
    void buildsSummaryFromTaskAndToolFacts() {
        TaskState state = TaskState.fromMap(Map.of("goal", "upgrade runtime", "next_action", "run tests"));
        var summary = new TaskSummaryService().summarizeCurrentTask(state, List.of(Map.of(
                "tool_name", "edit_file", "status", "ok", "arguments_summary", "path=src/Runtime.java")),
                List.of(), List.of("./mvnw test"), List.of("SQLite is the fact store"));
        assertEquals("upgrade runtime", summary.goal());
        assertTrue(summary.changedFiles().toString().contains("Runtime.java"));
        assertTrue(summary.keyDecisions().toString().contains("SQLite"));
    }
}
