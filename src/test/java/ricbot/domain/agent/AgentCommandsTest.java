package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSetStatus;
import ricbot.domain.memory.Dream;
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperienceStore;
import ricbot.domain.experience.ExperienceType;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.CommandRiskAnalyzer;
import ricbot.domain.security.RiskAssessment;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamTask;
import ricbot.domain.team.VerificationResult;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;
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
    void subagentCommandsCreateListShowAndRecordRoleSummaries(@TempDir Path workspace) throws Exception {
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

        String planned = router.dispatch(context("/subagent plan V4.1 subagent summaries", sessionManager)).get().getContent();
        String planId = lineValue(planned, "id:");
        assertTrue(planned.contains("role: PLANNER"), planned);
        assertTrue(planned.contains("subagent result recorded"), planned);

        String explored = router.dispatch(context("/subagent explore inspect src/main/java/ricbot/domain/agent/ContextSelectionService.java", sessionManager)).get().getContent();
        assertTrue(explored.contains("role: EXPLORER"), explored);

        String reviewed = router.dispatch(context("/subagent review", sessionManager)).get().getContent();
        assertTrue(reviewed.contains("role: REVIEWER"), reviewed);

        String listed = router.dispatch(context("/subagent list", sessionManager)).get().getContent();
        assertTrue(listed.contains("subagent results"), listed);
        assertTrue(listed.contains(planId), listed);

        String shown = router.dispatch(context("/subagent show " + planId, sessionManager)).get().getContent();
        assertTrue(shown.contains("SubAgentResult " + planId), shown);
        assertTrue(shown.contains("role: PLANNER"), shown);
        String summary = router.dispatch(context("/summary", sessionManager)).get().getContent();
        assertTrue(summary.contains("SubAgent Findings"), summary);
        assertTrue(summary.contains(planId), summary);
        assertTrue(Files.exists(workspace.resolve("notes").resolve("temporary")));
    }

    @Test
    void teamCommandsOperateStateMachineAndExposeSummary(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);

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

        String started = router.dispatch(context("/team start Implement TeamEngine verifier gate", sessionManager)).get().getContent();
        String teamId = lineValue(started, "id:");
        assertTrue(started.contains("team session started"), started);
        assertTrue(Files.exists(workspace.resolve(".team").resolve(teamId).resolve("whiteboard.md")));

        String created = router.dispatch(context("/team task developer Implement state transitions", sessionManager)).get().getContent();
        String taskId = lineValue(created, "id:");
        assertTrue(created.contains("role: DEVELOPER"), created);
        assertTrue(created.contains("state: CREATED"), created);

        String status = router.dispatch(context("/team status", sessionManager)).get().getContent();
        assertTrue(status.contains(teamId), status);
        assertTrue(status.contains(taskId), status);

        String rejected = router.dispatch(context("/team verify " + taskId + " reject missing targeted tests", sessionManager)).get().getContent();
        assertTrue(rejected.contains("team verification recorded"), rejected);
        assertTrue(rejected.contains("state: REVISING"), rejected);
        assertTrue(rejected.contains("revisionRequest:"), rejected);

        String events = router.dispatch(context("/team events", sessionManager)).get().getContent();
        assertTrue(events.contains("team events"), events);
        assertTrue(events.contains("VERIFICATION_REJECTED"), events);

        String whiteboard = router.dispatch(context("/team whiteboard", sessionManager)).get().getContent();
        assertTrue(whiteboard.contains(".team/" + teamId + "/whiteboard.md"), whiteboard);
        assertTrue(whiteboard.contains("Verifier result"), whiteboard);

        String listed = router.dispatch(context("/team list", sessionManager)).get().getContent();
        assertTrue(listed.contains("team sessions"), listed);
        assertTrue(listed.contains(teamId), listed);

        AgentCommands resumedCommands = new AgentCommands(
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
        CommandRouter resumedRouter = new CommandRouter();
        resumedCommands.register(resumedRouter);
        String resumed = resumedRouter.dispatch(context("/team resume " + teamId, sessionManager)).get().getContent();
        assertTrue(resumed.contains("team session resumed"), resumed);
        assertTrue(resumed.contains(teamId), resumed);

        String suggested = resumedRouter.dispatch(context("/team suggest modify security approval risk and verify tests", sessionManager)).get().getContent();
        assertTrue(suggested.contains("useTeam: true"), suggested);
        assertTrue(suggested.contains("suggestedRoles:"), suggested);
        assertTrue(suggested.contains("VERIFIER"), suggested);

        String autoVerified = resumedRouter.dispatch(context("/team auto-verify " + taskId, sessionManager)).get().getContent();
        assertTrue(autoVerified.contains("team auto verification recorded"), autoVerified);
        assertTrue(autoVerified.contains("status: REJECT"), autoVerified);
        assertTrue(autoVerified.contains("worker result is empty"), autoVerified);

        String verifierReport = resumedRouter.dispatch(context("/team verifier-report " + taskId, sessionManager)).get().getContent();
        assertTrue(verifierReport.contains("team verifier report"), verifierReport);
        assertTrue(verifierReport.contains("missingTests:"), verifierReport);
        assertTrue(verifierReport.contains("requiredActions:"), verifierReport);

        String suggestCurrent = resumedRouter.dispatch(context("/team suggest-current", sessionManager)).get().getContent();
        assertTrue(suggestCurrent.contains("useTeam: true"), suggestCurrent);
        assertTrue(suggestCurrent.contains("VERIFIER"), suggestCurrent);

        String summary = router.dispatch(context("/summary", sessionManager)).get().getContent();
        assertTrue(summary.contains("Team Findings"), summary);
        assertTrue(summary.contains("Verifier Report"), summary);
        assertTrue(summary.contains("revision:"), summary);

        String aborted = router.dispatch(context("/team abort " + taskId, sessionManager)).get().getContent();
        assertTrue(aborted.contains("team task aborted"), aborted);
        assertTrue(aborted.contains("state: ABORTED"), aborted);

        String archived = router.dispatch(context("/team archive " + teamId, sessionManager)).get().getContent();
        assertTrue(archived.contains("team session archived"), archived);
        String listedAfterArchive = router.dispatch(context("/team list", sessionManager)).get().getContent();
        assertTrue(listedAfterArchive.contains("archived=true"), listedAfterArchive);
    }

    @Test
    void changeCommandsCreateInspectApproveAndRollbackWithoutExecuting(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "changed\n");
        Files.writeString(workspace.resolve("new.txt"), "new file\n");
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);

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

        String created = router.dispatch(context("/change create", sessionManager)).get().getContent();
        String changeSetId = lineValue(created, "id:");
        assertTrue(created.contains("changeset created"), created);
        assertTrue(created.contains("README.md"), created);
        assertTrue(created.contains("new.txt"), created);
        assertTrue(Files.exists(workspace.resolve(".changesets").resolve(changeSetId).resolve("changeset.json")));
        assertTrue(Files.exists(workspace.resolve(".changesets").resolve(changeSetId).resolve("diff.patch")));

        String status = router.dispatch(context("/change status", sessionManager)).get().getContent();
        assertTrue(status.contains(changeSetId), status);
        assertTrue(status.contains("status: DRAFT"), status);

        String diff = router.dispatch(context("/change diff", sessionManager)).get().getContent();
        assertTrue(diff.contains("changeset diff " + changeSetId), diff);
        assertTrue(diff.contains("README.md"), diff);

        String commitMessage = router.dispatch(context("/change commit-message", sessionManager)).get().getContent();
        assertTrue(commitMessage.contains("Update"), commitMessage);

        String approved = router.dispatch(context("/change approve", sessionManager)).get().getContent();
        assertTrue(approved.contains("status: APPROVED"), approved);
        assertTrue(approved.contains("No git commit was executed"), approved);

        String rollback = router.dispatch(context("/change rollback", sessionManager)).get().getContent();
        assertTrue(rollback.contains("git restore -- README.md"), rollback);
        assertTrue(rollback.contains("rm new.txt"), rollback);
        assertEquals("changed\n", Files.readString(workspace.resolve("README.md")));
        assertTrue(Files.exists(workspace.resolve("new.txt")));

        String summary = router.dispatch(context("/summary", sessionManager)).get().getContent();
        assertTrue(summary.contains("ChangeSet"), summary);
        assertTrue(summary.contains(changeSetId), summary);
    }

    @Test
    void workspaceCommandsCreateLocalAndExposeContextSource(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String created = router.dispatch(context("/workspace create --mode local local sandbox", sessionManager)).get().getContent();
        String workspaceId = lineValue(created, "id:");
        assertTrue(created.contains("workspace created"), created);
        assertTrue(created.contains("type: LOCAL"), created);
        assertTrue(Files.exists(workspace.resolve(".workspaces").resolve(workspaceId).resolve("session.json")));

        String status = router.dispatch(context("/workspace status", sessionManager)).get().getContent();
        assertTrue(status.contains(workspaceId), status);

        String sources = router.dispatch(context("/context --sources", sessionManager)).get().getContent();
        assertTrue(sources.contains("workspace_session"), sources);
        assertTrue(sources.contains(".workspaces/" + workspaceId + "/session.json"), sources);
    }

    @Test
    void workspaceCommandsCreateWorktreeListUseDiffAndCleanup(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String created = router.dispatch(context("/workspace create --mode worktree isolated sandbox", sessionManager)).get().getContent();
        String workspaceId = lineValue(created, "id:");
        String workspacePath = lineValue(created, "workspacePath:");
        assertTrue(created.contains("type: GIT_WORKTREE"), created);
        assertTrue(Files.exists(Path.of(workspacePath).resolve("README.md")));

        String listed = router.dispatch(context("/workspace list", sessionManager)).get().getContent();
        assertTrue(listed.contains(workspaceId), listed);

        String selected = router.dispatch(context("/workspace use " + workspaceId, sessionManager)).get().getContent();
        assertTrue(selected.contains("workspace selected"), selected);
        assertTrue(selected.contains(workspaceId), selected);

        Files.writeString(Path.of(workspacePath).resolve("README.md"), "initial\nworktree change\n");
        String diff = router.dispatch(context("/workspace diff " + workspaceId, sessionManager)).get().getContent();
        assertTrue(diff.contains("workspace diff " + workspaceId), diff);
        assertTrue(diff.contains("worktree change"), diff);
        assertTrue(new TraceStore(workspace).loadEvents(new TraceStore(workspace).traceIdForSession("cli:direct"))
                .stream().anyMatch(event -> event.type() == TraceEventType.WORKSPACE_DIFFED));

        git(Path.of(workspacePath), "restore", "--", "README.md");
        String cleaned = router.dispatch(context("/workspace cleanup " + workspaceId, sessionManager)).get().getContent();
        assertTrue(cleaned.contains("workspace cleaned"), cleaned);
        assertTrue(cleaned.contains("status: CLEANED"), cleaned);
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
    }

    @Test
    void changeCreateUsesActiveWorkspaceSession(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String createdWorkspace = router.dispatch(context("/workspace create --mode worktree changeset workspace", sessionManager)).get().getContent();
        String workspaceId = lineValue(createdWorkspace, "id:");
        String workspacePath = lineValue(createdWorkspace, "workspacePath:");
        Files.writeString(Path.of(workspacePath).resolve("README.md"), "initial\nfrom active workspace\n");

        String createdChangeSet = router.dispatch(context("/change create", sessionManager)).get().getContent();
        String changeSetId = lineValue(createdChangeSet, "id:");
        ChangeSetService service = new ChangeSetService(workspace);

        assertTrue(createdChangeSet.contains("workspaceSessionId: " + workspaceId), createdChangeSet);
        assertEquals(workspaceId, service.load(changeSetId).workspaceSessionId());
        assertEquals(Path.of(workspacePath).toAbsolutePath().normalize().toString(), service.load(changeSetId).workspacePath());
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
        assertTrue(new TraceStore(workspace).loadEvents(new TraceStore(workspace).traceIdForSession("cli:direct"))
                .stream().anyMatch(event -> event.type() == TraceEventType.CHANGESET_CREATED_FROM_WORKSPACE));
    }

    @Test
    void changeCommitRequiresApprovedAndVerifierPassGates(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "changed\n");
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String created = router.dispatch(context("/change create", sessionManager)).get().getContent();
        String changeSetId = lineValue(created, "id:");

        String notApproved = router.dispatch(context("/change commit", sessionManager)).get().getContent();
        assertTrue(notApproved.contains("ChangeSet must be APPROVED"), notApproved);

        new ChangeSetService(workspace).markApproved(changeSetId);
        String noVerifier = router.dispatch(context("/change commit", sessionManager)).get().getContent();
        assertTrue(noVerifier.contains("verifierStatus=PASS"), noVerifier);
    }

    @Test
    void changeCommitCreatesApprovalAndApproveExecutesCommitOnlyOnce(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "changed\n");
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String created = router.dispatch(context("/change create", sessionManager)).get().getContent();
        String changeSetId = lineValue(created, "id:");
        ChangeSetService service = new ChangeSetService(workspace);
        service.attachVerifierResult(changeSetId, VerificationResult.pass("targeted tests passed"));
        service.markApproved(changeSetId);

        String pending = router.dispatch(context("/change commit --message \"Update README through approval\"", sessionManager)).get().getContent();
        String requestId = lineValue(pending, "requestId:");
        assertTrue(pending.contains("change commit requires approval"), pending);
        assertTrue(pending.contains("riskLevel: HIGH"), pending);
        assertTrue(pending.contains("Run: /approve " + requestId), pending);
        TraceStore traceStore = new TraceStore(workspace);
        String traceId = traceStore.traceIdForSession("cli:direct");
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.CHANGESET_COMMIT_REQUESTED), traceStore.loadEvents(traceId).toString());

        String approved = router.dispatch(context("/approve " + requestId, sessionManager)).get().getContent();
        String head = git(workspace, "rev-parse", "HEAD").trim();
        assertTrue(approved.contains("action: COMMIT"), approved);
        assertTrue(approved.contains("status: COMMITTED"), approved);
        assertTrue(approved.contains("commitHash: " + head), approved);
        assertEquals(GitChangeSetStatus.COMMITTED, new ChangeSetService(workspace).load(changeSetId).status());
        assertEquals("Update README through approval", git(workspace, "log", "-1", "--format=%s").trim());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.CHANGESET_COMMITTED), traceStore.loadEvents(traceId).toString());

        String duplicate = router.dispatch(context("/approve " + requestId, sessionManager)).get().getContent();
        assertTrue(duplicate.contains("不能重复执行") || duplicate.contains("已消费"), duplicate);
        assertEquals(head, git(workspace, "rev-parse", "HEAD").trim());
    }

    @Test
    void changeRollbackExecuteCreatesHighRiskApprovalAndApproveExecutesRollback(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "changed\n");
        Files.writeString(workspace.resolve("new.txt"), "new file\n");
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String created = router.dispatch(context("/change create", sessionManager)).get().getContent();
        String changeSetId = lineValue(created, "id:");
        String pending = router.dispatch(context("/change rollback --execute", sessionManager)).get().getContent();
        String requestId = lineValue(pending, "requestId:");

        assertTrue(pending.contains("change rollback requires approval"), pending);
        assertTrue(pending.contains("riskLevel: HIGH"), pending);
        assertTrue(pending.contains("git restore -- README.md"), pending);
        assertTrue(pending.contains("rm new.txt"), pending);
        TraceStore traceStore = new TraceStore(workspace);
        String traceId = traceStore.traceIdForSession("cli:direct");
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.CHANGESET_ROLLBACK_REQUESTED), traceStore.loadEvents(traceId).toString());

        String approved = router.dispatch(context("/approve " + requestId, sessionManager)).get().getContent();

        assertTrue(approved.contains("action: ROLLBACK"), approved);
        assertTrue(approved.contains("status: ROLLED_BACK"), approved);
        assertTrue(Files.notExists(workspace.resolve("new.txt")));
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
        assertEquals(GitChangeSetStatus.ROLLED_BACK, new ChangeSetService(workspace).load(changeSetId).status());
    }

    @Test
    void traceCommandsListLastShowEventsAndExport(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "changed\n");
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String created = router.dispatch(context("/change create", sessionManager)).get().getContent();
        String changeSetId = lineValue(created, "id:");
        String traceId = new TraceStore(workspace).traceIdForSession("cli:direct");

        String listed = router.dispatch(context("/trace list", sessionManager)).get().getContent();
        assertTrue(listed.contains(traceId), listed);
        assertTrue(listed.contains("types=CHANGESET_CREATED"), listed);

        String last = router.dispatch(context("/trace last", sessionManager)).get().getContent();
        assertTrue(last.contains("trace " + traceId), last);
        assertTrue(last.contains("eventTypes: CHANGESET_CREATED"), last);
        assertTrue(last.contains("changeSets: " + changeSetId), last);
        assertTrue(last.contains("path: .traces/" + traceId + "/events.jsonl"), last);

        String shown = router.dispatch(context("/trace show " + traceId, sessionManager)).get().getContent();
        assertTrue(shown.contains("eventCount: 1"), shown);
        assertTrue(shown.contains("commitHash: none"), shown);
        assertTrue(shown.contains("rollbackStatus: none"), shown);

        String events = router.dispatch(context("/trace events " + traceId, sessionManager)).get().getContent();
        assertTrue(events.contains("trace events"), events);
        assertTrue(events.contains("CHANGESET_CREATED"), events);

        String exported = router.dispatch(context("/trace export " + traceId, sessionManager)).get().getContent();
        assertTrue(exported.contains("\"traceId\":\"" + traceId + "\""), exported);
        assertTrue(exported.contains("\"type\":\"CHANGESET_CREATED\""), exported);
    }

    @Test
    void teamAutoVerifyPassSuggestsChangeSetCreateWhenWorkingTreeDirty(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "changed\n");
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);

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

        router.dispatch(context("/team start Verify changeset hint", sessionManager)).get();
        String created = router.dispatch(context("/team task developer Implement safe docs change", sessionManager)).get().getContent();
        String taskId = lineValue(created, "id:");
        TeamTask task = teamEngine(commands).submitWorkerResult(taskId, "Implemented safe docs change.", List.of());
        assertEquals(taskId, task.id());

        String autoVerified = router.dispatch(context("/team auto-verify " + taskId, sessionManager)).get().getContent();

        assertTrue(autoVerified.contains("status: PASS"), autoVerified);
        assertTrue(autoVerified.contains("changeSetHint: working tree has changes; run /change create"), autoVerified);
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

    @Test
    void contextSourcesShowsVerifiedExperienceButNotCandidate(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        Session session = sessionManager.getOrCreate("cli:direct");
        PromptContextBundle bundle = new PromptContextBundle();
        bundle.addItem(
                "verified_experience",
                "TEST_POLICY | Run filesystem tests",
                0.9d,
                ContextSource.of(
                        "experience",
                        "exp_verified",
                        "experience/verified.jsonl:exp_verified",
                        "Run filesystem tests",
                        0.9d,
                        Map.of(
                                "status", "VERIFIED",
                                "experience_type", "TEST_POLICY",
                                "sourceRef", "V3.6",
                                "confidence", 0.8d,
                                "effectiveConfidence", 0.9d,
                                "successCount", 2,
                                "failureCount", 1,
                                "lastUsedAt", "2026-05-14T00:00:00Z",
                                "reason", "keywords=filesystem"
                        )
                )
        );
        bundle.addItem(
                "subagent_summaries",
                "PLANNER task=subtask_context summary=Plan context display",
                0.8d,
                ContextSource.of(
                        "subagent",
                        "subtask_context",
                        "subagent:subtask_context",
                        "PLANNER summary",
                        0.8d,
                        Map.of(
                                "subagent_role", "PLANNER",
                                "confidence", 0.8d
                        )
                )
        );
        bundle.addItem(
                "team_context",
                "session=team_demo | state=VERIFYING | goal=Team context",
                0.8d,
                ContextSource.of(
                        "team",
                        "team_demo",
                        ".team/team_demo/whiteboard.md",
                        "team whiteboard",
                        0.8d,
                        Map.of(
                                "team_session", "team_demo",
                                "team_state", "VERIFYING"
                        )
                )
        );
        bundle.addItem(
                "team_context",
                "verification task=teamtask_context status=REJECT",
                0.78d,
                ContextSource.of(
                        "team_verification",
                        "team_demo:verification",
                        ".team/team_demo/verification.jsonl",
                        "team verification report",
                        0.78d,
                        Map.of(
                                "team_session", "team_demo",
                                "team_state", "VERIFYING"
                        )
                )
        );
        session.getMetadata().put(SessionRuntimeKeys.CONTEXT_TRACE_KEY, Map.of(
                "prompt_context_budget", bundle.budgetTrace(),
                "context_quality", bundle.qualityReport().toMap()
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
                (key, reason) -> {}
        );
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String sources = router.dispatch(context("/context --sources", sessionManager)).get().getContent();
        String detail = router.dispatch(context("/context --detail", sessionManager)).get().getContent();

        assertTrue(sources.contains("verified_experience"), sources);
        assertTrue(sources.contains("team_context"), sources);
        assertTrue(sources.contains("subagent_summaries"), sources);
        assertTrue(sources.contains("experience/verified.jsonl:exp_verified"), sources);
        assertTrue(sources.contains(".team/team_demo/whiteboard.md"), sources);
        assertTrue(sources.contains(".team/team_demo/verification.jsonl"), sources);
        assertTrue(sources.contains("subagent:subtask_context"), sources);
        assertTrue(sources.contains("subagent_role=PLANNER"), sources);
        assertTrue(sources.contains("status=VERIFIED"), sources);
        assertTrue(sources.contains("effectiveConfidence=0.9"), sources);
        assertTrue(sources.contains("successCount=2"), sources);
        assertTrue(sources.contains("failureCount=1"), sources);
        assertTrue(sources.contains("reason=keywords=filesystem"), sources);
        assertFalse(sources.contains("candidates.jsonl"), sources);
        assertTrue(detail.contains("verified_experience"), detail);
        assertTrue(detail.contains("team_context"), detail);
        assertTrue(detail.contains("subagent_summaries"), detail);
        assertTrue(detail.contains("avg_relevance"), detail);
    }

    @Test
    void experienceFeedbackUsageStaleAndArchiveCommands(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry added = store.addCandidate(experience("Run filesystem feedback tests", 0.7d));
        ExperienceEntry verified = store.verify(added.id());
        store.recordUsage(verified.id(), "cli:direct", "filesystem feedback tests", "goal", 0.9d, "keywords=filesystem");

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

        String success = router.dispatch(context("/experience feedback " + verified.id() + " success", sessionManager)).get().getContent();
        assertTrue(success.contains("experience feedback recorded"), success);
        assertTrue(success.contains("successCount: 1"), success);
        assertTrue(success.contains("effectiveConfidence:"), success);

        String usage = router.dispatch(context("/experience usage " + verified.id(), sessionManager)).get().getContent();
        assertTrue(usage.contains("experience usage"), usage);
        assertTrue(usage.contains("outcome=SUCCESS"), usage);

        String failure = router.dispatch(context("/experience feedback " + verified.id() + " failure", sessionManager)).get().getContent();
        assertTrue(failure.contains("failureCount: 1"), failure);
        router.dispatch(context("/experience feedback " + verified.id() + " failure", sessionManager)).get();

        String stale = router.dispatch(context("/experience stale", sessionManager)).get().getContent();
        assertTrue(stale.contains(verified.id()), stale);
        assertTrue(stale.contains("failureCount>successCount"), stale);

        String archived = router.dispatch(context("/experience archive " + verified.id(), sessionManager)).get().getContent();
        assertTrue(archived.contains("experience archived"), archived);
        assertTrue(archived.contains("status: ARCHIVED"), archived);
        assertTrue(new ExperienceStore(workspace).searchVerified("filesystem feedback tests", List.of(), 5).isEmpty());
    }

    @Test
    void experienceGovernanceCommandsPromoteDemoteRestoreStatsAndReview(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry high = store.verify(store.addCandidate(experience("Run governance promote tests", 0.9d)).id());
        ExperienceEntry failing = store.verify(store.addCandidate(experience("Run governance review tests", 0.6d)).id());
        store.feedback(failing.id(), ricbot.domain.experience.ExperienceOutcome.FAILURE);

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

        String promoted = router.dispatch(context("/experience promote " + high.id(), sessionManager)).get().getContent();
        assertTrue(promoted.contains("experience promoted"), promoted);
        assertTrue(promoted.contains("promotedTo: notes/project/test_policy.md"), promoted);
        assertTrue(Files.readString(workspace.resolve("notes/project/test_policy.md")).contains(high.id()));

        String demoted = router.dispatch(context("/experience demote " + high.id(), sessionManager)).get().getContent();
        assertTrue(demoted.contains("experience demoted"), demoted);
        assertTrue(demoted.contains("demotedAt:"), demoted);

        String stats = router.dispatch(context("/experience stats", sessionManager)).get().getContent();
        assertTrue(stats.contains("experience stats"), stats);
        assertTrue(stats.contains("verified: 2"), stats);
        assertTrue(stats.contains("promoted: 1"), stats);

        String review = router.dispatch(context("/experience review", sessionManager)).get().getContent();
        assertTrue(review.contains("experience review"), review);
        assertTrue(review.contains("failureCount>successCount"), review);

        String archived = router.dispatch(context("/experience archive " + high.id(), sessionManager)).get().getContent();
        assertTrue(archived.contains("status: ARCHIVED"), archived);
        String restored = router.dispatch(context("/experience restore " + high.id(), sessionManager)).get().getContent();
        assertTrue(restored.contains("experience restored"), restored);
        assertTrue(restored.contains("status: VERIFIED"), restored);
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

    private static TeamEngine teamEngine(AgentCommands commands) throws Exception {
        java.lang.reflect.Field field = AgentCommands.class.getDeclaredField("teamEngine");
        field.setAccessible(true);
        return (TeamEngine) field.get(commands);
    }

    private static AgentCommands commands(SessionManager sessionManager, MemoryStore memoryStore, Path workspace) {
        return new AgentCommands(
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
    }

    private static void initGitRepo(Path workspace) throws Exception {
        git(workspace, "init");
        git(workspace, "config", "user.name", "Test");
        git(workspace, "config", "user.email", "test@example.com");
        Files.writeString(workspace.resolve("README.md"), "initial\n");
        git(workspace, "add", "README.md");
        git(workspace, "commit", "-m", "init");
    }

    private static String git(Path workspace, String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(workspace.toFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        int code = process.waitFor();
        if (code != 0) {
            throw new AssertionError("git failed: " + String.join(" ", command) + "\n" + stderr + stdout);
        }
        return stdout;
    }

    private static ExperienceEntry experience(String title, double confidence) {
        return ExperienceEntry.candidate(
                ExperienceType.TEST_POLICY,
                title,
                "Run targeted tests after changing filesystem tools.",
                "When changing filesystem tools.",
                "Command test evidence.",
                "task_summary",
                "V3.7",
                List.of("src/main/java/ricbot/tool/filesystem/WriteFileTool.java"),
                List.of("./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test"),
                confidence
        );
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
