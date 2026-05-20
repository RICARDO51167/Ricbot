package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamWorkerExecutorTest {

    @Test
    void explorerGeneratesExplorationSummary(@TempDir Path workspace) {
        TeamWorkerExecutor executor = new TeamWorkerExecutor();

        WorkerExecutionResult result = executor.execute(input(TeamRole.EXPLORER, workspace.toString()));

        assertEquals(TeamRole.EXPLORER, result.role());
        assertEquals("COMPLETED", result.status());
        assertTrue(result.summary().contains("Explorer summarized"), result.summary());
        assertTrue(result.findings().toString().contains("Related files"), result.findings().toString());
        assertTrue(result.risks().toString().contains("read-only"), result.risks().toString());
    }

    @Test
    void verifierGeneratesVerificationSummary(@TempDir Path workspace) {
        TeamWorkerExecutor executor = new TeamWorkerExecutor();

        WorkerExecutionResult result = executor.execute(new WorkerExecutionInput(
                "task_verify",
                "team_verify",
                TeamRole.VERIFIER,
                "Verify safe change",
                workspace.toString(),
                "TaskSummary contains verification context",
                List.of("src/main/java/ricbot/domain/team/TeamEngine.java"),
                List.of(),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test"),
                "Implemented safe change.",
                List.of("DiffReview risk=LOW"),
                List.of(),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test"),
                List.of(),
                0d,
                ""
        ));

        assertEquals(TeamRole.VERIFIER, result.role());
        assertEquals("PASS", result.status());
        assertTrue(result.summary().contains("Verifier accepted"), result.summary());
        assertTrue(result.findings().toString().contains("Verification status: PASS"), result.findings().toString());
    }

    @Test
    void missingWorkspaceFallsBackToBaseWorkspaceLabel(@TempDir Path workspace) {
        TeamWorkerExecutor executor = new TeamWorkerExecutor();

        WorkerExecutionResult result = executor.execute(input(TeamRole.EXPLORER, ""));

        assertTrue(result.findings().toString().contains("fallback base workspace"), result.findings().toString());
    }

    private WorkerExecutionInput input(TeamRole role, String workspacePath) {
        return new WorkerExecutionInput(
                "task_explore",
                "team_explore",
                role,
                "Explore team execution",
                workspacePath,
                "whiteboard summary",
                List.of("src/main/java/ricbot/domain/team/TeamEngine.java"),
                List.of("Use team tests"),
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0d,
                ""
        );
    }
}
