package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextBuilderStructuredContextTest {

    @Test
    void buildMessages_rendersStructuredContextInFixedOrder(@TempDir Path workspace) {
        ContextBuilder builder = new ContextBuilder(workspace, "UTC", List.of());
        PromptContextBundle bundle = new PromptContextBundle();
        bundle.addItem("recent_history", "older relevant exchange");
        bundle.addItem("task_state", "goal: finish upgrade");
        bundle.addItem("user_profile", "prefers concise replies");
        bundle.addItem("memory_recall", "project uses Java 17");
        bundle.addItem("memory_recall", "project uses Java 17");
        bundle.addItem("tool_trace", "grep: ok | result=found files");

        List<Map<String, Object>> messages = builder.buildMessages(
                List.of(Map.of("role", "user", "content", "hello")),
                "next",
                null,
                "cli",
                "direct",
                "summary",
                "user",
                bundle
        );

        String system = String.valueOf(messages.get(0).get("content"));
        assertTrue(system.contains("## recent_history"));
        assertTrue(system.contains("## task_state"));
        assertTrue(system.contains("## user_profile"));
        assertTrue(system.contains("## memory_recall"));
        assertTrue(system.contains("## tool_trace"));
        assertTrue(system.indexOf("## recent_history") < system.indexOf("## task_state"));
        assertTrue(system.indexOf("## task_state") < system.indexOf("## user_profile"));
        assertTrue(system.contains("grep: ok | result=found files"));
        assertEquals(system.indexOf("project uses Java 17"), system.lastIndexOf("project uses Java 17"));
    }
}
