package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.application.workspace.WorkspaceApplicationService;
import ricbot.domain.change.ChangeSetRenderer;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.ChangeActionGraphService;
import ricbot.domain.change.GitChangeSet;
import ricbot.domain.change.GitChangeSetStatus;
import ricbot.domain.change.PendingChangeAction;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.message.OutboundMessages;
import ricbot.domain.policy.PolicyDecision;
import ricbot.domain.policy.PolicyDecisionType;
import ricbot.domain.policy.PolicyEngine;
import ricbot.domain.policy.PolicyRenderer;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.domain.security.ApprovalApplicationService;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.RiskAssessment;
import ricbot.domain.task.TaskRole;
import ricbot.domain.task.TaskWorkerRunner;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceRenderer;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.trace.TraceTimeline;
import ricbot.domain.trace.TraceViewerService;
import ricbot.domain.workspace.WorkspaceLifecycleService;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.integration.command.CommandRouter;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.domain.agent.graph.BuiltinGraphExecutors;
import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.agent.graph.GraphRunCoordinator;
import ricbot.domain.agent.graph.LocalTeamGraphService;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.domain.task.LocalTaskScheduler;
import ricbot.domain.task.LocalTaskSchedulerConfig;
import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TeamPlanModelPlanner;
import ricbot.domain.task.TeamPlan;
import ricbot.domain.task.TaskDelivery;
import ricbot.domain.task.TaskResult;
import ricbot.domain.verification.VerificationReport;
import ricbot.domain.verification.WorkspaceVerificationService;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class AgentCommands {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final SessionManager sessionManager;
    private final String model;
    private final Path workspace;
    private final Function<InboundMessage, String> sessionKeyResolver;
    private final Function<String, List<Future<?>>> activeTaskRemover;
    private final BiConsumer<String, String> sessionInterruptMarker;
    private final ApprovalService approvalService;
    private final ToolRegistry toolRegistry;
    private final TaskWorkerRunner teamWorkerRunner;
    private final TraceStore traceStore;
    private final WorkspaceApplicationService workspaceApplication;
    private final LLMProvider provider;
    private final RuntimeQueryService runtime;

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
                activeTaskRemover, sessionInterruptMarker, approvalService, toolRegistry, null, null);
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
            TaskWorkerRunner teamWorkerRunner
    ) {
        this(sessionManager, model, workspace, sessionKeyResolver, activeTaskRemover, sessionInterruptMarker,
                approvalService, toolRegistry, teamWorkerRunner, null);
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
            TaskWorkerRunner teamWorkerRunner,
            LLMProvider provider
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
        this.provider = provider;
        this.runtime = new RuntimeQueryService(this.workspace);
        this.workspaceApplication = new WorkspaceApplicationService(this.workspace, this.sessionManager, this.traceStore);
        recoverRuntimeOnStartup();
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
        router.exact("/run", this::run);
        router.prefix("/run ", this::run);
        router.exact("/task", this::taskV2);
        router.prefix("/task ", this::taskV2);
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
        return completedReply(ctx, "ricbot 命令：\n/new — 开始新对话\n/stop — 停止当前任务"
                + "\n/run start <goal> [--mode agent|team] — 启动 Graph Run"
                + "\n/run list|status|report|graph|events|resume|cancel <runId> — Run 管理"
                + "\n/task list <runId> | /task show|retry|cancel <taskId> — Task 管理"
                + "\n/summary — 查看当前任务摘要\n/workspace — Workspace 管理\n/change — ChangeSet 管理"
                + "\n/trace — Trace 管理\n/approve <requestId> | /reject <requestId> — 审批\n/help — 查看可用命令");
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
        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);
        traceEvent(session, TraceEventType.TASK_SUMMARY_CREATED, "agent", "task summary created", Map.of(
                "changedFiles", summary.changedFiles(),
                "blockers", summary.blockers(),
                "changeSetStatus", summary.changeSetStatus(),
                "commitHash", summary.commitHash(),
                "rollbackStatus", summary.rollbackStatus()
        ), "", "", "");
        return completedReply(ctx, new TaskSummaryRenderer().render(summary));
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
                    TaskRole role = parseTeamRole(commandArg(args, 1));
                    String toolName = commandArg(args, 2);
                    PolicyDecision decision = engine.evaluate(role, toolName, Map.of(), null);
                    tracePolicy(session, decision);
                    yield completedReply(ctx, renderer.renderDecision(decision));
                }
                case "check-command" -> {
                    TaskRole role = parseTeamRole(commandArg(args, 1));
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
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        String teamSessionId = "";
        String taskId = "";
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
        storeChangeSetContext(session, changeSet, renderer);
        traceEvent(session, fromWorkspace ? TraceEventType.CHANGESET_CREATED_FROM_WORKSPACE : TraceEventType.CHANGESET_CREATED, "change", fromWorkspace ? "changeset created from workspace" : "changeset created", Map.of(
                "status", changeSet.status().name(),
                "changedFiles", changeSet.changedFiles(),
                "diffSummary", changeSet.diffSummary(),
                "workspaceSessionId", changeSet.workspaceSessionId(),
                "workspacePath", changeSet.workspacePath()
        ), changeSet.teamSessionId(), changeSet.id(), "");
        if (json) {
            try {
                return completedReply(ctx, MAPPER.writeValueAsString(changeSet.toMap()));
            } catch (Exception e) {
                throw new IllegalStateException("changeset json render failed: " + e.getMessage(), e);
            }
        }
        return completedReply(ctx, "changeset created\n"
                + "id: " + changeSet.id() + "\n"
                + "record: sqlite:.ricbot/runtime.db#changesets/" + changeSet.id() + "\n"
                + "diff: stored in the immutable ChangeSet event payload\n\n"
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
        ChangeActionGraphService.Result graph = new ChangeActionGraphService(workspace, approvalService).start(action, assessment);
        ApprovalRequest request = approvalService.find(graph.requestId());
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        traceEvent(session, TraceEventType.CHANGESET_COMMIT_REQUESTED, "change", "changeset commit requested", Map.of(
                "commitMessage", message,
                "commands", action.commands()
        ), changeSet.teamSessionId(), changeSet.id(), request.requestId());
        return completedReply(ctx, "change commit requires approval\n"
                + "requestId: " + request.requestId() + "\n"
                + "riskLevel: " + assessment.riskLevel() + "\n"
                + "runId: " + graph.runId() + "\n"
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
        ChangeActionGraphService.Result graph = new ChangeActionGraphService(workspace, approvalService).start(action, assessment);
        ApprovalRequest request = approvalService.find(graph.requestId());
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        traceEvent(session, TraceEventType.CHANGESET_ROLLBACK_REQUESTED, "change", "changeset rollback requested", Map.of(
                "commands", changeSet.rollbackCommands()
        ), changeSet.teamSessionId(), changeSet.id(), request.requestId());
        return completedReply(ctx, "change rollback requires approval\n"
                + "requestId: " + request.requestId() + "\n"
                + "riskLevel: " + assessment.riskLevel() + "\n"
                + "runId: " + graph.runId() + "\n"
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

    private CompletableFuture<OutboundMessage> run(CommandRouter.CommandContext ctx) {
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "list" : args.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        String rest = afterCommand(args);
        try {
            return switch (action) {
                case "start" -> startRun(ctx, rest);
                case "list" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtime.runs()));
                case "status", "report", "graph" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtime.report(requiredArgument(rest, "runId"))));
                case "events" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtime.events(requiredArgument(rest, "runId"))));
                case "replay" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(replayRun(rest)));
                case "fork" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(forkRun(rest)));
                case "cancel" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtime.cancelRun(requiredArgument(rest, "runId"), "cancelled from CLI")));
                case "resume" -> resumeRun(ctx, requiredArgument(rest, "runId"));
                default -> completedReply(ctx, "用法：/run start <goal> [--mode agent|team] [--worktree] [--verify]"
                        + " | /run list | /run status|report|graph|events|resume|cancel <runId>"
                        + " | /run replay <runId> [eventSequence] | /run fork <runId> [eventSequence] [newRunId]");
            };
        } catch (Exception e) {
            return completedReply(ctx, "run error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private Object replayRun(String raw) {
        String[] parts = trim(raw).split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) throw new IllegalArgumentException("runId is required");
        long sequence = parts.length > 1 ? Long.parseLong(parts[1]) : Long.MAX_VALUE;
        return runtime.replay(parts[0], sequence);
    }

    private Object forkRun(String raw) {
        String[] parts = trim(raw).split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) throw new IllegalArgumentException("runId is required");
        long sequence = parts.length > 1 ? Long.parseLong(parts[1]) : Long.MAX_VALUE;
        String newRunId = parts.length > 2 ? parts[2] : "fork-" + java.util.UUID.randomUUID();
        return runtime.fork(parts[0], sequence, newRunId);
    }

    private CompletableFuture<OutboundMessage> startRun(CommandRouter.CommandContext ctx, String raw) throws Exception {
        String mode = raw.contains("--mode team") ? "team" : "agent";
        String goal = raw.replace("--mode team", "").replace("--mode agent", "")
                .replace("--worktree", "").replace("--verify", "").trim();
        if (goal.isBlank()) throw new IllegalArgumentException("goal is required");
        if ("agent".equals(mode)) {
            if (!(ctx.getLoop() instanceof AgentLoop loop)) throw new IllegalStateException("agent loop is unavailable");
            InboundMessage message = ctx.getMsg();
            OutboundMessage response = loop.processDirect(goal, ctx.getKey(), message.getChannel(), message.getChatId(),
                    message.getMetadata(), List.of());
            return CompletableFuture.completedFuture(response);
        }
        return completedReply(ctx, runTeamGraph(goal, null));
    }

    private CompletableFuture<OutboundMessage> resumeRun(CommandRouter.CommandContext ctx, String runId) {
        GraphExecutionState state = runtime.run(runId);
        if (state == null) throw new IllegalArgumentException("run not found: " + runId);
        if (!ricbot.domain.agent.graph.DefaultTeamGraph.GRAPH_ID.equals(state.graphId())) {
            return completedReply(ctx, "该 Agent Run 需要原始会话上下文恢复；请发送普通消息继续，或查看 /run report " + runId);
        }
        return completedReply(ctx, runTeamGraph(String.valueOf(state.channels().getOrDefault("goal", "")), runId));
    }

    private String runTeamGraph(String goal, String existingRunId) {
        if (provider == null || teamWorkerRunner == null) throw new IllegalStateException("team runtime is unavailable");
        SqliteRuntimeStore graphStore = new SqliteRuntimeStore(workspace);
        try (LocalTaskScheduler scheduler = new LocalTaskScheduler(workspace, graphStore, graphStore,
                LocalTaskSchedulerConfig.defaults(), Set.copyOf(AgentTeamWorkerRunner.ALLOWED_TOOLS))) {
            LocalTeamTaskExecutor executor = new LocalTeamTaskExecutor(workspace, teamWorkerRunner);
            for (TaskRole role : TaskRole.values()) scheduler.register(role, executor);
            scheduler.recover();
            BuiltinGraphExecutors.Verifier verifier = (state, input) -> verifyIntegration(state);
            try (LocalTeamGraphService service = new LocalTeamGraphService(workspace, scheduler,
                    new TeamPlanModelPlanner(provider, model), verifier, approvalService);
                 GraphRunCoordinator coordinator = existingRunId == null ? service.start(goal) : service.open(existingRunId, goal)) {
                GraphExecutionState state = coordinator.awaitTerminalOrHumanPause(java.time.Duration.ofHours(2));
                return "runId: " + state.runId() + "\ngraphId: " + state.graphId() + "\nstatus: " + state.status()
                        + "\nsuperstep: " + state.superstep() + (state.waits().isEmpty() ? "" : "\nwaits: " + state.waits());
            }
        }
    }

    private BuiltinGraphExecutors.VerificationDecision verifyIntegration(GraphExecutionState state) {
        String path = String.valueOf(state.channels().getOrDefault("integrationWorkspace", ""));
        if (path.isBlank()) return new BuiltinGraphExecutors.VerificationDecision("needs_human",
                Map.of("reason", "integration workspace is missing"));
        try {
            TeamPlan plan = value(state.channels().get("teamPlan"), TeamPlan.class);
            List<TaskResult> results = taskResults(state.channels().get("workerResults"));
            VerificationReport report = new WorkspaceVerificationService(workspace)
                    .verify(state.runId(), Path.of(path), plan, results,
                            String.valueOf(state.channels().getOrDefault("verificationProfileDigest", "")));
            return new BuiltinGraphExecutors.VerificationDecision(report.outcome(), report.toMap());
        } catch (Exception e) {
            return new BuiltinGraphExecutors.VerificationDecision("needs_human", Map.of("reason", e.getMessage()));
        }
    }

    private static <T> T value(Object raw, Class<T> type) {
        if (type.isInstance(raw)) return type.cast(raw);
        if (raw instanceof Map<?, ?>) return MAPPER.convertValue(raw, type);
        return null;
    }

    private static List<TaskResult> taskResults(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(item -> item instanceof TaskDelivery delivery ? delivery.result() : item)
                .map(item -> item instanceof TaskResult result ? result
                        : item instanceof Map<?, ?> ? MAPPER.convertValue(item, TaskResult.class) : null)
                .filter(java.util.Objects::nonNull).toList();
    }

    private CompletableFuture<OutboundMessage> taskV2(CommandRouter.CommandContext ctx) {
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "list" : args.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        String id = afterCommand(args);
        try {
            return switch (action) {
                case "list" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtime.tasks(requiredArgument(id, "runId"))));
                case "show" -> {
                    TaskRecord task = runtime.task(requiredArgument(id, "taskId"));
                    if (task == null) throw new IllegalArgumentException("task not found: " + id);
                    yield completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "task", task, "result", runtime.result(task.spec().taskId()))));
                }
                case "cancel" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtime.cancelTask(requiredArgument(id, "taskId"), "cancelled from CLI")));
                case "retry" -> {
                    TaskRecord retry = runtime.retryTask(requiredArgument(id, "taskId"));
                    GraphExecutionState parent = runtime.run(retry.spec().parentRunId());
                    if (parent != null && ricbot.domain.agent.graph.DefaultTeamGraph.GRAPH_ID.equals(parent.graphId()) && !parent.status().terminal()) {
                        CompletableFuture.runAsync(() -> runTeamGraph(
                                String.valueOf(parent.channels().getOrDefault("goal", "")), parent.runId()));
                    }
                    yield completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(retry));
                }
                default -> completedReply(ctx, "用法：/task list <runId> | /task show|retry|cancel <taskId>");
            };
        } catch (Exception e) {
            return completedReply(ctx, "task error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private static String requiredArgument(String value, String name) {
        String clean = value != null ? value.trim().split("\\s+")[0] : "";
        if (clean.isBlank()) throw new IllegalArgumentException(name + " is required");
        return clean;
    }

    private TaskRole parseTeamRole(String raw) {
        try {
            return TaskRole.valueOf(trim(raw).replace('-', '_').toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("unknown team role: " + raw);
        }
    }

    private String activeWorkspaceSessionId(Session session) {
        if (session == null || session.getMetadata() == null) return "";
        Object raw = session.getMetadata().get(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY);
        return raw != null ? String.valueOf(raw).trim() : "";
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

    private CompletableFuture<OutboundMessage> approve(CommandRouter.CommandContext ctx) {
        String requestId = trim(ctx.getArgs()).split("\\s+")[0];
        ApprovalRequest existing = approvalService.find(requestId);
        if (existing == null) return completedReply(ctx, "未找到审批请求：" + requestId);
        if (existing.binding() == null || !existing.binding().bound()) {
            return completedReply(ctx, "该审批没有绑定 Runtime Activation，已拒绝提交。");
        }
        try {
            ApprovalRequest request = new SqliteRuntimeStore(workspace).decideApprovalAndSignal(requestId, true);
            approvalService.acceptCommitted(request);
            Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
            traceEvent(session, TraceEventType.APPROVAL_APPROVED, "approval", "approval signal committed", Map.of(
                    "status", request.status().name(), "runId", request.binding().runId()
            ), "", "", request.requestId());
            return completedReply(ctx, "审批 Signal 已提交：" + request.requestId()
                    + "\nstatus: " + request.status()
                    + "\nrunId: " + request.binding().runId()
                    + "\nRuntime 将从审批节点恢复；命令路径未直接执行任何副作用。");
        } catch (IllegalStateException | IllegalArgumentException e) {
            return completedReply(ctx, "无法处理审批请求：" + requestId + "\n" + e.getMessage());
        }
    }

    private CompletableFuture<OutboundMessage> reject(CommandRouter.CommandContext ctx) {
        String requestId = trim(ctx.getArgs()).split("\\s+")[0];
        ApprovalRequest existing = approvalService.find(requestId);
        if (existing == null) return completedReply(ctx, "未找到审批请求：" + requestId);
        if (existing.binding() == null || !existing.binding().bound()) {
            return completedReply(ctx, "该审批没有绑定 Runtime Activation，已拒绝提交。");
        }
        ApprovalRequest request = new SqliteRuntimeStore(workspace).decideApprovalAndSignal(requestId, false);
        approvalService.acceptCommitted(request);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        traceEvent(session, TraceEventType.APPROVAL_REJECTED, "approval", "approval rejection signal committed", Map.of(
                "status", request.status().name()
        ), "", "", request.requestId());
        return completedReply(ctx, "拒绝 Signal 已提交：" + request.requestId() + "\nstatus: " + request.status()
                + "\nRuntime 将从审批节点恢复；命令路径未直接执行任何副作用。");
    }

    private void recoverRuntimeOnStartup() {
        if (runtime.hasLegacyData()) {
            org.slf4j.LoggerFactory.getLogger(AgentCommands.class).info(
                    "Legacy Ricbot runtime data is retained read-only and cannot be resumed by the unified runtime");
        }
        if (provider == null || teamWorkerRunner == null) return;
        for (GraphExecutionState state : runtime.runs()) {
            boolean recoverable = ricbot.domain.agent.graph.DefaultTeamGraph.GRAPH_ID.equals(state.graphId()) && !state.status().terminal()
                    && state.waits().stream().anyMatch(wait -> "tasks".equals(wait.type()));
            if (recoverable) {
                CompletableFuture.runAsync(() -> runTeamGraph(
                        String.valueOf(state.channels().getOrDefault("goal", "")), state.runId()));
            }
        }
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
