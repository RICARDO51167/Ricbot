package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSetStatus;
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
import ricbot.domain.team.TeamWorkerResult;
import ricbot.domain.team.TeamWorkerRunner;
import ricbot.domain.team.TeamWorkerStatus;
import ricbot.domain.team.VerificationResult;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;
import ricbot.infra.config.Config;
import ricbot.integration.command.CommandRouter;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ReadFileTool;
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
    void teamCommandsOperateStateMachineAndExposeSummary(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);

        AgentCommands commands = new AgentCommands(
                sessionManager,
                memoryStore,
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
    void teamRunWorkerRunVerifierAndWorkerReportCommands(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        router.dispatch(context("/team start V4.8 real worker execution", sessionManager)).get();
        String created = router.dispatch(context("/team task explorer Explore TeamWorkerExecutor", sessionManager)).get().getContent();
        String taskId = lineValue(created, "id:");

        String worker = router.dispatch(context("/team run-worker " + taskId, sessionManager)).get().getContent();
        assertTrue(worker.contains("team worker executed"), worker);
        assertTrue(worker.contains("role: EXPLORER"), worker);
        assertTrue(worker.contains("workspacePath: " + workspace.toAbsolutePath().normalize()), worker);
        assertTrue(worker.contains("nextState: VERIFYING"), worker);

        String report = router.dispatch(context("/team worker-report " + taskId, sessionManager)).get().getContent();
        assertTrue(report.contains("team worker report"), report);
        assertTrue(report.contains("Explorer summarized"), report);

        String verifier = router.dispatch(context("/team run-verifier " + taskId, sessionManager)).get().getContent();
        assertTrue(verifier.contains("team verifier executed"), verifier);
        assertTrue(verifier.contains("role: VERIFIER"), verifier);
        assertTrue(verifier.contains("nextState:"), verifier);

        TraceStore traceStore = new TraceStore(workspace);
        String traceId = traceStore.traceIdForSession("cli:direct");
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.WORKER_FINISHED
                || event.type() == TraceEventType.VERIFIER_FINISHED), traceStore.loadEvents(traceId).toString());
    }

    @Test
    void teamToolCallUsesPolicyAwareExecutor(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "team tool-call\n");
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        ApprovalService approvalService = new ApprovalService();
        AgentCommands commands = commandsWithTools(sessionManager, memoryStore, workspace, approvalService);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        router.dispatch(context("/team start V4.10 role tool calls", sessionManager)).get();
        String explorerCreated = router.dispatch(context("/team task explorer Inspect README", sessionManager)).get().getContent();
        String explorerTaskId = lineValue(explorerCreated, "id:");

        String read = router.dispatch(context("/team tool-call " + explorerTaskId + " read_file {\"path\":\"README.md\",\"offset\":1,\"limit\":20}", sessionManager)).get().getContent();
        assertTrue(read.contains("team role tool-call"), read);
        assertTrue(read.contains("policy decision: ALLOW"), read);
        assertTrue(read.contains("team tool-call"), read);

        String denied = router.dispatch(context("/team tool-call " + explorerTaskId + " write_file {\"path\":\"blocked.txt\",\"content\":\"no\"}", sessionManager)).get().getContent();
        assertTrue(denied.contains("policy decision: DENY"), denied);
        assertTrue(denied.contains("denied: true"), denied);
        assertTrue(Files.notExists(workspace.resolve("blocked.txt")));

        String developerCreated = router.dispatch(context("/team task developer Edit README", sessionManager)).get().getContent();
        String developerTaskId = lineValue(developerCreated, "id:");
        String developerPlan = router.dispatch(context("/team run-worker " + developerTaskId, sessionManager)).get().getContent();
        assertTrue(developerPlan.contains("Developer Plan"), developerPlan);
        assertTrue(developerPlan.contains("changeSetRecommendation:"), developerPlan);
        assertEquals("team tool-call\n", Files.readString(workspace.resolve("README.md")));

        String approval = router.dispatch(context("/team tool-call " + developerTaskId + " edit_file {\"path\":\"README.md\",\"old_text\":\"team\",\"new_text\":\"policy\"}", sessionManager)).get().getContent();
        assertTrue(approval.contains("policy decision: REQUIRE_APPROVAL"), approval);
        assertTrue(approval.contains("approval requestId:"), approval);
        assertTrue(approval.contains("/change create"), approval);
        String requestId = lineValue(approval, "approval requestId:");
        assertNotNull(approvalService.find(requestId));

        String applied = router.dispatch(context("/approve " + requestId, sessionManager)).get().getContent();
        assertTrue(applied.contains("已批准并恢复执行"), applied);
        assertTrue(applied.contains("DiffReview"), applied);
        assertTrue(applied.contains("/change create"), applied);
        assertTrue(applied.contains("/team run-verifier " + developerTaskId), applied);
        assertEquals("policy tool-call\n", Files.readString(workspace.resolve("README.md")));

        String report = router.dispatch(context("/team worker-report " + explorerTaskId, sessionManager)).get().getContent();
        assertTrue(report.contains("Policy-gated role tool-call"), report);
        String developerReport = router.dispatch(context("/team worker-report " + developerTaskId, sessionManager)).get().getContent();
        assertTrue(developerReport.contains("Developer approved tool applied"), developerReport);

        String summary = router.dispatch(context("/summary", sessionManager)).get().getContent();
        assertTrue(summary.contains("Policy"), summary);
        assertTrue(summary.contains("policy-gated") || summary.contains("Policy-gated"), summary);
        assertTrue(summary.contains("Approved Tool Calls"), summary);
        assertTrue(summary.contains("ChangeSet Recommendation"), summary);

        TraceStore traceStore = new TraceStore(workspace);
        String traceId = traceStore.traceIdForSession("cli:direct");
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.POLICY_EVALUATED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.POLICY_DENIED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.POLICY_APPROVAL_REQUIRED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.DEVELOPER_TOOL_APPROVAL_REQUIRED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.DEVELOPER_TOOL_APPLIED), traceStore.loadEvents(traceId).toString());
    }

    @Test
    void teamImplementationStepCommandsPlanApplyShowAndReject(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "team step\n");
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        ApprovalService approvalService = new ApprovalService();
        AgentCommands commands = commandsWithTools(sessionManager, memoryStore, workspace, approvalService);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        router.dispatch(context("/team start V4.12 implementation steps", sessionManager)).get();
        String created = router.dispatch(context("/team task developer Replace team with policy in README.md", sessionManager)).get().getContent();
        String taskId = lineValue(created, "id:");
        router.dispatch(context("/team run-worker " + taskId, sessionManager)).get();

        String planned = router.dispatch(context("/team plan-steps " + taskId, sessionManager)).get().getContent();
        assertTrue(planned.contains("implementation steps planned"), planned);
        assertTrue(planned.contains("[READ]"), planned);
        assertTrue(planned.contains("[EDIT]"), planned);
        String readStepId = stepId(planned, "[READ]");
        String editStepId = stepId(planned, "[EDIT]");

        String listed = router.dispatch(context("/team steps " + taskId, sessionManager)).get().getContent();
        assertTrue(listed.contains(readStepId), listed);
        assertTrue(listed.contains("order="), listed);
        assertTrue(listed.contains("dependsOn="), listed);
        String next = router.dispatch(context("/team next-step " + taskId, sessionManager)).get().getContent();
        assertTrue(next.contains("next implementation step"), next);
        assertTrue(next.contains("type: READ"), next);
        String shown = router.dispatch(context("/team show-step " + readStepId, sessionManager)).get().getContent();
        assertTrue(shown.contains("type: READ"), shown);
        assertTrue(shown.contains("gate: ALLOW"), shown);

        String editBlocked = router.dispatch(context("/team apply-step " + editStepId, sessionManager)).get().getContent();
        assertTrue(editBlocked.contains("implementation step blocked"), editBlocked);
        assertTrue(editBlocked.contains("requiredActions:"), editBlocked);
        String blockedListed = router.dispatch(context("/team steps " + taskId, sessionManager)).get().getContent();
        assertTrue(blockedListed.contains("blockedReason="), blockedListed);

        String readApplied = router.dispatch(context("/team apply-step " + readStepId, sessionManager)).get().getContent();
        assertTrue(readApplied.contains("policy decision: ALLOW"), readApplied);
        assertTrue(readApplied.contains("status: APPLIED"), readApplied);
        assertTrue(readApplied.contains("team step"), readApplied);

        String editUpdated = router.dispatch(context("/team update-step " + editStepId + " {\"updateReason\":\"refresh after read\"}", sessionManager)).get().getContent();
        assertTrue(editUpdated.contains("implementation step updated"), editUpdated);
        assertTrue(editUpdated.contains("status: READY"), editUpdated);
        assertTrue(editUpdated.contains("validationErrors: none"), editUpdated);

        String editApproval = router.dispatch(context("/team apply-step " + editStepId, sessionManager)).get().getContent();
        assertTrue(editApproval.contains("policy decision: REQUIRE_APPROVAL"), editApproval);
        assertTrue(editApproval.contains("approval requestId:"), editApproval);
        String requestId = lineValue(editApproval, "approval requestId:");
        assertNotNull(approvalService.find(requestId));

        String approved = router.dispatch(context("/approve " + requestId, sessionManager)).get().getContent();
        assertTrue(approved.contains("DiffReview"), approved);
        assertEquals("policy step\n", Files.readString(workspace.resolve("README.md")));
        String editShown = router.dispatch(context("/team show-step " + editStepId, sessionManager)).get().getContent();
        assertTrue(editShown.contains("status: APPLIED"), editShown);
        String teamSessionId = lineValue(editShown, "teamSessionId:");
        String stepTimeline = router.dispatch(context("/team step-timeline " + editStepId, sessionManager)).get().getContent();
        assertTrue(stepTimeline.contains("step timeline"), stepTimeline);
        assertTrue(stepTimeline.contains("STEP_APPROVAL_REQUIRED"), stepTimeline);
        assertTrue(stepTimeline.contains("STEP_TOOL_APPLIED"), stepTimeline);
        String taskTimeline = router.dispatch(context("/team task-timeline " + taskId, sessionManager)).get().getContent();
        assertTrue(taskTimeline.contains("task step audit"), taskTimeline);
        assertTrue(taskTimeline.contains("totalAuditRecords="), taskTimeline);
        String compactTaskTimeline = router.dispatch(context("/team task-timeline " + taskId + " --compact", sessionManager)).get().getContent();
        assertTrue(compactTaskTimeline.contains("compact step audit"), compactTaskTimeline);
        assertTrue(compactTaskTimeline.contains("linkedAuditEvents="), compactTaskTimeline);
        String audit = router.dispatch(context("/team audit " + taskId, sessionManager)).get().getContent();
        assertTrue(audit.contains("task step audit"), audit);
        String compactAudit = router.dispatch(context("/team audit " + taskId + " --compact", sessionManager)).get().getContent();
        assertTrue(compactAudit.contains("compact step audit"), compactAudit);
        assertTrue(compactAudit.contains("auditHealth="), compactAudit);
        String compactJsonAudit = router.dispatch(context("/team audit " + taskId + " --compact --json", sessionManager)).get().getContent();
        assertTrue(compactJsonAudit.contains("\"auditHealth\""), compactJsonAudit);
        assertTrue(compactJsonAudit.contains("\"totalSteps\""), compactJsonAudit);
        assertTrue(compactJsonAudit.contains("\"warnings\""), compactJsonAudit);
        String jsonAudit = router.dispatch(context("/team audit " + taskId + " --json", sessionManager)).get().getContent();
        assertTrue(jsonAudit.contains("\"summary\""), jsonAudit);
        assertTrue(jsonAudit.contains("\"auditHealth\""), jsonAudit);
        String report = router.dispatch(context("/team report " + taskId, sessionManager)).get().getContent();
        assertTrue(report.contains("team task report"), report);
        assertTrue(report.contains("status: RUNNING"), report);
        assertTrue(report.contains("health: WARNING"), report);
        assertTrue(report.contains("progress:"), report);
        assertTrue(report.contains("suggestedNextActions:"), report);
        String reportJson = router.dispatch(context("/team report " + taskId + " --json", sessionManager)).get().getContent();
        assertTrue(reportJson.contains("\"status\":\"RUNNING\""), reportJson);
        assertTrue(reportJson.contains("\"health\":\"WARNING\""), reportJson);
        assertTrue(reportJson.contains("\"suggestedNextActions\""), reportJson);

        String rejectStepId = stepId(planned, "[RUN_VERIFIER]");
        String rejected = router.dispatch(context("/team reject-step " + rejectStepId, sessionManager)).get().getContent();
        assertTrue(rejected.contains("status: REJECTED"), rejected);

        String summary = router.dispatch(context("/summary", sessionManager)).get().getContent();
        assertTrue(summary.contains("Implementation Steps"), summary);
        assertTrue(summary.contains("implstep_"), summary);

        TraceStore traceStore = new TraceStore(workspace);
        String traceId = traceStore.traceIdForSession("cli:direct");
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.IMPLEMENTATION_STEP_CREATED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.IMPLEMENTATION_STEP_APPLIED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.IMPLEMENTATION_STEP_APPROVAL_REQUIRED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.IMPLEMENTATION_STEP_REJECTED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.IMPLEMENTATION_STEP_GATE_CHECKED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.IMPLEMENTATION_STEP_BLOCKED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.IMPLEMENTATION_STEP_UPDATED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.IMPLEMENTATION_STEP_READY), traceStore.loadEvents(traceId).toString());
        String teamTraceId = traceStore.traceIdForSession(teamSessionId);
        assertTrue(traceStore.loadEvents(teamTraceId).stream().anyMatch(event -> event.type() == TraceEventType.STEP_AUDIT_RECORDED), traceStore.loadEvents(teamTraceId).toString());
    }

    @Test
    void teamUpdateStepValidatesDraftBeforeApply(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "draft step\n");
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commandsWithTools(sessionManager, memoryStore, workspace, new ApprovalService());
        CommandRouter router = new CommandRouter();
        commands.register(router);

        router.dispatch(context("/team start V4.14 update steps", sessionManager)).get();
        String created = router.dispatch(context("/team task developer Update README.md", sessionManager)).get().getContent();
        String taskId = lineValue(created, "id:");
        router.dispatch(context("/team run-worker " + taskId, sessionManager)).get();
        String planned = router.dispatch(context("/team plan-steps " + taskId, sessionManager)).get().getContent();
        String editStepId = stepId(planned, "[EDIT]");

        String draftApply = router.dispatch(context("/team apply-step " + editStepId, sessionManager)).get().getContent();
        assertTrue(draftApply.contains("implementation step is DRAFT"), draftApply);
        assertTrue(draftApply.contains("validationErrors:"), draftApply);

        String invalidUpdate = router.dispatch(context("/team update-step " + editStepId + " {\"targetPath\":\"README.md\",\"updateReason\":\"still missing edit text\"}", sessionManager)).get().getContent();
        assertTrue(invalidUpdate.contains("status: DRAFT"), invalidUpdate);
        assertTrue(invalidUpdate.contains("EDIT requires oldText"), invalidUpdate);

        String shown = router.dispatch(context("/team show-step " + editStepId, sessionManager)).get().getContent();
        assertTrue(shown.contains("validationErrors:"), shown);
        assertTrue(shown.contains("EDIT requires newText"), shown);

        TraceStore traceStore = new TraceStore(workspace);
        String traceId = traceStore.traceIdForSession("cli:direct");
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.IMPLEMENTATION_STEP_UPDATED), traceStore.loadEvents(traceId).toString());
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.IMPLEMENTATION_STEP_VALIDATION_FAILED), traceStore.loadEvents(traceId).toString());
    }

    @Test
    void teamRunWorktreeCreatesTaskWorkspaceAndVerifierUsesIt(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("mvnw"), "#!/bin/sh\nprintf \"verified:%s\\n\" \"$PWD\"\n");
        git(workspace, "add", "mvnw");
        git(workspace, "commit", "-m", "add mvnw");
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String result = router.dispatch(context("/team run Implement worktree execution --worktree --verify", sessionManager)).get().getContent();

        assertTrue(result.contains("team execution"), result);
        assertTrue(result.contains("workspaceSessionId: team-implement-worktree-execution"), result);
        assertTrue(result.contains("verifierStatus: PASS"), result);
        assertTrue(result.contains(".workspaces"), result);
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
        String taskId = lineValue(result, "taskId:");
        String workspaceId = lineValue(result, "workspaceSessionId:");
        String workspacePath = lineValue(result, "workspacePath:");
        assertFalse(workspaceId.equals("none"));

        String workspaceStatus = router.dispatch(context("/workspace status", sessionManager)).get().getContent();
        assertTrue(workspaceStatus.contains(workspaceId), workspaceStatus);
        String targetedStatus = router.dispatch(context("/workspace status " + taskId, sessionManager)).get().getContent();
        assertTrue(targetedStatus.contains("workspace status " + workspaceId), targetedStatus);
        Files.createDirectories(Path.of(workspacePath).resolve("notes"));
        Files.writeString(Path.of(workspacePath).resolve("notes/index.json"), "{}\n");
        String runtimeOnlyDiff = router.dispatch(context("/workspace diff " + taskId, sessionManager)).get().getContent();
        assertTrue(runtimeOnlyDiff.contains("changedFiles: none"), runtimeOnlyDiff);
        assertTrue(runtimeOnlyDiff.contains("No diff."), runtimeOnlyDiff);
        assertFalse(runtimeOnlyDiff.contains("notes/index.json"), runtimeOnlyDiff);
        String runtimeOnlyChangeSet = router.dispatch(context("/change create " + taskId, sessionManager)).get().getContent();
        assertEquals("no user changes found", runtimeOnlyChangeSet);

        Files.writeString(Path.of(workspacePath).resolve("README.md"), "initial\npost run change\n");
        String diff = router.dispatch(context("/workspace diff " + taskId, sessionManager)).get().getContent();
        assertTrue(diff.contains("post run change"), diff);
        assertTrue(diff.contains("changedFiles: README.md"), diff);
        assertFalse(diff.contains("notes/index.json"), diff);
        String changeSet = router.dispatch(context("/change create " + taskId, sessionManager)).get().getContent();
        assertTrue(changeSet.contains("workspaceSessionId: " + workspaceId), changeSet);
        assertTrue(changeSet.contains("changedFiles: README.md"), changeSet);
        assertFalse(changeSet.contains("notes/index.json"), changeSet);
        String changeSetJson = router.dispatch(context("/change create " + taskId + " --json", sessionManager)).get().getContent();
        assertTrue(changeSetJson.contains("\"workspaceSessionId\":\"" + workspaceId + "\""), changeSetJson);
        String taskTrace = router.dispatch(context("/trace show " + taskId, sessionManager)).get().getContent();
        assertTrue(taskTrace.contains("trace timeline"), taskTrace);
        assertTrue(taskTrace.contains("taskId: " + taskId), taskTrace);
        assertTrue(taskTrace.contains("relatedWorkspace: " + workspaceId), taskTrace);
        String taskTraceJson = router.dispatch(context("/trace show " + taskId + " --json", sessionManager)).get().getContent();
        assertTrue(taskTraceJson.contains("\"taskId\":\"" + taskId + "\""), taskTraceJson);
        assertTrue(taskTraceJson.contains("\"relatedWorkspace\""), taskTraceJson);
        String discardRejected = router.dispatch(context("/workspace discard " + taskId, sessionManager)).get().getContent();
        assertTrue(discardRejected.contains("discard requires --force"), discardRejected);
        String discarded = router.dispatch(context("/workspace discard " + taskId + " --force", sessionManager)).get().getContent();
        assertTrue(discarded.contains("workspace discarded"), discarded);
        assertTrue(discarded.contains("status: DISCARDED"), discarded);
        assertFalse(Files.exists(Path.of(workspacePath).resolve("README.md")));
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
        String report = router.dispatch(context("/team report " + taskId, sessionManager)).get().getContent();
        assertTrue(report.contains("team task report"), report);
        String reportAgain = router.dispatch(context("/team report " + taskId, sessionManager)).get().getContent();
        assertEquals(report, reportAgain);
        assertFalse(reportAgain.contains("refusing to clean unsafe workspace"), reportAgain);
        assertFalse(reportAgain.toLowerCase(java.util.Locale.ROOT).contains("eval report"), reportAgain);
    }

    @Test
    void teamRunWorktreeUsesWorkerRunnerToApplyUserDiff(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("mvnw"), "#!/bin/sh\necho ok\n");
        git(workspace, "add", "mvnw");
        git(workspace, "commit", "-m", "add mvnw");
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        TeamWorkerRunner runner = (task, workspaceSession, root) -> {
            try {
                Files.writeString(root.resolve("README.md"), "initial\nteam worker applied\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return new TeamWorkerResult(TeamWorkerStatus.APPLIED, List.of("README.md"),
                    "Updated README.md", List.of("APPLY_CHANGE README.md"), "", 9, "run_fake", "");
        };
        AgentCommands commands = commands(sessionManager, memoryStore, workspace, runner);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String result = router.dispatch(context("/team run 给 README 增加一个很小的说明性修正 --worktree --verify", sessionManager)).get().getContent();

        assertTrue(result.contains("workerStatus: APPLIED"), result);
        assertTrue(result.contains("reportHealth: HEALTHY"), result);
        assertTrue(result.contains("verifierReason: structured evidence passed"), result);
        assertTrue(result.contains("structuredEvidence: team-worktree-verifier"), result);
        assertTrue(result.contains("verifierCommand: sh ./mvnw -q test"), result);
        assertTrue(result.contains("workerSummary:"), result);
        assertTrue(result.contains("toolCalls:"), result);
        assertTrue(result.contains("changedFiles: README.md"), result);
        assertFalse(result.contains("debug:allowedTools="), result);
        assertFalse(result.contains("debug:exposedTools="), result);
        String taskId = lineValue(result, "taskId:");
        String diff = router.dispatch(context("/workspace diff " + taskId, sessionManager)).get().getContent();
        assertTrue(diff.contains("README.md"), diff);
        assertTrue(diff.contains("team worker applied"), diff);
        String report = router.dispatch(context("/team report " + taskId, sessionManager)).get().getContent();
        assertTrue(report.contains("latestVerifier: PASS"), report);
        assertTrue(report.contains("structured verifier decision: structured evidence passed"), report);
        assertFalse(report.contains("no user changes produced"), report);
        String changeSet = router.dispatch(context("/change create " + taskId, sessionManager)).get().getContent();
        assertTrue(changeSet.contains("changeset created"), changeSet);
        assertTrue(changeSet.contains("changedFiles: README.md"), changeSet);
        assertFalse(changeSet.contains("notes/index.json"), changeSet);
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
    }

    @Test
    void teamSessionIdArgumentsShowFriendlyTaskIdHint(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String started = router.dispatch(context("/team start Friendly id hint", sessionManager)).get().getContent();
        String teamId = lineValue(started, "id:");

        String report = router.dispatch(context("/team report " + teamId, sessionManager)).get().getContent();
        String workspaceDiff = router.dispatch(context("/workspace diff " + teamId, sessionManager)).get().getContent();
        String changeCreate = router.dispatch(context("/change create " + teamId, sessionManager)).get().getContent();

        assertTrue(report.contains("你传入的是 teamSessionId：" + teamId), report);
        assertTrue(report.contains("/team report 需要 taskId，例如 teamtask_xxx"), report);
        assertTrue(workspaceDiff.contains("你传入的是 teamSessionId：" + teamId), workspaceDiff);
        assertTrue(workspaceDiff.contains("/workspace diff 需要 taskId 或 workspaceId"), workspaceDiff);
        assertTrue(changeCreate.contains("你传入的是 teamSessionId：" + teamId), changeCreate);
        assertTrue(changeCreate.contains("/change create 需要 taskId 或 workspaceId"), changeCreate);
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
    void teamVerifierRequiresChangeSetForActiveWorkspaceDiff(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String createdWorkspace = router.dispatch(context("/workspace create --mode worktree verifier sandbox", sessionManager)).get().getContent();
        String workspacePath = lineValue(createdWorkspace, "workspacePath:");
        Files.writeString(Path.of(workspacePath).resolve("README.md"), "initial\nneeds changeset\n");
        router.dispatch(context("/team start Verify active workspace diff", sessionManager)).get();
        String createdTask = router.dispatch(context("/team task explorer Review active workspace", sessionManager)).get().getContent();
        String taskId = lineValue(createdTask, "id:");

        String verifier = router.dispatch(context("/team run-verifier " + taskId, sessionManager)).get().getContent();
        assertTrue(verifier.contains("changeSetHint: active workspace has diff; run /change create"), verifier);
        assertTrue(verifier.contains("nextState: REVISING"), verifier);

        String report = router.dispatch(context("/team verifier-report " + taskId, sessionManager)).get().getContent();
        assertTrue(report.contains("/change create"), report);
        TraceStore traceStore = new TraceStore(workspace);
        assertTrue(traceStore.loadEvents(traceStore.traceIdForSession("cli:direct")).stream()
                .anyMatch(event -> event.type() == TraceEventType.WORKSPACE_DIFF_REQUIRES_CHANGESET), traceStore.loadEvents(traceStore.traceIdForSession("cli:direct")).toString());

        git(Path.of(workspacePath), "restore", "--", "README.md");
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
        assertTrue(last.contains("trace timeline"), last);
        assertTrue(last.contains("traceId: " + traceId), last);
        assertTrue(last.contains("CHANGESET_CREATED"), last);
        assertTrue(last.contains("changeSetId=" + changeSetId), last);

        String shown = router.dispatch(context("/trace show " + traceId, sessionManager)).get().getContent();
        assertTrue(shown.contains("trace timeline"), shown);
        assertTrue(shown.contains("status: COMPLETED"), shown);
        assertTrue(shown.contains("CHANGESET_CREATED"), shown);
        String shownJson = router.dispatch(context("/trace show " + traceId + " --json", sessionManager)).get().getContent();
        assertTrue(shownJson.contains("\"traceId\":\"" + traceId + "\""), shownJson);
        assertTrue(shownJson.contains("\"events\""), shownJson);

        String events = router.dispatch(context("/trace events " + traceId, sessionManager)).get().getContent();
        assertTrue(events.contains("trace events"), events);
        assertTrue(events.contains("CHANGESET_CREATED"), events);

        String exported = router.dispatch(context("/trace export " + traceId, sessionManager)).get().getContent();
        assertTrue(exported.contains("\"traceId\":\"" + traceId + "\""), exported);
        assertTrue(exported.contains("\"type\":\"CHANGESET_CREATED\""), exported);
    }

    @Test
    void policyCommandsShowAndCheck(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        AgentCommands commands = commands(sessionManager, memoryStore, workspace);
        CommandRouter router = new CommandRouter();
        commands.register(router);

        String shown = router.dispatch(context("/policy show", sessionManager)).get().getContent();
        assertTrue(shown.contains("role tool policy"), shown);
        assertTrue(shown.contains("EXPLORER"), shown);

        String explorer = router.dispatch(context("/policy show EXPLORER", sessionManager)).get().getContent();
        assertTrue(explorer.contains("read_file"), explorer);
        assertTrue(explorer.contains("write"), explorer);

        String denied = router.dispatch(context("/policy check EXPLORER write_file", sessionManager)).get().getContent();
        assertTrue(denied.contains("decision: DENY"), denied);
        assertTrue(denied.contains("denied: true"), denied);

        String test = router.dispatch(context("/policy check-command TESTER ./mvnw test", sessionManager)).get().getContent();
        assertTrue(test.contains("decision:"), test);
        assertTrue(test.contains("role: TESTER"), test);

        TraceStore traceStore = new TraceStore(workspace);
        String traceId = traceStore.traceIdForSession("cli:direct");
        assertTrue(traceStore.loadEvents(traceId).stream().anyMatch(event -> event.type() == TraceEventType.POLICY_DENIED
                || event.type() == TraceEventType.POLICY_EVALUATED
                || event.type() == TraceEventType.POLICY_APPROVAL_REQUIRED), traceStore.loadEvents(traceId).toString());
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
    void contextSourcesShowsTeamSources(@TempDir Path workspace) throws Exception {
        SessionManager sessionManager = new SessionManager(workspace);
        MemoryStore memoryStore = new MemoryStore(workspace);
        Session session = sessionManager.getOrCreate("cli:direct");
        PromptContextBundle bundle = new PromptContextBundle();
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

        assertTrue(sources.contains("team_context"), sources);
        assertTrue(sources.contains(".team/team_demo/whiteboard.md"), sources);
        assertTrue(sources.contains(".team/team_demo/verification.jsonl"), sources);
        assertTrue(detail.contains("team_context"), detail);
        assertTrue(detail.contains("avg_relevance"), detail);
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

    private static String stepId(String text, String typeMarker) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- implstep_") && trimmed.contains(typeMarker)) {
                return trimmed.substring(2).split("\\s+")[0];
            }
        }
        throw new AssertionError("missing step " + typeMarker + " in: " + text);
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
                "model",
                workspace,
                msg -> "cli:direct",
                key -> List.<Future<?>>of(),
                (key, reason) -> {}
        );
    }

    private static AgentCommands commands(
            SessionManager sessionManager,
            MemoryStore memoryStore,
            Path workspace,
            TeamWorkerRunner teamWorkerRunner
    ) {
        return new AgentCommands(
                sessionManager,
                memoryStore,
                "model",
                workspace,
                msg -> "cli:direct",
                key -> List.<Future<?>>of(),
                (key, reason) -> {},
                new ApprovalService(),
                null,
                teamWorkerRunner
        );
    }

    private static AgentCommands commandsWithTools(
            SessionManager sessionManager,
            MemoryStore memoryStore,
            Path workspace,
            ApprovalService approvalService
    ) {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new ReadFileTool(workspace, workspace, List.of()));
        tools.register(new WriteFileTool(workspace, workspace));
        tools.register(new EditFileTool(workspace, workspace));
        return new AgentCommands(
                sessionManager,
                memoryStore,
                "model",
                workspace,
                msg -> "cli:direct",
                key -> List.<Future<?>>of(),
                (key, reason) -> {},
                approvalService,
                tools
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
