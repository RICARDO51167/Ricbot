package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.application.runtime.RunCancellationService;
import ricbot.application.workspace.WorkspaceApplicationService;
import ricbot.domain.agent.dto.ContextQualityReport;
import ricbot.domain.change.ChangeSetRenderer;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.ChangeActionRuntimeService;
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
import ricbot.domain.runtime.*;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.RiskAssessment;
import ricbot.domain.policy.PolicyRole;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceRenderer;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.trace.TraceTimeline;
import ricbot.domain.trace.TraceViewerService;
import ricbot.domain.workspace.WorkspaceLifecycleService;
import ricbot.domain.workspace.dto.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.domain.workspace.enump.WorkspaceSessionStatus;
import ricbot.integration.command.CommandRouter;
import ricbot.integration.llm.api.LLMProvider;
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

    private final SessionManager sessionManager;
    private final String model;
    private final Path workspace;
    private final Function<InboundMessage, String> sessionKeyResolver;
    private final Function<String, List<Future<?>>> activeTaskRemover;
    private final BiConsumer<String, String> sessionInterruptMarker;
    private final ApprovalService approvalService;
    private final ToolRegistry toolRegistry;
    private final TraceStore traceStore;
    private final WorkspaceApplicationService workspaceApplication;
    private final LLMProvider provider;
    private final RuntimeQueryService runtime;
    private final DurableAgentRuntime agentRuntime;
    private final RunCancellationService cancellations;

    AgentCommands(
            SessionManager sessionManager,
            String model,
            Path workspace,
            Function<InboundMessage, String> sessionKeyResolver,
            Function<String, List<Future<?>>> activeTaskRemover,
            BiConsumer<String, String> sessionInterruptMarker,
            ApprovalService approvalService,
            ToolRegistry toolRegistry,
            LLMProvider provider,
            DurableAgentRuntime agentRuntime
    ) {
        this.sessionManager = sessionManager;
        this.model = model;
        this.workspace = workspace;
        this.sessionKeyResolver = sessionKeyResolver;
        this.activeTaskRemover = activeTaskRemover;
        this.sessionInterruptMarker = sessionInterruptMarker;
        this.traceStore = new TraceStore(this.workspace);
        this.approvalService = java.util.Objects.requireNonNull(approvalService, "approvalService");
        this.approvalService.setTraceStore(this.traceStore);
        this.toolRegistry = toolRegistry;
        this.provider = provider;
        this.runtime = new RuntimeQueryService(this.workspace, agentRuntime);
        this.agentRuntime = agentRuntime;
        this.cancellations = new RunCancellationService(agentRuntime, this.runtime, java.time.Clock.systemUTC());
        this.workspaceApplication = new WorkspaceApplicationService(this.workspace, this.sessionManager, this.traceStore);
    }

    void register(CommandRouter router) {
        router.priority("/stop", this::stop);
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
        router.prefix("/approve ", this::approve);
        router.prefix("/reject ", this::reject);
    }

    private CompletableFuture<OutboundMessage> stop(CommandRouter.CommandContext ctx) {
        String sessionKey = sessionKeyResolver.apply(ctx.getMsg());
        List<RunView> persisted = cancellations.cancelSession(sessionKey, "manual_stop");
        List<Future<?>> tasks = activeTaskRemover.apply(sessionKey);

        int interrupted = 0;
        if (tasks != null) {
            for (Future<?> task : tasks) {
                if (task != null && !task.isDone() && task.cancel(true)) {
                    interrupted++;
                }
            }
        }

        if (!persisted.isEmpty() || interrupted > 0) {
            sessionInterruptMarker.accept(sessionKey, "manual_stop");
        }
        return completedReply(ctx, !persisted.isEmpty() || interrupted > 0
                ? "⏹ 已持久化取消 " + persisted.size() + " 个 Run，并中断 " + interrupted + " 个本地任务。"
                : "没有可停止的任务。");
    }

    private CompletableFuture<OutboundMessage> startNewSession(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        session.clear();
        sessionManager.save(session);
        return completedReply(ctx, "已开始新的会话。");
    }

    private CompletableFuture<OutboundMessage> help(CommandRouter.CommandContext ctx) {
        return completedReply(ctx, "ricbot 命令：\n/new — 开始新对话\n/stop — 停止当前运行"
                + "\n/run start <goal> — 启动 Durable Run"
                + "\n/run list|show|events|timeline|children|health|cancel|effect-confirm|retry|replay|fork — Run 管理"
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
        Object rawRunTrace = session != null && session.getMetadata() != null
                ? session.getMetadata().get(SessionRuntimeKeys.RUN_TRACE_KEY) : null;
        if (rawRunTrace instanceof Map<?, ?> runTrace) {
            String runId = String.valueOf(runTrace.get("run_id") != null ? runTrace.get("run_id") : "").trim();
            if (!runId.isBlank()) try {
                Map<String, Object> report = runtime.report(runId);
                sb.append("\n\nrun: ").append(runId);
                sb.append("\nbudget: ").append(report.getOrDefault("budget", Map.of()));
                Object hints = report.get("runtimeHints");
                if (hints instanceof Map<?, ?> hintMap) {
                    sb.append("\ncontext: ").append(hintMap.get("context") != null
                            ? hintMap.get("context") : Map.of());
                }
                Object modelInput = report.getOrDefault("modelInput", Map.of());
                if (modelInput instanceof Map<?, ?> input) {
                    sb.append("\nmodel input: ").append(Map.of(
                            "utilization", input.get("utilization") != null ? input.get("utilization") : 0,
                            "mode", input.get("mode") != null ? input.get("mode") : "",
                            "totalTokens", input.get("totalTokens") != null ? input.get("totalTokens") : 0));
                }
                sb.append("\ntool exposure: ").append(report.getOrDefault("tools", Map.of()));
                sb.append("\nfile receipts: ").append(report.getOrDefault("fileReadReceipts", Map.of()));
                sb.append("\nexternal actions: ").append(report.getOrDefault("externalActions", Map.of()));
            } catch (RuntimeException ignored) { }
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
                    yield completedReply(ctx, renderer.renderRole(engine.policy(), parsePolicyRole(roleRaw)));
                }
                case "check" -> {
                    PolicyRole role = parsePolicyRole(commandArg(args, 1));
                    String toolName = commandArg(args, 2);
                    PolicyDecision decision = engine.evaluate(role, toolName, Map.of(), null);
                    tracePolicy(session, decision);
                    yield completedReply(ctx, renderer.renderDecision(decision));
                }
                case "check-command" -> {
                    PolicyRole role = parsePolicyRole(commandArg(args, 1));
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
        boolean fromWorkspace = activeWorkspace != null && activeWorkspace.status() == WorkspaceSessionStatus.ACTIVE;
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
                + "record: sqlite:.ricbot/application.db#changesets/" + changeSet.id() + "\n"
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
        ChangeActionRuntimeService.Result run = new ChangeActionRuntimeService(workspace, requireAgentRuntime()).start(action);
        ApprovalRequest request = approvalService.find(run.requestId());
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        traceEvent(session, TraceEventType.CHANGESET_COMMIT_REQUESTED, "change", "changeset commit requested", Map.of(
                "commitMessage", message,
                "commands", action.commands()
        ), changeSet.teamSessionId(), changeSet.id(), request.requestId());
        return completedReply(ctx, "change commit requires approval\n"
                + "requestId: " + request.requestId() + "\n"
                + "riskLevel: " + assessment.riskLevel() + "\n"
                + "runId: " + run.runId() + "\n"
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
        ChangeActionRuntimeService.Result run = new ChangeActionRuntimeService(workspace, requireAgentRuntime()).start(action);
        ApprovalRequest request = approvalService.find(run.requestId());
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        traceEvent(session, TraceEventType.CHANGESET_ROLLBACK_REQUESTED, "change", "changeset rollback requested", Map.of(
                "commands", changeSet.rollbackCommands()
        ), changeSet.teamSessionId(), changeSet.id(), request.requestId());
        return completedReply(ctx, "change rollback requires approval\n"
                + "requestId: " + request.requestId() + "\n"
                + "riskLevel: " + assessment.riskLevel() + "\n"
                + "runId: " + run.runId() + "\n"
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
                        .writeValueAsString(runtime.list()));
                case "show" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtime.report(requiredArgument(rest, "runId"))));
                case "events" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtime.events(requiredArgument(rest, "runId"))));
                case "timeline" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtime.timeline(requiredArgument(rest, "runId"))));
                case "children" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtime.children(requiredArgument(rest, "runId"))));
                case "health" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(requireAgentRuntime().health()));
                case "replay" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(replayRun(rest)));
                case "fork" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(forkRun(rest)));
                case "cancel" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(cancelRuntime(rest)));
                case "effect-confirm" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(confirmEffect(rest)));
                case "retry" -> completedReply(ctx, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(retryRun(rest)));
                default -> completedReply(ctx, "用法：/run start <goal> | /run list | /run health | /run show|events|timeline|children|cancel|retry <runId>"
                        + " | /run effect-confirm <runId> <effectId> <succeeded|failed> [resultReference]"
                        + " | /run replay <runId> [commit] | /run fork <runId> [commit] [newRunId] [--execute]");
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
        boolean execute = raw.contains("--execute");
        String[] parts = trim(raw.replace("--execute", "")).split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) throw new IllegalArgumentException("runId is required");
        long sequence = parts.length > 1 ? Long.parseLong(parts[1]) : Long.MAX_VALUE;
        String newRunId = parts.length > 2 ? parts[2] : "fork-" + java.util.UUID.randomUUID();
        return requireAgentRuntime().fork(new ForkSpec(parts[0], sequence, newRunId, execute));
    }

    private CompletableFuture<OutboundMessage> startRun(CommandRouter.CommandContext ctx, String raw) {
        String goal = trim(raw);
        if (goal.isBlank()) throw new IllegalArgumentException("goal is required");
        String runId = "run-" + java.util.UUID.randomUUID();
        RunView started = requireAgentRuntime().start(new RunSpec(runId, "", runId, "", List.of(), goal,
                "default", 128, Map.of("cli", true, "sessionId", ctx.getKey(),
                "workspace", workspace.toString())));
        return completedReply(ctx, MAPPER.valueToTree(started).toPrettyString());
    }

    private Object cancelRuntime(String raw) {
        return cancellations.cancel(requiredArgument(raw, "runId"), "cancelled from CLI");
    }

    private Object confirmEffect(String raw) {
        String[] parts = trim(raw).split("\\s+", 4);
        if (parts.length < 3 || parts[0].isBlank()) {
            throw new IllegalArgumentException(
                    "usage: /run effect-confirm <runId> <effectId> <succeeded|failed> [resultReference]");
        }
        String outcome = parts[2].toUpperCase(java.util.Locale.ROOT);
        if (!"SUCCEEDED".equals(outcome) && !"FAILED".equals(outcome)) {
            throw new IllegalArgumentException("effect outcome must be succeeded or failed");
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("effectId", parts[1]);
        payload.put("outcome", outcome);
        if (parts.length == 4 && !parts[3].isBlank()) payload.put("resultReference", parts[3]);
        return requireAgentRuntime().submit(parts[0], new ExternalEvent.EffectConfirmation(
                "effect-confirm-" + java.util.UUID.randomUUID(), parts[1], java.time.Instant.now(), payload));
    }

    private Object retryRun(String raw) {
        String runId = requiredArgument(raw, "runId");
        RunState previous = runtime.get(runId).map(RunView::state)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        if (!previous.status().terminal()) throw new IllegalStateException("only a terminal run can be retried");
        String retryId = "run-" + java.util.UUID.randomUUID();
        return requireAgentRuntime().start(new RunSpec(retryId, previous.spec().parentRunId(),
                previous.spec().rootRunId(), runId, previous.spec().dependencies(), previous.spec().goal(),
                previous.spec().executionPolicyRef(), previous.spec().maxSupersteps(), previous.spec().metadata()));
    }

    private static String requiredArgument(String value, String name) {
        String clean = value != null ? value.trim().split("\\s+")[0] : "";
        if (clean.isBlank()) throw new IllegalArgumentException(name + " is required");
        return clean;
    }

    private PolicyRole parsePolicyRole(String raw) {
        try { return PolicyRole.valueOf(trim(raw).replace('-', '_').toUpperCase(java.util.Locale.ROOT)); }
        catch (Exception failure) { throw new IllegalArgumentException("unknown role: " + raw); }
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
        try {
            String runId = approvalRunId(requestId, existing);
            String eventId = "approval-" + java.util.UUID.randomUUID();
            requireAgentRuntime().submit(runId, new ExternalEvent.ApprovalDecision(
                    eventId, requestId, java.time.Instant.now(), Map.of("requestId", requestId, "approved", true)));
            Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
            traceEvent(session, TraceEventType.APPROVAL_APPROVED, "approval", "approval signal committed", Map.of(
                    "status", "APPROVED", "runId", runId
            ), "", "", requestId);
            return completedReply(ctx, "审批 Event 已提交：" + requestId + "\nrunId: " + runId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return completedReply(ctx, "无法处理审批请求：" + requestId + "\n" + e.getMessage());
        }
    }

    private CompletableFuture<OutboundMessage> reject(CommandRouter.CommandContext ctx) {
        String requestId = trim(ctx.getArgs()).split("\\s+")[0];
        ApprovalRequest existing = approvalService.find(requestId);
        try {
            String runId = approvalRunId(requestId, existing);
            String eventId = "approval-" + java.util.UUID.randomUUID();
            requireAgentRuntime().submit(runId, new ExternalEvent.ApprovalDecision(
                    eventId, requestId, java.time.Instant.now(), Map.of("requestId", requestId, "approved", false)));
            Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
            traceEvent(session, TraceEventType.APPROVAL_REJECTED, "approval", "approval rejection signal committed", Map.of(
                    "status", "REJECTED", "runId", runId
            ), "", "", requestId);
            return completedReply(ctx, "拒绝 Event 已提交：" + requestId + "\nrunId: " + runId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return completedReply(ctx, "无法处理审批请求：" + requestId + "\n" + e.getMessage());
        }
    }

    private String approvalRunId(String requestId, ApprovalRequest existing) {
        if (existing != null) {
            if (existing.binding() == null || !existing.binding().bound()) {
                throw new IllegalStateException("该审批没有绑定 Runtime Activation，已拒绝提交。");
            }
            return existing.binding().runId();
        }
        return runtime.list().stream()
                .map(RunView::state)
                .filter(state -> state.status() == RunStatus.WAITING)
                .filter(state -> state.waitReason() instanceof WaitReason.ApprovalWait)
                .filter(state -> ((WaitReason.ApprovalWait) state.waitReason()).approvalRequestId().equals(requestId))
                .map(state -> state.spec().runId())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到审批请求：" + requestId));
    }

    private DurableAgentRuntime requireAgentRuntime() {
        if (agentRuntime == null) throw new IllegalStateException("DurableAgentRuntime is unavailable");
        return agentRuntime;
    }

    private String afterCommand(String args) {
        String value = trim(args);
        int firstSpace = value.indexOf(' ');
        return firstSpace >= 0 ? value.substring(firstSpace + 1).trim() : "";
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

    private static String trim(String s) {
        return s == null ? "" : s.trim();
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
