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

    @Test
    void buildMessages_appliesStructuredContextBudgets(@TempDir Path workspace) {
        ContextBuilder builder = new ContextBuilder(workspace, "UTC", List.of());
        PromptContextBundle bundle = new PromptContextBundle();
        for (int i = 0; i < 12; i++) {
            bundle.addItem("tool_trace", "trace-" + i + " " + "x".repeat(80));
        }

        List<Map<String, Object>> messages = builder.buildMessages(
                List.of(),
                "next",
                null,
                "cli",
                "direct",
                "",
                "user",
                bundle
        );

        String system = String.valueOf(messages.get(0).get("content"));
        assertTrue(system.contains("## tool_trace"));
        assertTrue(system.contains("trace-0"));
        assertTrue(system.contains("trace-3"));
        assertFalse(system.contains("trace-4 x"));
        assertTrue(system.contains("[truncated]"));
    }

    @Test
    void promptContextBudgetScalesWithContextWindow() {
        PromptContextBundle small = PromptContextBundle.forContextWindow(8_000);
        PromptContextBundle large = PromptContextBundle.forContextWindow(128_000);

        assertTrue(small.totalCharLimit() < new PromptContextBundle().totalCharLimit());
        assertTrue(large.totalCharLimit() > new PromptContextBundle().totalCharLimit());
        assertTrue(large.sectionBudget("memory_recall").maxChars() > small.sectionBudget("memory_recall").maxChars());
        assertTrue(large.sectionBudget("tool_trace").maxItems() > small.sectionBudget("tool_trace").maxItems());
    }

    @Test
    void buildMessages_rendersSkillsContextSeparatelyFromSessionSummary(@TempDir Path workspace) {
        ContextBuilder builder = new ContextBuilder(workspace, "UTC", List.of());

        List<Map<String, Object>> messages = builder.buildMessages(
                List.of(),
                "next",
                null,
                "cli",
                "direct",
                "archived session note",
                "## Skill: demo\n\nDemo skill body",
                "user",
                new PromptContextBundle()
        );

        String system = String.valueOf(messages.get(0).get("content"));
        assertTrue(system.contains("## Skills Context"));
        assertTrue(system.contains("Demo skill body"));
        assertTrue(system.contains("## Session Context"));
        assertTrue(system.contains("archived session note"));
        assertTrue(system.indexOf("## Skills Context") < system.indexOf("## Session Context"));
    }
}
