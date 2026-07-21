package ricbot.domain.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.TaskSummaryService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.session.SessionManager;
import ricbot.tool.filesystem.DiffReview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentOrchestratorTest {

    @Test
    void createsPlannerExplorerAndReviewerTasks() {
        SubAgentOrchestrator orchestrator = new SubAgentOrchestrator();

        SubAgentTask planner = orchestrator.createPlannerTask(
                "Ship V4.1",
                Map.of("goal", "Ship V4.1", "next_action", "Add tests")
        );
        SubAgentTask explorer = orchestrator.createExplorerTask(
                "Explore context",
                List.of("src/main/java/ricbot/domain/agent/ContextSelectionService.java"),
                List.of("notes/project/context.md")
        );
        SubAgentTask reviewer = orchestrator.createReviewerTask(
                new DiffReview(
                        List.of("src/main/java/ricbot/domain/subagent/SubAgentOrchestrator.java"),
                        3,
                        1,
                        CommandRiskLevel.MEDIUM,
                        "Subagent orchestrator changed",
                        List.of(),
                        List.of("./mvnw -q -Dtest='ricbot.domain.subagent.*Test' test"),
                        "git checkout -- src/main/java/ricbot/domain/subagent/SubAgentOrchestrator.java",
                        List.of("subagent"),
                        false,
                        false,
                        false
                ),
                summary()
        );

        assertEquals(SubAgentRole.PLANNER, planner.role());
        assertEquals(SubAgentRole.EXPLORER, explorer.role());
        assertEquals(SubAgentRole.REVIEWER, reviewer.role());
        assertTrue(planner.expectedOutputSchema().contains("findings"));
        assertFalse(explorer.relatedFiles().isEmpty());
        assertTrue(reviewer.inputContext().contains("Subagent orchestrator changed"));
    }

    @Test
    void recordResultListsRecentAndRendersContext(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        SubAgentOrchestrator orchestrator = new SubAgentOrchestrator(sessionManager, workspace);
        SubAgentResult result = new SubAgentResult(
                "subtask_1",
                SubAgentRole.PLANNER,
                "Plan the task.",
                List.of("Use small steps."),
                List.of("Keep provider path unchanged."),
                List.of("./mvnw -q -Dtest='ricbot.domain.subagent.*Test' test"),
                List.of("src/main/java/ricbot/domain/subagent/SubAgentOrchestrator.java"),
                0.7d,
                null
        );

        orchestrator.recordResult("cli:direct", result);

        List<SubAgentResult> recent = orchestrator.listRecentResults("cli:direct");
        String rendered = orchestrator.renderResultsForContext("cli:direct");

        assertEquals(1, recent.size());
        assertEquals("subtask_1", recent.get(0).taskId());
        assertTrue(rendered.contains("PLANNER"), rendered);
        assertTrue(rendered.contains("Use small steps"), rendered);
        assertTrue(Files.exists(workspace.resolve("notes").resolve("temporary")));
        assertTrue(Files.readString(workspace.resolve("notes").resolve("index.json")).contains("subagent"));
    }

    private static TaskSummaryService.TaskSummary summary() {
        return new TaskSummaryService.TaskSummary(
                "V4.1 subagent",
                List.of("src/main/java/ricbot/domain/subagent/SubAgentOrchestrator.java"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("SubAgentOrchestrator.java — added role summaries"),
                List.of("./mvnw -q -Dtest='ricbot.domain.subagent.*Test' test"),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }
}
