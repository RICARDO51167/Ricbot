package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.memory.Dream;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.CommandRiskAnalyzer;
import ricbot.domain.security.RiskAssessment;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.infra.config.Config;
import ricbot.integration.command.CommandRouter;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.WriteFileTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class AgentCommandsTest {

    @Test
    void approveAndRejectCommandsUpdateApprovalStatus(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        ApprovalService approvalService = new ApprovalService();
        ApprovalRequest request = approvalService.createRequest(RiskAssessment.of(
                CommandRiskLevel.HIGH,
                List.of("danger"),
                "rm file",
                "exec",
                List.of("file")
        ));

        AgentCommands commands = new AgentCommands(
                sessionManager,
                memoryStore,
                null,
                new Config.DreamConfig(),
                "model",
                workspace,
                msg -> "cli:direct",
                key -> List.<Future<?>>of(),
                (key, reason) -> {},
                approvalService
        );
        CommandRouter router = new CommandRouter();
        commands.register(router);

        CommandRouter.CommandContext approveCtx = context("/approve " + request.requestId(), sessionManager);
        String approved = router.dispatch(approveCtx).get().getContent();
        assertTrue(approved.contains("已批准"), approved);
        assertEquals(ApprovalRequest.ApprovalStatus.APPROVED, approvalService.find(request.requestId()).status());

        String rejected = router.dispatch(context("/reject " + request.requestId(), sessionManager)).get().getContent();
        assertTrue(rejected.contains("已拒绝"), rejected);
        assertEquals(ApprovalRequest.ApprovalStatus.REJECTED, approvalService.find(request.requestId()).status());
    }

    @Test
    void approveCommandRestoresPendingWriteFileExecution(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        ApprovalService approvalService = new ApprovalService();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new WriteFileTool(workspace, workspace, new CommandRiskAnalyzer(workspace), approvalService));

        String gated = String.valueOf(tools.execute("write_file", Map.of("path", "approved.txt", "content", "done\n")));
        String requestId = requestId(gated);
        assertTrue(gated.contains("需要审批后才能执行"), gated);
        assertTrue(Files.notExists(workspace.resolve("approved.txt")));

        AgentCommands commands = new AgentCommands(
                sessionManager,
                memoryStore,
                null,
                new Config.DreamConfig(),
                "model",
                workspace,
                msg -> "cli:direct",
                key -> List.<Future<?>>of(),
                (key, reason) -> {},
                approvalService,
                tools
        );
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String approved = router.dispatch(context("/approve " + requestId, sessionManager)).get().getContent();
        assertTrue(approved.contains("已批准并恢复执行"), approved);
        assertTrue(approved.contains("DiffReview"), approved);
        assertTrue(approved.contains("suggestedTests"), approved);
        assertTrue(approved.contains("rollbackHint: rm approved.txt"), approved);
        assertEquals("done\n", Files.readString(workspace.resolve("approved.txt")));

        String duplicate = router.dispatch(context("/approve " + requestId, sessionManager)).get().getContent();
        assertTrue(duplicate.contains("不能重复执行") || duplicate.contains("已消费"), duplicate);
    }

    @Test
    void summaryCommandRendersCurrentTaskSummary(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        Session session = sessionManager.getOrCreate("cli:direct");
        seedSummaryMetadata(session);

        AgentCommands commands = new AgentCommands(
                sessionManager,
                memoryStore,
                null,
                new Config.DreamConfig(),
                "model",
                workspace,
                msg -> "cli:direct",
                key -> List.<Future<?>>of(),
                (key, reason) -> {}
        );
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String summary = router.dispatch(context("/summary", sessionManager)).get().getContent();

        assertTrue(summary.contains("Task Summary - V3.4 note writing"), summary);
        assertTrue(summary.contains("Changed Files"), summary);
        assertTrue(summary.contains("Suggested Tests"), summary);
        assertTrue(Files.notExists(workspace.resolve("notes").resolve("tasks")));
    }

    @Test
    void summaryWriteNoteCommandWritesTaskNote(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        Session session = sessionManager.getOrCreate("cli:direct");
        seedSummaryMetadata(session);

        AgentCommands commands = new AgentCommands(
                sessionManager,
                memoryStore,
                null,
                new Config.DreamConfig(),
                "model",
                workspace,
                msg -> "cli:direct",
                key -> List.<Future<?>>of(),
                (key, reason) -> {}
        );
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String written = router.dispatch(context("/summary --write-note", sessionManager)).get().getContent();
        String path = lineValue(written, "path:");

        assertTrue(written.contains("summary note written"), written);
        assertTrue(path.startsWith("notes/tasks/"), written);
        assertTrue(Files.exists(workspace.resolve(path)));
        assertTrue(Files.readString(workspace.resolve(path)).contains("V3.4 note writing"));
        assertTrue(Files.readString(workspace.resolve("notes").resolve("index.json")).contains("task-summary"));
    }

    @Test
    void experienceCommandsExtractListShowVerifyAndReject(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        Session session = sessionManager.getOrCreate("cli:direct");
        seedSummaryMetadata(session);

        AgentCommands commands = new AgentCommands(
                sessionManager,
                memoryStore,
                null,
                new Config.DreamConfig(),
                "model",
                workspace,
                msg -> "cli:direct",
                key -> List.<Future<?>>of(),
                (key, reason) -> {}
        );
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String extracted = router.dispatch(context("/experience extract", sessionManager)).get().getContent();
        assertTrue(extracted.contains("experience extracted"), extracted);
        assertTrue(extracted.contains("TEST_POLICY"), extracted);
        assertTrue(Files.exists(workspace.resolve("experience").resolve("candidates.jsonl")));

        String listed = router.dispatch(context("/experience list", sessionManager)).get().getContent();
        List<String> ids = experienceIds(listed);
        assertTrue(ids.size() >= 2, listed);

        String shown = router.dispatch(context("/experience show " + ids.get(0), sessionManager)).get().getContent();
        assertTrue(shown.contains("Experience " + ids.get(0)), shown);
        assertTrue(shown.contains("status: CANDIDATE"), shown);

        String verified = router.dispatch(context("/experience verify " + ids.get(0), sessionManager)).get().getContent();
        assertTrue(verified.contains("experience verified"), verified);
        assertTrue(verified.contains("status: VERIFIED"), verified);
        assertTrue(Files.readString(workspace.resolve("experience").resolve("verified.jsonl")).contains(ids.get(0)));

        String rejected = router.dispatch(context("/experience reject " + ids.get(1), sessionManager)).get().getContent();
        assertTrue(rejected.contains("experience rejected"), rejected);
        assertTrue(rejected.contains("status: REJECTED"), rejected);
        assertTrue(Files.readString(workspace.resolve("experience").resolve("rejected.jsonl")).contains(ids.get(1)));
    }

    private CommandRouter.CommandContext context(String raw, SessionManager sessionManager) {
        InboundMessage msg = new InboundMessage("cli", "user", "direct", raw);
        Session session = sessionManager.getOrCreate("cli:direct");
        return new CommandRouter.CommandContext(msg, session, "cli:direct", raw, null);
    }

    private static String requestId(String text) {
        for (String line : text.split("\\R")) {
            if (line.startsWith("requestId:")) {
                return line.substring("requestId:".length()).trim();
            }
        }
        throw new AssertionError("missing requestId in: " + text);
    }

    private static String lineValue(String text, String prefix) {
        for (String line : text.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        throw new AssertionError("missing " + prefix + " in: " + text);
    }

    private static List<String> experienceIds(String text) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- exp_")) {
                ids.add(trimmed.substring(2).split("\\s+")[0]);
            }
        }
        return ids;
    }

    private static void seedSummaryMetadata(Session session) {
        session.getMetadata().put(SessionRuntimeKeys.TASK_STATE_KEY, Map.of(
                "goal", "V3.4 note writing",
                "next_action", "Run targeted tests"
        ));
        session.getMetadata().put(SessionRuntimeKeys.TOOL_TRACE_KEY, List.of(
                Map.of(
                        "tool_name", "write_file",
                        "status", "ok",
                        "arguments_summary", "path=src/main/java/ricbot/domain/note/TaskNoteWriter.java",
                        "result_summary", """
                                DiffReview
                                summary: Created src/main/java/ricbot/domain/note/TaskNoteWriter.java (+20/-0)
                                changedFiles: src/main/java/ricbot/domain/note/TaskNoteWriter.java
                                riskLevel: MEDIUM
                                suggestedTests: ./mvnw -q -Dtest='ricbot.domain.note.*Test' test
                                rollbackHint: rm src/main/java/ricbot/domain/note/TaskNoteWriter.java
                                """
                )
        ));
    }
}
