package ricbot.domain.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.application.team.TeamReportApplicationService;
import ricbot.application.team.TeamRunApplicationService;
import ricbot.application.team.TeamSessionApplicationService;
import ricbot.application.team.TeamStepApplicationService;
import ricbot.application.team.TeamTaskApplicationService;
import ricbot.application.team.TeamToolApplicationService;
import ricbot.application.team.TeamWorkerApplicationService;
import ricbot.application.workspace.WorkspaceApplicationService;
import ricbot.domain.change.ChangeSetRenderer;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSet;
import ricbot.domain.change.GitChangeSetStatus;
import ricbot.domain.change.PendingChangeAction;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.message.OutboundMessages;
import ricbot.domain.note.NoteService;
import ricbot.domain.note.TaskNoteWriter;
import ricbot.domain.policy.PolicyDecision;
import ricbot.domain.policy.PolicyDecisionType;
import ricbot.domain.policy.PolicyAwareToolExecutor;
import ricbot.domain.policy.PolicyEngine;
import ricbot.domain.policy.PolicyRenderer;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.domain.security.ApprovalApplicationService;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.PendingToolCall;
import ricbot.domain.security.RiskAssessment;
import ricbot.domain.team.TeamArtifact;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamRole;
import ricbot.domain.team.TeamSession;
import ricbot.domain.team.TeamTask;
import ricbot.domain.team.TeamWorkerRunner;
import ricbot.domain.team.ImplementationStepGate;
import ricbot.domain.team.ImplementationStepStatus;
import ricbot.domain.team.ImplementationStepType;
import ricbot.domain.team.PendingImplementationStep;
import ricbot.domain.team.StepGateResult;
import ricbot.domain.team.StepAuditEventType;
import ricbot.domain.team.StepAuditRecord;
import ricbot.domain.team.VerificationResult;
import ricbot.domain.team.WorkerExecutionResult;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceRenderer;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.trace.TraceTimeline;
import ricbot.domain.trace.TraceViewerService;
import ricbot.domain.workspace.GitWorktreeWorkspaceBackend;
import ricbot.domain.workspace.LocalWorkspaceBackend;
import ricbot.domain.workspace.WorkspaceBackend;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceLifecycleService;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.integration.command.CommandRouter;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class AgentCommands {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SessionManager sessionManager;
    private final String model;
    private final Path workspace;
    private final Function<InboundMessage, String> sessionKeyResolver;
    private final Function<String, List<Future<?>>> activeTaskRemover;
    private final BiConsumer<String, String> sessionInterruptMarker;
    private final ApprovalService approvalService;
    private final ToolRegistry toolRegistry;
    private final TeamWorkerRunner teamWorkerRunner;
    private final TeamEngine teamEngine;
    private final TraceStore traceStore;
    private final WorkspaceApplicationService workspaceApplication;
    private final TeamSessionApplicationService teamSessions;
    private final TeamReportApplicationService teamReports;
    private final TeamTaskApplicationService teamTasks;
    private final TeamStepApplicationService teamSteps;
    private final TeamRunApplicationService teamRuns;
    private final TeamWorkerApplicationService teamWorkers;
    private final TeamToolApplicationService teamTools;

    AgentCommands(
            SessionManager sessionManager,
            String model,
            Path workspace,
            Function<InboundMessage, String> sessionKeyResolver,
            Function<String, List<Future<?>>> activeTaskRemover,
            BiConsumer<String, String> sessionInterruptMarker
    ) {
        this(sessionManager, model, workspace, sessionKeyResolver,
                activeTaskRemover, sessionInterruptMarker, new ApprovalService(), null);
    }

    AgentCommands(
            SessionManager sessionManager,
            String model,
            Path workspace,
            Function<InboundMessage, String> sessionKeyResolver,
            Function<String, List<Future<?>>> activeTaskRemover,
            BiConsumer<String, String> sessionInterruptMarker,
            ApprovalService approvalService
    ) {
        this(sessionManager, model, workspace, sessionKeyResolver,
                activeTaskRemover, sessionInterruptMarker, approvalService, null);
    }

    AgentCommands(
            SessionManager sessionManager,
            String model,
            Path workspace,
            Function<InboundMessage, String> sessionKeyResolver,
            Function<String, List<Future<?>>> activeTaskRemover,
            BiConsumer<String, String> sessionInterruptMarker,
            ApprovalService approvalService,
            ToolRegistry toolRegistry
    ) {
        this(sessionManager, model, workspace, sessionKeyResolver,
                activeTaskRemover, sessionInterruptMarker, approvalService, toolRegistry, null);
    }

    AgentCommands(
            SessionManager sessionManager,
            String model,
            Path workspace,
            Function<InboundMessage, String> sessionKeyResolver,
            Function<String, List<Future<?>>> activeTaskRemover,
            BiConsumer<String, String> sessionInterruptMarker,
            ApprovalService approvalService,
            ToolRegistry toolRegistry,
            TeamWorkerRunner teamWorkerRunner
    ) {
        this.sessionManager = sessionManager;
        this.model = model;
        this.workspace = workspace;
        this.sessionKeyResolver = sessionKeyResolver;
        this.activeTaskRemover = activeTaskRemover;
        this.sessionInterruptMarker = sessionInterruptMarker;
        this.traceStore = new TraceStore(this.workspace);
        this.approvalService = approvalService != null ? approvalService : new ApprovalService();
        this.approvalService.setTraceStore(this.traceStore);
        this.toolRegistry = toolRegistry;
        this.teamWorkerRunner = teamWorkerRunner;
        this.teamEngine = new TeamEngine(this.workspace, this.traceStore);
        this.workspaceApplication = new WorkspaceApplicationService(this.workspace, this.sessionManager, this.traceStore);
        this.teamSessions = new TeamSessionApplicationService(this.sessionManager, this.teamEngine);
        this.teamReports = new TeamReportApplicationService(this.teamEngine, this.teamSessions);
        this.teamTasks = new TeamTaskApplicationService(this.workspace, this.teamEngine, this.teamSessions);
        this.teamSteps = new TeamStepApplicationService(this.workspace, this.teamEngine, this.teamSessions, this.traceStore);
        this.teamRuns = new TeamRunApplicationService(this.workspace, this.teamEngine, this.teamWorkerRunner,
                this.teamSessions, this.workspaceApplication);
        this.teamWorkers = new TeamWorkerApplicationService(this.workspace, this.sessionManager, this.teamEngine,
                this.teamSessions, this.traceStore);
        this.teamTools = new TeamToolApplicationService(this.workspace, this.sessionManager, this.teamEngine,
                this.teamSessions, this.teamWorkers, this.toolRegistry, this.approvalService, this.traceStore);
    }

    void register(CommandRouter router) {
        router.priority("/stop", this::stop);
        router.priority("/restart", this::disabled);
        router.exact("/new", this::startNewSession);
        router.exact("/help", this::help);
        router.exact("/status", this::status);
        router.exact("/context", this::context);
        router.prefix("/context ", this::context);
        router.exact("/summary", this::summary);
        router.prefix("/summary ", this::summary);
        router.exact("/change", this::change);
        router.prefix("/change ", this::change);
        router.exact("/workspace", this::workspace);
        router.prefix("/workspace ", this::workspace);
        router.exact("/trace", this::trace);
        router.prefix("/trace ", this::trace);
        router.exact("/policy", this::policy);
        router.prefix("/policy ", this::policy);
        router.exact("/team", this::team);
        router.prefix("/team ", this::team);
        router.prefix("/approve ", this::approve);
        router.prefix("/reject ", this::reject);
    }

    private CompletableFuture<OutboundMessage> stop(CommandRouter.CommandContext ctx) {
        String sessionKey = sessionKeyResolver.apply(ctx.getMsg());
        List<Future<?>> tasks = activeTaskRemover.apply(sessionKey);

        int cancelled = 0;
        if (tasks != null) {
            for (Future<?> task : tasks) {
                if (task != null && !task.isDone() && task.cancel(true)) {
                    cancelled++;
                }
            }
        }

        if (cancelled > 0) {
            sessionInterruptMarker.accept(sessionKey, "manual_stop");
        }
        return completedReply(ctx, cancelled > 0 ? "⏹ 已停止 " + cancelled + " 个任务。" : "没有可停止的任务。");
    }

    private CompletableFuture<OutboundMessage> disabled(CommandRouter.CommandContext ctx) {
        return completedReply(ctx, "当前运行时未启用该命令。");
    }

    private CompletableFuture<OutboundMessage> startNewSession(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        session.clear();
        sessionManager.save(session);
        return completedReply(ctx, "已开始新的会话。");
    }

    private CompletableFuture<OutboundMessage> help(CommandRouter.CommandContext ctx) {
        return completedReply(ctx, "ricbot 命令：\n/new — 开始新对话\n/stop — 停止当前任务\n/summary — 查看当前任务摘要\n/team start|status|list|resume|archive|suggest|suggest-current|task|auto-verify|verifier-report|verify|events|whiteboard|abort — TeamEngine 状态机\n/workspace create|status|list|use|diff|cleanup — Local/Worktree workspace session\n/change create|status|diff|commit-message|approve|commit|rollback — GitChangeSet 工作流\n/trace last|list|show|events|export — Coding Harness trace\n/help — 查看可用命令");
    }

    private CompletableFuture<OutboundMessage> status(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        int sessionMsgCount = session != null ? session.getMessages().size() : 0;
        TaskState taskState = TaskState.fromSession(session);

        StringBuilder sb = new StringBuilder();
        sb.append("ricbot status\n");
        sb.append("model: ").append(model).append("\n");
        sb.append("workspace: ").append(workspace).append("\n");
        sb.append("session messages: ").append(sessionMsgCount).append("\n");
        sb.append("\n").append(taskState.renderStatus());
        ContextQualityReport quality = readContextQuality(session);
        if (quality != null) {
            sb.append("\n\n").append(quality.renderStatusBlock());
        }
        return completedReply(ctx, sb.toString());
    }

    private ContextQualityReport readContextQuality(Session session) {
        if (session == null || session.getMetadata() == null) {
            return null;
        }
        Object rawTrace = session.getMetadata().get(SessionRuntimeKeys.CONTEXT_TRACE_KEY);
        if (!(rawTrace instanceof Map<?, ?> trace)) {
            return null;
        }
        return ContextQualityReport.fromMap(trace.get("context_quality"));
    }

    private CompletableFuture<OutboundMessage> context(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        Object rawTrace = session != null && session.getMetadata() != null
                ? session.getMetadata().get(SessionRuntimeKeys.CONTEXT_TRACE_KEY)
                : null;
        Map<?, ?> trace = rawTrace instanceof Map<?, ?> map ? map : Map.of();
        String args = trim(ctx.getArgs()).toLowerCase();
        boolean detail = args.contains("--detail");
        boolean sources = args.contains("--sources");
        String rendered = ContextCommandRenderer.render(trace, detail, sources);
        if (sources || detail) {
            rendered = appendActiveWorkspaceSource(rendered, session);
            rendered = appendPolicySource(rendered);
        }
        return completedReply(ctx, rendered);
    }

    private CompletableFuture<OutboundMessage> summary(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        resolveActiveTeamSessionId(session);
        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);
        traceEvent(session, TraceEventType.TASK_SUMMARY_CREATED, "agent", "task summary created", Map.of(
                "changedFiles", summary.changedFiles(),
                "blockers", summary.blockers(),
                "changeSetStatus", summary.changeSetStatus(),
                "commitHash", summary.commitHash(),
                "rollbackStatus", summary.rollbackStatus()
        ), "", "", "");
        String rendered = new TaskNoteWriter(null).renderMarkdown(summary);
        String args = trim(ctx.getArgs());
        if (!args.contains("--write-note")) {
            return completedReply(ctx, rendered);
        }

        String category = optionValue(args, "--category", "tasks");
        TaskNoteWriter writer = new TaskNoteWriter(new NoteService(workspace));
        TaskNoteWriter.WriteResult result = writer.write(summary, category);
        return completedReply(ctx, "summary note written\n"
                + "id: " + result.noteId() + "\n"
                + "path: " + result.path() + "\n"
                + "category: " + result.category() + "\n\n"
                + rendered);
    }

    private CompletableFuture<OutboundMessage> trace(CommandRouter.CommandContext ctx) {
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "last" : args.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        TraceRenderer renderer = new TraceRenderer();
        TraceViewerService viewer = new TraceViewerService(workspace);
        try {
            return switch (action) {
                case "last" -> {
                    TraceTimeline timeline = viewer.lastTimeline();
                    yield completedReply(ctx, renderer.renderTimeline(timeline));
                }
                case "list" -> completedReply(ctx, renderer.renderList(traceStore.listTraces().stream()
                        .map(traceStore::summarize)
                        .toList()));
                case "show" -> {
                    String id = commandArg(args, 1);
                    TraceTimeline timeline = viewer.show(id);
                    if (containsFlag(args, "--json")) {
                        yield completedReply(ctx, MAPPER.writeValueAsString(timeline.toMap()));
                    }
                    yield completedReply(ctx, renderer.renderTimeline(timeline));
                }
                case "events" -> completedReply(ctx, renderer.renderEvents(traceStore.loadEvents(commandArg(args, 1))));
                case "export" -> completedReply(ctx, renderer.renderExport(traceStore.loadEvents(commandArg(args, 1))));
                default -> completedReply(ctx, "用法：/trace last|list|show <taskId|runId|sessionId> [--json]|events <traceId>|export <traceId>");
            };
        } catch (IllegalArgumentException e) {
            return completedReply(ctx, "trace error: " + e.getMessage());
        } catch (Exception e) {
            return completedReply(ctx, "trace error: " + e.getMessage());
        }
    }

    private CompletableFuture<OutboundMessage> policy(CommandRouter.CommandContext ctx) {
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "show" : args.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        PolicyEngine engine = new PolicyEngine(workspace);
        PolicyRenderer renderer = new PolicyRenderer();
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        try {
            return switch (action) {
                case "show" -> {
                    String roleRaw = commandArgOrBlank(args, 1);
                    if (roleRaw.isBlank()) {
                        yield completedReply(ctx, renderer.renderPolicy(engine.policy()));
                    }
                    yield completedReply(ctx, renderer.renderRole(engine.policy(), parseTeamRole(roleRaw)));
                }
                case "check" -> {
                    TeamRole role = parseTeamRole(commandArg(args, 1));
                    String toolName = commandArg(args, 2);
                    PolicyDecision decision = engine.evaluate(role, toolName, Map.of(), null);
                    tracePolicy(session, decision);
                    yield completedReply(ctx, renderer.renderDecision(decision));
                }
                case "check-command" -> {
                    TeamRole role = parseTeamRole(commandArg(args, 1));
                    String command = afterNthArg(args, 2);
                    if (command.isBlank()) {
                        throw new IllegalArgumentException("missing command");
                    }
                    PolicyDecision decision = engine.evaluateCommand(role, command, null);
                    tracePolicy(session, decision);
                    yield completedReply(ctx, renderer.renderDecision(decision));
                }
                default -> completedReply(ctx, "用法：/policy show [role]|check <role> <toolName>|check-command <role> <command>");
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return completedReply(ctx, "policy error: " + e.getMessage());
        }
    }

    private CompletableFuture<OutboundMessage> workspace(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        return completedReply(ctx, workspaceApplication.execute(session, ctx.getArgs()));
    }

    private CompletableFuture<OutboundMessage> change(CommandRouter.CommandContext ctx) {
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "status" : args.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        ChangeSetService service = new ChangeSetService(workspace);
        ChangeSetRenderer renderer = new ChangeSetRenderer();
        try {
            return switch (action) {
                case "create" -> changeCreate(ctx, service, renderer, afterCommand(args));
                case "status" -> completedReply(ctx, renderer.renderStatus(latestChangeSet(ctx, service)));
                case "diff" -> completedReply(ctx, renderer.renderDiff(latestChangeSet(ctx, service), 4_000));
                case "commit-message" -> changeCommitMessage(ctx, service, renderer);
                case "approve" -> changeApprove(ctx, service, renderer);
                case "commit" -> changeCommit(ctx, service);
                case "rollback" -> args.contains("--execute")
                        ? changeRollbackExecute(ctx, service)
                        : completedReply(ctx, renderer.renderRollback(latestChangeSet(ctx, service)));
                default -> completedReply(ctx, "用法：/change create [taskId|workspaceId] [--json]|status|diff|commit-message|approve|commit [--message \"...\"]|rollback [--execute]");
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            if ("no user changes found".equalsIgnoreCase(trim(e.getMessage()))) {
                return completedReply(ctx, "no user changes found");
            }
            return completedReply(ctx, "change error: " + e.getMessage());
        }
    }

    private CompletableFuture<OutboundMessage> changeCreate(
            CommandRouter.CommandContext ctx,
            ChangeSetService service,
            ChangeSetRenderer renderer,
            String rawArgs
    ) {
        String args = trim(rawArgs);
        boolean json = containsFlag(args, "--json");
        String targetToken = stripFlags(args, "--json");
        rejectTeamSessionIdArgument(targetToken, "/change create 需要 taskId 或 workspaceId，例如 teamtask_xxx。");
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        String teamSessionId = resolveActiveTeamSessionId(session);
        String taskId = latestTeamTaskId(teamSessionId);
        String developerTaskId = session.getMetadata() != null && session.getMetadata().get(SessionRuntimeKeys.DEVELOPER_TASK_ID_KEY) != null
                ? String.valueOf(session.getMetadata().get(SessionRuntimeKeys.DEVELOPER_TASK_ID_KEY)).trim()
                : "";
        if (!developerTaskId.isBlank()) {
            taskId = developerTaskId;
        }
        WorkspaceSessionStore workspaceStore = new WorkspaceSessionStore(workspace);
        String activeWorkspaceId = activeWorkspaceSessionId(session);
        WorkspaceSession activeWorkspace = !targetToken.isBlank()
                ? new WorkspaceLifecycleService(workspace).resolveManagedWorktree(targetToken)
                : !activeWorkspaceId.isBlank() ? workspaceStore.load(activeWorkspaceId) : null;
        if (activeWorkspace != null) {
            Object workspaceTaskId = activeWorkspace.metadata().get("taskId");
            if (workspaceTaskId != null && !String.valueOf(workspaceTaskId).trim().isBlank()) {
                taskId = String.valueOf(workspaceTaskId).trim();
            }
            Object workspaceTeamId = activeWorkspace.metadata().get("teamSessionId");
            if (workspaceTeamId != null && !String.valueOf(workspaceTeamId).trim().isBlank()) {
                teamSessionId = String.valueOf(workspaceTeamId).trim();
            }
        }
        boolean fromWorkspace = activeWorkspace != null && activeWorkspace.status() == ricbot.domain.workspace.WorkspaceSessionStatus.ACTIVE;
        GitChangeSet changeSet = fromWorkspace
                ? service.createFromWorkspace(activeWorkspace.id(), Path.of(activeWorkspace.workspacePath()), ctx.getKey(), teamSessionId, taskId)
                : service.createFromWorkingTree(ctx.getKey(), teamSessionId, taskId);
        if (!teamSessionId.isBlank()) {
            String path = ".changesets/" + changeSet.id() + "/changeset.json";
            teamEngine.recordArtifact(teamSessionId, new TeamArtifact(null, taskId, path, "ChangeSet " + changeSet.id(), "changeset", null));
        }
        storeChangeSetContext(session, changeSet, renderer);
        traceEvent(session, fromWorkspace ? TraceEventType.CHANGESET_CREATED_FROM_WORKSPACE : TraceEventType.CHANGESET_CREATED, "change", fromWorkspace ? "changeset created from workspace" : "changeset created", Map.of(
                "status", changeSet.status().name(),
                "changedFiles", changeSet.changedFiles(),
                "diffSummary", changeSet.diffSummary(),
                "workspaceSessionId", changeSet.workspaceSessionId(),
                "workspacePath", changeSet.workspacePath()
        ), changeSet.teamSessionId(), changeSet.id(), "");
        if (!changeSet.teamSessionId().isBlank() && !changeSet.taskId().isBlank()) {
            teamEngine.recordStepAudit(new StepAuditRecord(null, "", changeSet.taskId(), changeSet.teamSessionId(),
                    StepAuditEventType.STEP_CHANGESET_LINKED, "", "",
                    "ChangeSet linked to implementation task.", "", "", "", changeSet.id(), "", "", null,
                    Map.of("changedFiles", changeSet.changedFiles(), "workspaceSessionId", changeSet.workspaceSessionId())));
            storeTeamContext(session, changeSet.teamSessionId());
        }
        if (json) {
            try {
                return completedReply(ctx, MAPPER.writeValueAsString(changeSet.toMap()));
            } catch (Exception e) {
                throw new IllegalStateException("changeset json render failed: " + e.getMessage(), e);
            }
        }
        return completedReply(ctx, "changeset created\n"
                + "id: " + changeSet.id() + "\n"
                + "path: .changesets/" + changeSet.id() + "/changeset.json\n"
                + "diff: .changesets/" + changeSet.id() + "/diff.patch\n\n"
                + renderer.renderStatus(changeSet));
    }

    private CompletableFuture<OutboundMessage> changeCommitMessage(
            CommandRouter.CommandContext ctx,
            ChangeSetService service,
            ChangeSetRenderer renderer
    ) {
        GitChangeSet changeSet = latestChangeSet(ctx, service);
        if (changeSet.commitMessage().isBlank()) {
            changeSet = changeSet.withCommitMessage(service.generateCommitMessage(changeSet));
        }
        storeChangeSetContext(ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey()), changeSet, renderer);
        return completedReply(ctx, renderer.renderCommitMessage(changeSet));
    }

    private CompletableFuture<OutboundMessage> changeApprove(
            CommandRouter.CommandContext ctx,
            ChangeSetService service,
            ChangeSetRenderer renderer
    ) {
        GitChangeSet approved = service.markApproved(latestChangeSet(ctx, service).id());
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        storeChangeSetContext(session, approved, renderer);
        traceEvent(session, TraceEventType.CHANGESET_APPROVED, "change", "changeset approved", Map.of(
                "status", approved.status().name(),
                "changedFiles", approved.changedFiles()
        ), approved.teamSessionId(), approved.id(), "");
        return completedReply(ctx, "changeset approved\n" + renderer.renderStatus(approved)
                + "\n\nNo git commit was executed.");
    }

    private CompletableFuture<OutboundMessage> changeCommit(
            CommandRouter.CommandContext ctx,
            ChangeSetService service
    ) {
        String args = trim(ctx.getArgs());
        GitChangeSet changeSet = latestChangeSet(ctx, service);
        String gateFailure = commitGateFailure(service, changeSet);
        if (!gateFailure.isBlank()) {
            return completedReply(ctx, "change commit blocked\n" + gateFailure);
        }
        String message = optionQuoted(args, "--message");
        if (message.isBlank()) {
            message = !changeSet.commitMessage().isBlank() ? changeSet.commitMessage() : service.generateCommitMessage(changeSet);
        }
        RiskAssessment assessment = RiskAssessment.of(
                CommandRiskLevel.HIGH,
                List.of("git commit changes persistent repository history", "requires explicit user approval"),
                "git commit",
                "change_commit",
                changeSet.changedFiles()
        );
        PendingChangeAction action = PendingChangeAction.create(
                null,
                PendingChangeAction.ActionType.COMMIT,
                changeSet.id(),
                List.of("git add -- " + String.join(" ", changeSet.changedFiles()), "git commit -m " + abbreviate(message, 120)),
                message,
                assessment
        );
        ApprovalRequest request = approvalService.createChangeActionRequest(assessment, action);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        traceEvent(session, TraceEventType.CHANGESET_COMMIT_REQUESTED, "change", "changeset commit requested", Map.of(
                "commitMessage", message,
                "commands", action.commands()
        ), changeSet.teamSessionId(), changeSet.id(), request.requestId());
        return completedReply(ctx, "change commit requires approval\n"
                + "requestId: " + request.requestId() + "\n"
                + "riskLevel: " + assessment.riskLevel() + "\n"
                + "changeSetId: " + changeSet.id() + "\n"
                + "commitMessage:\n" + message + "\n\n"
                + "Run: /approve " + request.requestId());
    }

    private CompletableFuture<OutboundMessage> changeRollbackExecute(
            CommandRouter.CommandContext ctx,
            ChangeSetService service
    ) {
        GitChangeSet changeSet = latestChangeSet(ctx, service);
        if (changeSet.rollbackCommands().isEmpty()) {
            return completedReply(ctx, "change rollback blocked\nchangeset has no rollback commands: " + changeSet.id());
        }
        RiskAssessment assessment = RiskAssessment.of(
                CommandRiskLevel.HIGH,
                List.of("rollback modifies or removes working tree files", "requires explicit user approval"),
                String.join("; ", changeSet.rollbackCommands()),
                "change_rollback",
                changeSet.changedFiles()
        );
        PendingChangeAction action = PendingChangeAction.create(
                null,
                PendingChangeAction.ActionType.ROLLBACK,
                changeSet.id(),
                changeSet.rollbackCommands(),
                "",
                assessment
        );
        ApprovalRequest request = approvalService.createChangeActionRequest(assessment, action);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        traceEvent(session, TraceEventType.CHANGESET_ROLLBACK_REQUESTED, "change", "changeset rollback requested", Map.of(
                "commands", changeSet.rollbackCommands()
        ), changeSet.teamSessionId(), changeSet.id(), request.requestId());
        return completedReply(ctx, "change rollback requires approval\n"
                + "requestId: " + request.requestId() + "\n"
                + "riskLevel: " + assessment.riskLevel() + "\n"
                + "changeSetId: " + changeSet.id() + "\n"
                + "commands:\n- " + String.join("\n- ", changeSet.rollbackCommands()) + "\n\n"
                + "Run: /approve " + request.requestId());
    }

    private String commitGateFailure(ChangeSetService service, GitChangeSet changeSet) {
        if (changeSet == null) {
            return "No ChangeSet found. Run /change create first.";
        }
        if (changeSet.status() != GitChangeSetStatus.APPROVED) {
            return "ChangeSet must be APPROVED before commit. Current status: " + changeSet.status();
        }
        if (!"PASS".equalsIgnoreCase(changeSet.verifierStatus())) {
            return "ChangeSet must have verifierStatus=PASS before commit. Current verifierStatus: "
                    + (changeSet.verifierStatus().isBlank() ? "(none)" : changeSet.verifierStatus());
        }
        if (changeSet.changedFiles().isEmpty()) {
            return "ChangeSet has no changed files.";
        }
        if (!service.changedFilesStillPresent(changeSet)) {
            return "Working tree no longer contains all ChangeSet files. Re-run /change create.";
        }
        return "";
    }

    private GitChangeSet latestChangeSet(CommandRouter.CommandContext ctx, ChangeSetService service) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        String id = session.getMetadata() != null && session.getMetadata().get(SessionRuntimeKeys.CHANGESET_ID_KEY) != null
                ? String.valueOf(session.getMetadata().get(SessionRuntimeKeys.CHANGESET_ID_KEY))
                : "";
        GitChangeSet changeSet = !id.isBlank() ? service.load(id) : null;
        if (changeSet == null) {
            changeSet = service.latest();
        }
        if (changeSet == null) {
            throw new IllegalStateException("no changeset found. Run /change create first");
        }
        return changeSet;
    }

    private void storeChangeSetContext(Session session, GitChangeSet changeSet, ChangeSetRenderer renderer) {
        if (session == null || changeSet == null) {
            return;
        }
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_ID_KEY, changeSet.id());
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_SUMMARY_KEY, renderer.summaryLine(changeSet));
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_STATUS_KEY, changeSet.status().name());
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_COMMIT_HASH_KEY, changeSet.commitHash());
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_ROLLBACK_STATUS_KEY, changeSet.rollbackStatus());
        sessionManager.save(session);
    }

    private void recordChangeSetTeamArtifact(GitChangeSet changeSet, String action) {
        if (changeSet == null || changeSet.teamSessionId().isBlank()) {
            return;
        }
        String path = ".changesets/" + changeSet.id() + "/changeset.json";
        teamEngine.recordArtifact(changeSet.teamSessionId(), new TeamArtifact(
                null,
                changeSet.taskId(),
                path,
                "ChangeSet " + changeSet.id() + " " + action + " status=" + changeSet.status(),
                "changeset",
                null
        ));
    }

    private TraceEvent traceEvent(
            Session session,
            TraceEventType type,
            String actor,
            String message,
            Map<String, Object> payload,
            String teamSessionId,
            String changeSetId,
            String approvalRequestId
    ) {
        if (traceStore == null) {
            return null;
        }
        try {
            String sessionId = session != null ? session.getKey() : "";
            String traceId = traceStore.traceIdForSession(sessionId);
            TraceEvent event = traceStore.append(new TraceEvent(
                    traceId,
                    null,
                    "",
                    sessionId,
                    teamSessionId,
                    changeSetId,
                    approvalRequestId,
                    type,
                    actor,
                    message,
                    payload,
                    null,
                    null
            ));
            if (session != null && event != null) {
                session.getMetadata().put(SessionRuntimeKeys.TRACE_ID_KEY, event.traceId());
                session.getMetadata().put(SessionRuntimeKeys.TRACE_SUMMARY_KEY, new TraceRenderer().renderSummary(traceStore.summarize(event.traceId()), traceStore.loadEvents(event.traceId())));
                sessionManager.save(session);
            }
            return event;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String latestTeamTaskId(String teamSessionId) {
        if (teamSessionId == null || teamSessionId.isBlank()) {
            return "";
        }
        TeamSession session = teamEngine.findSession(teamSessionId);
        if (session == null || session.tasks().isEmpty()) {
            return "";
        }
        return session.tasks().get(session.tasks().size() - 1).id();
    }

    private CompletableFuture<OutboundMessage> team(CommandRouter.CommandContext ctx) {
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "status" : args.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        try {
            return switch (action) {
                case "start", "status", "list", "resume", "archive", "suggest", "suggest-current",
                        "events", "whiteboard", "abort" -> completedReply(ctx,
                        teamSessions.execute(session, action, afterCommand(args)));
                case "run" -> completedReply(ctx, teamRuns.run(session, afterCommand(args)));
                case "run-worker", "run-verifier" -> completedReply(ctx,
                        teamWorkers.execute(session, action, afterCommand(args)));
                case "worker-report", "report", "verifier-report", "step-timeline", "task-timeline", "audit" ->
                        completedReply(ctx, teamReports.execute(session, action, afterCommand(args)));
                case "tool-call", "apply-step" -> completedReply(ctx,
                        teamTools.execute(session, action, afterCommand(args)));
                case "plan-steps", "steps", "show-step", "next-step", "update-step", "reject-step" ->
                        completedReply(ctx, teamSteps.execute(session, action, afterCommand(args)));
                case "auto-verify", "task", "verify" -> completedReply(ctx,
                        teamTasks.execute(session, action, afterCommand(args)));
                default -> completedReply(ctx, "用法：/team start <goal>|status|list|resume <sessionId>|archive <sessionId>|suggest <goal>|suggest-current|run <task> [--worktree] [--verify]|task <role> <goal>|run-worker <taskId>|run-verifier <taskId>|worker-report <taskId>|report <taskId>|tool-call <taskId> <toolName> <jsonArgs>|plan-steps <taskId>|steps <taskId>|show-step <stepId>|next-step <taskId>|update-step <stepId> <jsonUpdate>|apply-step <stepId>|reject-step <stepId>|step-timeline <stepId>|task-timeline <taskId>|audit <taskId>|auto-verify <taskId>|verifier-report <taskId>|verify <taskId> pass|reject|needs-human <reason>|events|whiteboard|abort <taskId>");
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return completedReply(ctx, "team error: " + e.getMessage());
        }
    }

    private void rejectTeamSessionIdArgument(String value, String commandHint) {
        String id = value != null ? value.trim() : "";
        if (id.startsWith("team_") && !id.startsWith("teamtask_")) {
            throw new IllegalArgumentException("你传入的是 teamSessionId：" + id + "。\n"
                    + commandHint + "\n"
                    + "请使用最近输出中的 taskId；也可以用 /trace show " + id + " 查看相关事件。");
        }
    }

    private TeamRole parseTeamRole(String raw) {
        try {
            return TeamRole.valueOf(trim(raw).replace('-', '_').toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("unknown team role: " + raw);
        }
    }

    private String activeWorkspaceSessionId(Session session) {
        if (session == null || session.getMetadata() == null) return "";
        Object raw = session.getMetadata().get(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY);
        return raw != null ? String.valueOf(raw).trim() : "";
    }

    private WorkerExecutionResult roleToolCallReport(
            TeamTask task,
            PolicyAwareToolExecutor.PolicyToolResult result,
            WorkspaceSession workspaceSession
    ) {
        PolicyDecision decision = result.decision();
        String workspacePath = workspaceSession != null && !workspaceSession.workspacePath().isBlank()
                ? workspaceSession.workspacePath()
                : workspace.toString();
        List<String> findings = new java.util.ArrayList<>();
        findings.add("tool=" + decision.toolName());
        findings.add("decision=" + decision.decisionType());
        if (result.executed()) {
            findings.add("tool result=" + abbreviate(result.resultSummary(), 240));
        }
        List<String> risks = new java.util.ArrayList<>();
        if (decision.denied()) {
            risks.add("policy denied tool execution");
        }
        if (decision.requiresApproval()) {
            risks.add("approval required before execution");
        }
        if (task.role() == TeamRole.DEVELOPER && workspaceSession == null) {
            risks.add("local workspace warning: create a worktree with /workspace create --mode worktree before editing shared code");
        }
        String changeSetRecommendation = task.role() == TeamRole.DEVELOPER
                ? "After approved edit/write tool calls, run /change create, then /team run-verifier " + task.id() + ", then /summary."
                : "";
        List<String> nextActions = task.role() == TeamRole.DEVELOPER
                ? List.of("/change create", "/team run-verifier " + task.id(), "/summary")
                : List.of();
        List<String> policySummary = List.of(
                "role=" + decision.role(),
                "tool=" + decision.toolName(),
                "decision=" + decision.decisionType(),
                "requiresApproval=" + decision.requiresApproval(),
                "denied=" + decision.denied(),
                "requestId=" + result.approvalRequestId(),
                "reasons=" + String.join(",", decision.reasons())
        );
        String status = decision.denied()
                ? "DENIED"
                : decision.requiresApproval()
                ? "APPROVAL_REQUIRED"
                : result.executed() ? "EXECUTED" : "SKIPPED";
        return new WorkerExecutionResult(
                task.id(),
                task.sessionId(),
                task.role(),
                task.goal(),
                workspacePath,
                teamEngine.whiteboard(task.sessionId()).readSummary(),
                List.of(),
                List.of(),
                "Policy-gated role tool-call " + decision.toolName() + " -> " + decision.decisionType(),
                findings,
                risks,
                List.of(),
                List.of(new TeamArtifact(
                        null,
                        task.id(),
                        ".team/" + task.sessionId() + "/workers.jsonl",
                        "Policy-gated tool call for " + task.id(),
                        "role_tool_call",
                        null
                )),
                policySummary,
                List.of(),
                decision.requiresApproval() ? List.of(decision.toolName() + " requires approval requestId=" + result.approvalRequestId()) : List.of(),
                nextActions,
                changeSetRecommendation,
                result.executed() ? 0.72d : 0.45d,
                status,
                null
        );
    }

    private String renderPolicyToolResult(PolicyAwareToolExecutor.PolicyToolResult result, WorkerExecutionResult report) {
        PolicyDecision decision = result.decision();
        return "team role tool-call\n"
                + "taskId: " + report.taskId() + "\n"
                + "role: " + decision.role() + "\n"
                + "toolName: " + decision.toolName() + "\n"
                + "workspacePath: " + report.workspacePath() + "\n"
                + "policy decision: " + decision.decisionType() + "\n"
                + "reasons: " + renderListInline(decision.reasons()) + "\n"
                + "requiresApproval: " + decision.requiresApproval() + "\n"
                + "denied: " + decision.denied() + "\n"
                + (!result.approvalRequestId().isBlank() ? "approval requestId: " + result.approvalRequestId() + "\n" : "")
                + "tool result: " + (result.resultSummary().isBlank() ? "none" : result.resultSummary()) + "\n"
                + (report.risks().stream().anyMatch(risk -> risk.startsWith("local workspace warning"))
                ? "warning: local workspace is active; consider /workspace create --mode worktree <goal>\n"
                : "")
                + (!report.changeSetRecommendation().isBlank() ? "next: " + report.changeSetRecommendation() + "\n" : "")
                + "status: " + report.status();
    }

    private PendingImplementationStep requireImplementationStep(String stepId) {
        PendingImplementationStep step = teamEngine.findImplementationStep(stepId);
        if (step == null) {
            throw new IllegalArgumentException("implementation step not found: " + stepId);
        }
        return step;
    }

    private String renderImplementationStepDetail(PendingImplementationStep step) {
        return "implementation step\n"
                + "id: " + step.id() + "\n"
                + "taskId: " + step.taskId() + "\n"
                + "teamSessionId: " + step.teamSessionId() + "\n"
                + "role: " + step.role() + "\n"
                + "type: " + step.type() + "\n"
                + "status: " + step.status() + "\n"
                + "targetPath: " + (step.targetPath().isBlank() ? "none" : step.targetPath()) + "\n"
                + "command: " + (step.command().isBlank() ? "none" : step.command()) + "\n"
                + "oldText: " + (step.oldText().isBlank() ? "none" : abbreviate(step.oldText(), 120)) + "\n"
                + "newText: " + (step.newText().isBlank() ? "none" : abbreviate(step.newText(), 120)) + "\n"
                + "riskLevel: " + step.riskLevel() + "\n"
                + "requiresApproval: " + step.requiresApproval() + "\n"
                + "orderIndex: " + step.orderIndex() + "\n"
                + "dependsOn: " + (step.dependsOnStepIds().isEmpty() ? "none" : String.join(", ", step.dependsOnStepIds())) + "\n"
                + "unblocks: " + (step.unblocksStepIds().isEmpty() ? "none" : String.join(", ", step.unblocksStepIds())) + "\n"
                + "blockedBy: " + (step.blockedBy().isEmpty() ? "none" : String.join(", ", step.blockedBy())) + "\n"
                + "blockedReason: " + (step.blockedReason().isBlank() ? "none" : step.blockedReason()) + "\n"
                + "qualityGate: " + (step.qualityGate().isBlank() ? "none" : step.qualityGate()) + "\n"
                + "requiredBeforeApply: " + (step.requiredBeforeApply().isEmpty() ? "none" : String.join("; ", step.requiredBeforeApply())) + "\n"
                + "validationErrors: " + (step.validationErrors().isEmpty() ? "none" : String.join("; ", step.validationErrors())) + "\n"
                + "lastUpdatedBy: " + (step.lastUpdatedBy().isBlank() ? "none" : step.lastUpdatedBy()) + "\n"
                + "updateReason: " + (step.updateReason().isBlank() ? "none" : step.updateReason()) + "\n"
                + "reason: " + step.reason();
    }

    private String renderStepGate(StepGateResult gate) {
        if (gate == null) {
            return "gate: unknown";
        }
        return "gate: " + (gate.allowed() ? "ALLOW" : "BLOCKED") + "\n"
                + "reasons: " + renderListInline(gate.reasons()) + "\n"
                + "requiredActions: " + renderListInline(gate.requiredActions()) + "\n"
                + "nextSuggestedCommand: " + (gate.nextSuggestedCommand().isBlank() ? "none" : gate.nextSuggestedCommand());
    }

    private String toolNameForStep(PendingImplementationStep step) {
        return switch (step.type()) {
            case READ -> "read_file";
            case EDIT -> "edit_file";
            case WRITE -> "write_file";
            case EXEC_TEST -> "exec";
            default -> throw new IllegalArgumentException("step is not tool-backed: " + step.type());
        };
    }

    private Map<String, Object> stepArgs(PendingImplementationStep step) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        switch (step.type()) {
            case READ -> {
                args.put("path", step.targetPath());
                args.put("offset", 1);
                args.put("limit", 200);
            }
            case EDIT -> {
                args.put("path", step.targetPath());
                args.put("old_text", step.oldText());
                args.put("new_text", step.newText());
                args.put("replace_all", false);
            }
            case WRITE -> {
                args.put("path", step.targetPath());
                args.put("content", step.newText());
            }
            case EXEC_TEST -> args.put("command", step.command());
            default -> {
            }
        }
        return args;
    }

    private void traceImplementationStep(Session session, TraceEventType type, PendingImplementationStep step) {
        if (step == null) {
            return;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("stepId", step.id());
        payload.put("taskId", step.taskId());
        payload.put("teamSessionId", step.teamSessionId());
        payload.put("type", step.type().name());
        payload.put("targetPath", step.targetPath());
        payload.put("command", step.command());
        payload.put("status", step.status().name());
        payload.put("orderIndex", step.orderIndex());
        payload.put("blockedBy", step.blockedBy());
        payload.put("blockedReason", step.blockedReason());
        payload.put("requiredBeforeApply", step.requiredBeforeApply());
        payload.put("lastUpdatedBy", step.lastUpdatedBy());
        payload.put("updateReason", step.updateReason());
        payload.put("validationErrors", step.validationErrors());
        traceEvent(session, type, "team", "implementation step lifecycle", payload, step.teamSessionId(), "", "");
    }

    private void traceImplementationStepGate(Session session, TraceEventType type, PendingImplementationStep step, StepGateResult gate) {
        if (step == null || gate == null) {
            return;
        }
        traceEvent(session, type, "team", "implementation step gate", Map.of(
                "stepId", step.id(),
                "taskId", step.taskId(),
                "teamSessionId", step.teamSessionId(),
                "type", step.type().name(),
                "status", step.status().name(),
                "reasons", gate.reasons(),
                "requiredActions", gate.requiredActions(),
                "nextSuggestedCommand", gate.nextSuggestedCommand()
        ), step.teamSessionId(), "", "");
    }

    private void tracePolicy(Session session, PolicyDecision decision) {
        if (decision == null) {
            return;
        }
        TraceEventType type = decision.decisionType() == PolicyDecisionType.DENY
                ? TraceEventType.POLICY_DENIED
                : decision.decisionType() == PolicyDecisionType.REQUIRE_APPROVAL
                ? TraceEventType.POLICY_APPROVAL_REQUIRED
                : TraceEventType.POLICY_EVALUATED;
        traceEvent(session, type, "policy", "policy evaluated", Map.of(
                "role", decision.role().name(),
                "toolName", decision.toolName(),
                "decisionType", decision.decisionType().name(),
                "reasons", decision.reasons(),
                "riskLevel", decision.riskLevel().name(),
                "requiresApproval", decision.requiresApproval(),
                "denied", decision.denied()
        ), "", "", "");
    }

    private String renderListInline(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join("; ", values);
    }

    private String stringArg(Map<String, Object> map, String key) {
        Object value = map != null ? map.get(key) : null;
        return value != null ? String.valueOf(value).trim() : "";
    }

    private List<String> changedFilesFromApprovedArgs(Map<String, Object> args) {
        String path = stringArg(args, "path");
        return path.isBlank() ? List.of() : List.of(path);
    }

    private String requireActiveTeamSessionId(Session session) {
        return teamSessions.requireActiveSessionId(session);
    }

    private String resolveActiveTeamSessionId(Session session) {
        return teamSessions.resolveActiveSessionId(session);
    }

    private void storeTeamContext(Session session, String teamSessionId) {
        teamSessions.storeContext(session, teamSessionId);
    }

    private CompletableFuture<OutboundMessage> approve(CommandRouter.CommandContext ctx) {
        String requestId = trim(ctx.getArgs()).split("\\s+")[0];
        ApprovalApplicationService.ApprovalActionResult approvalResult;
        try {
            approvalResult = new ApprovalApplicationService(approvalService, toolRegistry, workspace).approveAndExecute(requestId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return completedReply(ctx, "无法处理审批请求：" + requestId + "\n" + e.getMessage());
        }
        if (!approvalResult.found()) {
            return completedReply(ctx, "未找到审批请求：" + requestId);
        }
        ApprovalRequest request = approvalResult.request();
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        traceEvent(session, TraceEventType.APPROVAL_APPROVED, "approval", "approval approved", Map.of(
                "status", request.status().name(),
                "hasChangeAction", request.pendingChangeAction() != null,
                "hasToolCall", request.pendingToolCall() != null
        ), "", request.pendingChangeAction() != null ? request.pendingChangeAction().changeSetId() : "", request.requestId());
        if ("CHANGE_ACTION".equals(approvalResult.executionType())) {
            PendingChangeAction action = approvalResult.pendingChangeAction();
            GitChangeSet result = approvalResult.changeSet();
            ChangeSetRenderer renderer = new ChangeSetRenderer();
            storeChangeSetContext(session, result, renderer);
            recordChangeSetTeamArtifact(result, action.actionType().name().toLowerCase(java.util.Locale.ROOT));
            TraceEventType eventType = action.actionType() == PendingChangeAction.ActionType.COMMIT
                    ? TraceEventType.CHANGESET_COMMITTED
                    : TraceEventType.CHANGESET_ROLLED_BACK;
            traceEvent(session, eventType, "change", "changeset action executed", Map.of(
                    "action", action.actionType().name(),
                    "status", result.status().name(),
                    "commitHash", result.commitHash(),
                    "rollbackStatus", result.rollbackStatus()
            ), result.teamSessionId(), result.id(), request.requestId());
            String actionResult = action.actionType() == PendingChangeAction.ActionType.COMMIT
                    ? "commitHash: " + result.commitHash()
                    : "rollbackStatus: " + result.rollbackStatus();
            return completedReply(ctx, "已批准并执行变更动作：" + request.requestId()
                    + "\naction: " + action.actionType()
                    + "\nchangeSetId: " + result.id()
                    + "\nstatus: " + result.status()
                    + "\n" + actionResult);
        }
        if ("TOOL_CALL".equals(approvalResult.executionType())) {
            PendingToolCall pendingToolCall = approvalResult.pendingToolCall();
            Object result = approvalResult.executionResult();
            String developerHint = recordApprovedDeveloperToolCall(session, pendingToolCall, result, request.requestId());
            return completedReply(ctx, "已批准并恢复执行：" + request.requestId()
                    + "\ntool: " + pendingToolCall.toolName()
                    + "\n\n" + String.valueOf(result)
                    + developerHint);
        }
        return completedReply(ctx, "已批准审批请求：" + request.requestId()
                + "\nstatus: " + request.status()
                + "\n" + approvalResult.message());
    }

    private String recordApprovedDeveloperToolCall(
            Session session,
            PendingToolCall pendingToolCall,
            Object result,
            String requestId
    ) {
        Map<String, Object> arguments = pendingToolCall != null ? pendingToolCall.arguments() : Map.of();
        String role = stringArg(arguments, "__role");
        String taskId = stringArg(arguments, "__task_id");
        if (!"DEVELOPER".equalsIgnoreCase(role) || taskId.isBlank()) {
            return "";
        }
        TeamTask task = teamEngine.findTask(taskId);
        if (task == null) {
            return "";
        }
        String workspaceSessionId = stringArg(arguments, "__workspace_session_id");
        String workspacePath = stringArg(arguments, "__workspace_path");
        String stepId = stringArg(arguments, "__implementation_step_id");
        if (workspacePath.isBlank()) {
            workspacePath = workspace.toString();
        }
        String summary = abbreviate(String.valueOf(result), 420);
        WorkerExecutionResult report = new WorkerExecutionResult(
                task.id(),
                task.sessionId(),
                task.role(),
                task.goal(),
                workspacePath,
                teamEngine.whiteboard(task.sessionId()).readSummary(),
                List.of(),
                List.of(),
                "Developer approved tool applied: " + pendingToolCall.toolName(),
                List.of("tool=" + pendingToolCall.toolName(), "tool result=" + summary),
                List.of(),
                List.of(),
                List.of(new TeamArtifact(
                        null,
                        task.id(),
                        ".team/" + task.sessionId() + "/workers.jsonl",
                        "Developer tool applied for " + task.id(),
                        "developer_tool_call",
                        null
                )),
                List.of("role=DEVELOPER", "tool=" + pendingToolCall.toolName(), "decision=APPROVED", "requestId=" + requestId),
                List.of(),
                List.of(pendingToolCall.toolName() + " approved requestId=" + requestId),
                List.of("/change create", "/team run-verifier " + task.id(), "/summary"),
                "Approved changes were applied. Run /change create, then /team run-verifier " + task.id() + ".",
                0.74d,
                "APPLIED",
                null
        );
        teamEngine.recordRoleToolCall(task.id(), report);
        if (!stepId.isBlank()) {
            PendingImplementationStep applied = teamEngine.applyImplementationStep(stepId);
            teamEngine.recordStepAudit(new StepAuditRecord(null, applied.id(), applied.taskId(), applied.teamSessionId(),
                    StepAuditEventType.STEP_APPROVED, "", applied.status().name(),
                    "Implementation step approval consumed.", requestId, pendingToolCall.toolName(), "",
                    "", "", "", null, Map.of()));
            teamEngine.recordStepAudit(new StepAuditRecord(null, applied.id(), applied.taskId(), applied.teamSessionId(),
                    StepAuditEventType.STEP_TOOL_APPLIED, "", applied.status().name(),
                    "Approved tool call applied.", requestId, pendingToolCall.toolName(), summary,
                    "", "", "", null, Map.of("changedFiles", changedFilesFromApprovedArgs(arguments))));
            traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_APPLIED, applied);
        }
        storeTeamContext(session, task.sessionId());
        traceEvent(session, TraceEventType.DEVELOPER_TOOL_APPLIED, "developer", "developer approved tool applied", Map.of(
                "taskId", task.id(),
                "teamSessionId", task.sessionId(),
                "toolName", pendingToolCall.toolName(),
                "requestId", requestId,
                "workspaceSessionId", workspaceSessionId,
                "workspacePath", workspacePath,
                "stepId", stepId,
                "changedFiles", changedFilesFromApprovedArgs(arguments)
        ), task.sessionId(), "", requestId);
        return "\n\nnext: /change create\nnext: /team run-verifier " + task.id() + "\nnext: /summary";
    }

    private CompletableFuture<OutboundMessage> reject(CommandRouter.CommandContext ctx) {
        String requestId = trim(ctx.getArgs()).split("\\s+")[0];
        ApprovalApplicationService.ApprovalActionResult result =
                new ApprovalApplicationService(approvalService, toolRegistry, workspace).reject(requestId);
        if (!result.found()) {
            return completedReply(ctx, "未找到审批请求：" + requestId);
        }
        ApprovalRequest request = result.request();
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        traceEvent(session, TraceEventType.APPROVAL_REJECTED, "approval", "approval rejected", Map.of(
                "status", request.status().name()
        ), "", "", request.requestId());
        return completedReply(ctx, "已拒绝审批请求：" + request.requestId() + "\nstatus: " + request.status());
    }

    private String afterCommand(String args) {
        String value = trim(args);
        int firstSpace = value.indexOf(' ');
        return firstSpace >= 0 ? value.substring(firstSpace + 1).trim() : "";
    }

    private List<String> contextSourcePaths(Session session) {
        Object rawTrace = session != null && session.getMetadata() != null
                ? session.getMetadata().get(SessionRuntimeKeys.CONTEXT_TRACE_KEY)
                : null;
        if (!(rawTrace instanceof Map<?, ?> trace)) {
            return List.of();
        }
        Map<?, ?> budget = trace.get("prompt_context_budget") instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> sources = budget.get("sources") instanceof Map<?, ?> map ? map : Map.of();
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (Object rawRows : sources.values()) {
            if (!(rawRows instanceof List<?> rows)) {
                continue;
            }
            for (Object row : rows) {
                if (row instanceof Map<?, ?> source) {
                    Object rawPath = source.get("path");
                    String path = rawPath != null ? String.valueOf(rawPath) : "";
                    if (!path.isBlank() && !out.contains(path)) {
                        out.add(path);
                    }
                }
            }
        }
        return out.stream().limit(10).toList();
    }

    private String appendActiveWorkspaceSource(String rendered, Session session) {
        if (session == null || session.getMetadata() == null) {
            return rendered;
        }
        String id = activeWorkspaceSessionId(session);
        String source = String.valueOf(session.getMetadata().getOrDefault(SessionRuntimeKeys.WORKSPACE_SOURCE_KEY, ""));
        if (id.isBlank() || source.isBlank() || rendered.contains(source)) {
            return rendered;
        }
        String prefix = rendered.startsWith("暂无 context trace") ? "ricbot context\n\ntop sources" : rendered;
        return prefix + "\nworkspace_session\n- workspace id=" + id + " path=" + source + " label=active workspace session";
    }

    private String appendPolicySource(String rendered) {
        String source = new PolicyEngine(workspace).policy().source();
        if (rendered.contains(source)) {
            return rendered;
        }
        String prefix = rendered.startsWith("暂无 context trace") ? "ricbot context\n\ntop sources" : rendered;
        return prefix + "\npolicy\n- role_tool_policy path=" + source + " label=active policy source";
    }

    private CompletableFuture<OutboundMessage> completedReply(CommandRouter.CommandContext ctx, String content) {
        return CompletableFuture.completedFuture(OutboundMessages.of(
                ctx.getMsg().getChannel(),
                ctx.getMsg().getChatId(),
                content
        ));
    }

    private static int parseInt(String s, int def) {
        try {
            return s != null ? Integer.parseInt(s) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String optionValue(String args, String option, String def) {
        String[] parts = trim(args).split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (option.equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return def;
    }

    private static String optionQuoted(String args, String option) {
        String value = trim(args);
        int index = value.indexOf(option);
        if (index < 0) {
            return "";
        }
        int start = index + option.length();
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        if (start >= value.length()) {
            return "";
        }
        char first = value.charAt(start);
        if (first == '"' || first == '\'') {
            int end = value.indexOf(first, start + 1);
            return end > start ? value.substring(start + 1, end).trim() : value.substring(start + 1).trim();
        }
        return value.substring(start).trim();
    }

    private static Map<String, Object> parseJsonArgs(String raw) {
        try {
            return MAPPER.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("jsonArgs must be a JSON object: " + e.getMessage());
        }
    }

    private static String abbreviate(String value, int maxChars) {
        String safe = value != null ? value.trim().replaceAll("\\s+", " ") : "";
        return safe.length() <= maxChars ? safe : safe.substring(0, Math.max(0, maxChars)) + "...";
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static String commandArg(String args, int index) {
        String[] parts = trim(args).split("\\s+");
        if (parts.length <= index || parts[index].isBlank()) {
            throw new IllegalArgumentException("missing id");
        }
        return parts[index];
    }

    private static boolean containsFlag(String args, String flag) {
        String expected = flag != null ? flag.trim() : "";
        if (expected.isBlank()) {
            return false;
        }
        for (String part : trim(args).split("\\s+")) {
            if (expected.equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String stripFlags(String args, String... flags) {
        java.util.Set<String> flagSet = new java.util.HashSet<>();
        for (String flag : flags != null ? flags : new String[0]) {
            if (flag != null && !flag.isBlank()) {
                flagSet.add(flag.toLowerCase(java.util.Locale.ROOT));
            }
        }
        List<String> parts = new ArrayList<>();
        for (String part : trim(args).split("\\s+")) {
            if (!part.isBlank() && !flagSet.contains(part.toLowerCase(java.util.Locale.ROOT))) {
                parts.add(part);
            }
        }
        return String.join(" ", parts).trim();
    }

    private static String commandArgOrBlank(String args, int index) {
        String[] parts = trim(args).split("\\s+");
        return parts.length > index ? parts[index] : "";
    }

    private static String afterNthArg(String args, int index) {
        String[] parts = trim(args).split("\\s+");
        if (parts.length <= index) {
            return "";
        }
        int pos = 0;
        for (int i = 0; i < index; i++) {
            pos = trim(args).indexOf(parts[i], pos);
            if (pos < 0) {
                return "";
            }
            pos += parts[i].length();
        }
        return trim(args).substring(Math.min(pos, trim(args).length())).trim();
    }
}
