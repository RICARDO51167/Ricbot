package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.policy.PolicyAwareToolExecutor;
import ricbot.domain.policy.PolicyDecisionType;
import ricbot.domain.policy.PolicyEngine;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStatus;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.ReadFileTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        assertTrue(result.policySummary().toString().contains("decision=ALLOW"), result.policySummary().toString());
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
        assertTrue(result.policySummary().toString().contains("role=VERIFIER"), result.policySummary().toString());
    }

    @Test
    void missingWorkspaceFallsBackToBaseWorkspaceLabel(@TempDir Path workspace) {
        TeamWorkerExecutor executor = new TeamWorkerExecutor();

        WorkerExecutionResult result = executor.execute(input(TeamRole.EXPLORER, ""));

        assertTrue(result.findings().toString().contains("fallback base workspace"), result.findings().toString());
    }

    @Test
    void developerRunWorkerGeneratesPlanAndDoesNotModifyFiles(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("target.txt"), "unchanged\n");
        TeamWorkerExecutor executor = new TeamWorkerExecutor();

        WorkerExecutionResult result = executor.execute(new WorkerExecutionInput(
                "task_explore",
                "team_explore",
                TeamRole.DEVELOPER,
                "Edit target.txt safely",
                workspace.toString(),
                "whiteboard summary",
                List.of("target.txt"),
                List.of(),
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0d,
                ""
        ));

        assertEquals("PLANNED", result.status());
        assertTrue(result.summary().contains("Developer Plan"), result.summary());
        assertTrue(result.developerPlan().toString().contains("target.txt"), result.developerPlan().toString());
        assertTrue(result.requiredApprovals().toString().contains("edit_file"), result.requiredApprovals().toString());
        assertTrue(result.nextActions().toString().contains("/change create"), result.nextActions().toString());
        assertTrue(result.changeSetRecommendation().contains("/team run-verifier"), result.changeSetRecommendation());
        assertEquals("unchanged\n", Files.readString(workspace.resolve("target.txt")));
    }

    @Test
    void executeToolAsRoleDelegatesToPolicyAwareExecutor(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "delegated\n");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(workspace, workspace, List.of()));
        TeamWorkerExecutor executor = new TeamWorkerExecutor();
        WorkspaceSession session = new WorkspaceSession(
                "ws1",
                WorkspaceBackendType.LOCAL,
                workspace.toString(),
                workspace.toString(),
                "",
                "delegated",
                WorkspaceSessionStatus.ACTIVE,
                null,
                null,
                Map.of()
        );

        PolicyAwareToolExecutor.PolicyToolResult result = executor.executeToolAsRole(
                new PolicyAwareToolExecutor(new PolicyEngine(workspace), registry, new ApprovalService(), new TraceStore(workspace)),
                TeamRole.EXPLORER,
                "read_file",
                Map.of("path", "a.txt", "offset", 1, "limit", 20),
                session,
                "session-1",
                "team-1",
                "task-1"
        );

        assertEquals(PolicyDecisionType.ALLOW, result.decision().decisionType());
        assertTrue(result.executed());
        assertTrue(result.resultSummary().contains("delegated"), result.resultSummary());
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
