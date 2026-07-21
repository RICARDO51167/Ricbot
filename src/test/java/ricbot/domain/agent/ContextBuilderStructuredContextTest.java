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
        ContextBuilder builder = new ContextBuilder(workspace, "UTC");
        PromptContextBundle bundle = new PromptContextBundle();
        bundle.addItem("recent_history", "older relevant exchange");
        bundle.addItem("task_state", "goal: finish upgrade");
        bundle.addItem("user_profile", "prefers concise replies");
        bundle.addItem("memory_recall", "project uses Java 17");
        bundle.addItem("memory_recall", "project uses Java 17");
        bundle.addItem("project_notes", "notes/project/decisions.md: use GSSC");
        bundle.addItem("workspace_knowledge", "src/main/java/App.java:1-20");
        bundle.addItem("team_context", "session=team_1 | state=VERIFYING | goal=finish upgrade");
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
        assertTrue(system.contains("## project_notes"));
        assertTrue(system.contains("## workspace_knowledge"));
        assertTrue(system.contains("## team_context"));
        assertTrue(system.contains("## tool_trace"));
        assertTrue(system.indexOf("## recent_history") < system.indexOf("## task_state"));
        assertTrue(system.indexOf("## task_state") < system.indexOf("## user_profile"));
        assertTrue(system.indexOf("## memory_recall") < system.indexOf("## project_notes"));
        assertTrue(system.indexOf("## project_notes") < system.indexOf("## workspace_knowledge"));
        assertTrue(system.indexOf("## workspace_knowledge") < system.indexOf("## team_context"));
        assertTrue(system.indexOf("## team_context") < system.indexOf("## tool_trace"));
        assertTrue(system.contains("grep: ok | result=found files"));
        assertEquals(system.indexOf("project uses Java 17"), system.lastIndexOf("project uses Java 17"));
    }

    @Test
    void buildMessages_appliesStructuredContextBudgets(@TempDir Path workspace) {
        ContextBuilder builder = new ContextBuilder(workspace, "UTC");
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
    void promptContextQualityReport_surfacesBudgetAndNoiseSignals() {
        PromptContextBundle bundle = PromptContextBundle.forContextWindow(8_000);
        bundle.addItem("memory_recall", "[semantic] project uses MCP", 0.8d);
        bundle.addItem("memory_recall", "[semantic] project uses MCP", 0.8d);
        bundle.addItem("recent_history", "archived session: old MCP discussion");
        bundle.addItem("tool_trace", "exec: error " + "x".repeat(400));
        for (int i = 0; i < 20; i++) {
            bundle.addItem("memory_recall", "memory-" + i + " " + "y".repeat(200), 0.2d);
        }

        ContextQualityReport report = bundle.qualityReport();
        Map<String, Object> asMap = report.toMap();

        assertTrue((Integer) asMap.get("totalTokens") > 0);
        assertTrue((Double) asMap.get("budgetUsageRate") > 0d);
        assertTrue((Double) asMap.get("avgRelevanceScore") > 0d);
        assertTrue((Double) asMap.get("toolResultNoiseRatio") > 0d);
        assertEquals(true, asMap.get("missingTaskState"));
        assertEquals(true, asMap.get("compressionApplied"));
    }

}
