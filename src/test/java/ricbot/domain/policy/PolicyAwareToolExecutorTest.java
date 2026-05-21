package ricbot.domain.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.team.TeamRole;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStatus;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.WriteFileTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyAwareToolExecutorTest {

    @Test
    void explorerReadFileAllowsAndExecutes(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello policy\n");
        PolicyAwareToolExecutor executor = executor(workspace, new ApprovalService(), registry(workspace), new TraceStore(workspace));

        PolicyAwareToolExecutor.PolicyToolResult result = executor.execute(
                TeamRole.EXPLORER,
                "read_file",
                Map.of("path", "a.txt", "offset", 1, "limit", 20),
                null,
                "session-1",
                "team-1",
                "task-1"
        );

        assertEquals(PolicyDecisionType.ALLOW, result.decision().decisionType());
        assertTrue(result.executed());
        assertTrue(result.resultSummary().contains("hello policy"), result.resultSummary());
    }

    @Test
    void explorerWriteFileDenied(@TempDir Path workspace) {
        PolicyAwareToolExecutor executor = executor(workspace, new ApprovalService(), registry(workspace), new TraceStore(workspace));

        PolicyAwareToolExecutor.PolicyToolResult result = executor.execute(
                TeamRole.EXPLORER,
                "write_file",
                Map.of("path", "a.txt", "content", "blocked"),
                null,
                "session-1",
                "team-1",
                "task-1"
        );

        assertEquals(PolicyDecisionType.DENY, result.decision().decisionType());
        assertFalse(result.executed());
        assertFalse(Files.exists(workspace.resolve("a.txt")));
    }

    @Test
    void developerEditFileRequiresApproval(@TempDir Path workspace) {
        ApprovalService approvalService = new ApprovalService();
        PolicyAwareToolExecutor executor = executor(workspace, approvalService, registry(workspace), new TraceStore(workspace));

        PolicyAwareToolExecutor.PolicyToolResult result = executor.execute(
                TeamRole.DEVELOPER,
                "edit_file",
                Map.of("path", "a.txt", "old_text", "a", "new_text", "b"),
                null,
                "session-1",
                "team-1",
                "task-1"
        );

        assertEquals(PolicyDecisionType.REQUIRE_APPROVAL, result.decision().decisionType());
        assertFalse(result.executed());
        assertFalse(result.approvalRequestId().isBlank());
        ApprovalRequest request = approvalService.find(result.approvalRequestId());
        assertNotNull(request);
        assertEquals("edit_file", request.pendingToolCall().toolName());
        assertEquals("DEVELOPER", request.pendingToolCall().arguments().get("__role"));
        assertEquals("task-1", request.pendingToolCall().arguments().get("__task_id"));
    }

    @Test
    void developerReadFileAllowed(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "developer read\n");
        PolicyAwareToolExecutor executor = executor(workspace, new ApprovalService(), registry(workspace), new TraceStore(workspace));

        PolicyAwareToolExecutor.PolicyToolResult result = executor.execute(
                TeamRole.DEVELOPER,
                "read_file",
                Map.of("path", "a.txt", "offset", 1, "limit", 20),
                null,
                "session-1",
                "team-1",
                "task-1"
        );

        assertEquals(PolicyDecisionType.ALLOW, result.decision().decisionType());
        assertTrue(result.executed());
        assertTrue(result.resultSummary().contains("developer read"), result.resultSummary());
    }

    @Test
    void developerCommitRollbackDenied(@TempDir Path workspace) {
        PolicyAwareToolExecutor executor = executor(workspace, new ApprovalService(), registry(workspace), new TraceStore(workspace));

        PolicyAwareToolExecutor.PolicyToolResult commit = executor.execute(
                TeamRole.DEVELOPER,
                "commit",
                Map.of(),
                null,
                "session-1",
                "team-1",
                "task-1"
        );
        PolicyAwareToolExecutor.PolicyToolResult rollback = executor.execute(
                TeamRole.DEVELOPER,
                "rollback",
                Map.of(),
                null,
                "session-1",
                "team-1",
                "task-1"
        );

        assertEquals(PolicyDecisionType.DENY, commit.decision().decisionType());
        assertEquals(PolicyDecisionType.DENY, rollback.decision().decisionType());
        assertFalse(commit.executed());
        assertFalse(rollback.executed());
    }

    @Test
    void testerBlockedCommandDenied(@TempDir Path workspace) {
        PolicyAwareToolExecutor executor = executor(workspace, new ApprovalService(), registry(workspace), new TraceStore(workspace));

        PolicyAwareToolExecutor.PolicyToolResult result = executor.execute(
                TeamRole.TESTER,
                "exec",
                Map.of("command", "rm -rf /"),
                null,
                "session-1",
                "team-1",
                "task-1"
        );

        assertEquals(PolicyDecisionType.DENY, result.decision().decisionType());
        assertFalse(result.executed());
    }

    @Test
    void activeWorkspaceRoutesFilePath(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "base\n");
        Path active = workspace.resolve(".workspaces").resolve("ws1");
        Files.createDirectories(active);
        Files.writeString(active.resolve("README.md"), "active\n");
        WorkspaceSession session = new WorkspaceSession(
                "ws1",
                WorkspaceBackendType.LOCAL,
                workspace.toString(),
                active.toString(),
                "",
                "active",
                WorkspaceSessionStatus.ACTIVE,
                null,
                null,
                Map.of()
        );
        PolicyAwareToolExecutor executor = executor(workspace, new ApprovalService(), registry(workspace), new TraceStore(workspace));

        PolicyAwareToolExecutor.PolicyToolResult result = executor.execute(
                TeamRole.EXPLORER,
                "read_file",
                Map.of("path", "README.md", "offset", 1, "limit", 20),
                session,
                "session-1",
                "team-1",
                "task-1"
        );

        assertTrue(result.executed());
        assertTrue(result.resultSummary().contains("active"), result.resultSummary());
        assertFalse(result.resultSummary().contains("base"), result.resultSummary());
        assertTrue(String.valueOf(result.routedArgs().get("path")).startsWith(active.toString()));
    }

    @Test
    void policyTraceRecorded(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "trace\n");
        TraceStore traceStore = new TraceStore(workspace);
        PolicyAwareToolExecutor executor = executor(workspace, new ApprovalService(), registry(workspace), traceStore);

        executor.execute(TeamRole.EXPLORER, "read_file", Map.of("path", "a.txt"), null, "session-1", "team-1", "task-1");

        assertTrue(traceStore.loadEvents(traceStore.traceIdForSession("session-1")).stream()
                .anyMatch(event -> event.type() == TraceEventType.POLICY_EVALUATED), traceStore.loadEvents(traceStore.traceIdForSession("session-1")).toString());
    }

    private static PolicyAwareToolExecutor executor(Path workspace, ApprovalService approvalService, ToolRegistry registry, TraceStore traceStore) {
        return new PolicyAwareToolExecutor(new PolicyEngine(workspace), registry, approvalService, traceStore);
    }

    private static ToolRegistry registry(Path workspace) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(workspace, workspace, List.of()));
        registry.register(new WriteFileTool(workspace, workspace));
        registry.register(new EditFileTool(workspace, workspace));
        return registry;
    }
}
