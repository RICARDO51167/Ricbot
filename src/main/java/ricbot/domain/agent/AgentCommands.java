package ricbot.domain.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.change.ChangeSetRenderer;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSet;
import ricbot.domain.change.GitChangeSetStatus;
import ricbot.domain.change.PendingChangeAction;
import ricbot.domain.memory.Dream;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperienceExtractor;
import ricbot.domain.experience.ExperienceOutcome;
import ricbot.domain.experience.ExperiencePromoter;
import ricbot.domain.experience.ExperienceRenderer;
import ricbot.domain.experience.ExperienceSkillPromoter;
import ricbot.domain.experience.ExperienceStore;
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
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.PendingToolCall;
import ricbot.domain.security.RiskAssessment;
import ricbot.domain.subagent.SubAgentOrchestrator;
import ricbot.domain.subagent.SubAgentResult;
import ricbot.domain.subagent.SubAgentRole;
import ricbot.domain.subagent.SubAgentTask;
import ricbot.domain.team.TeamArtifact;
import ricbot.domain.team.TeamDecisionPolicy;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamEvent;
import ricbot.domain.team.TeamExecutionService;
import ricbot.domain.team.TeamRole;
import ricbot.domain.team.TeamSession;
import ricbot.domain.team.TeamTask;
import ricbot.domain.team.TeamTaskReport;
import ricbot.domain.team.ImplementationStepGate;
import ricbot.domain.team.ImplementationStepStatus;
import ricbot.domain.team.ImplementationStepType;
import ricbot.domain.team.PendingImplementationStep;
import ricbot.domain.team.StepGateResult;
import ricbot.domain.team.StepUpdateRequest;
import ricbot.domain.team.StepAuditEventType;
import ricbot.domain.team.StepAuditRecord;
import ricbot.domain.team.VerificationInput;
import ricbot.domain.team.VerificationResult;
import ricbot.domain.team.WorkerExecutionInput;
import ricbot.domain.team.WorkerExecutionResult;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceRenderer;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.workspace.GitWorktreeWorkspaceBackend;
import ricbot.domain.workspace.LocalWorkspaceBackend;
import ricbot.domain.workspace.WorkspaceBackend;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceRenderer;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.infra.config.Config;
import ricbot.integration.command.CommandRouter;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final MemoryStore memoryStore;
    private final Dream dream;
    private final Config.DreamConfig dreamConfig;
    private final String model;
    private final Path workspace;
    private final Function<InboundMessage, String> sessionKeyResolver;
    private final Function<String, List<Future<?>>> activeTaskRemover;
    private final BiConsumer<String, String> sessionInterruptMarker;
    private final ApprovalService approvalService;
    private final ToolRegistry toolRegistry;
    private final TeamEngine teamEngine;
    private final TraceStore traceStore;

    AgentCommands(
            SessionManager sessionManager,
            MemoryStore memoryStore,
            Dream dream,
            Config.DreamConfig dreamConfig,
            String model,
            Path workspace,
            Function<InboundMessage, String> sessionKeyResolver,
            Function<String, List<Future<?>>> activeTaskRemover,
            BiConsumer<String, String> sessionInterruptMarker
    ) {
        this(sessionManager, memoryStore, dream, dreamConfig, model, workspace, sessionKeyResolver,
                activeTaskRemover, sessionInterruptMarker, new ApprovalService(), null);
    }

    AgentCommands(
            SessionManager sessionManager,
            MemoryStore memoryStore,
            Dream dream,
            Config.DreamConfig dreamConfig,
            String model,
            Path workspace,
            Function<InboundMessage, String> sessionKeyResolver,
            Function<String, List<Future<?>>> activeTaskRemover,
            BiConsumer<String, String> sessionInterruptMarker,
            ApprovalService approvalService
    ) {
        this(sessionManager, memoryStore, dream, dreamConfig, model, workspace, sessionKeyResolver,
                activeTaskRemover, sessionInterruptMarker, approvalService, null);
    }

    AgentCommands(
            SessionManager sessionManager,
            MemoryStore memoryStore,
            Dream dream,
            Config.DreamConfig dreamConfig,
            String model,
            Path workspace,
            Function<InboundMessage, String> sessionKeyResolver,
            Function<String, List<Future<?>>> activeTaskRemover,
            BiConsumer<String, String> sessionInterruptMarker,
            ApprovalService approvalService,
            ToolRegistry toolRegistry
    ) {
        this.sessionManager = sessionManager;
        this.memoryStore = memoryStore;
        this.dream = dream;
        this.dreamConfig = dreamConfig;
        this.model = model;
        this.workspace = workspace;
        this.sessionKeyResolver = sessionKeyResolver;
        this.activeTaskRemover = activeTaskRemover;
        this.sessionInterruptMarker = sessionInterruptMarker;
        this.traceStore = new TraceStore(this.workspace);
        this.approvalService = approvalService != null ? approvalService : new ApprovalService();
        this.approvalService.setTraceStore(this.traceStore);
        this.toolRegistry = toolRegistry;
        this.teamEngine = new TeamEngine(this.workspace, this.traceStore);
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
        router.exact("/experience", this::experience);
        router.prefix("/experience ", this::experience);
        router.exact("/subagent", this::subagent);
        router.prefix("/subagent ", this::subagent);
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
        router.exact("/dream", this::dream);
        router.exact("/dream-log", this::dreamLog);
        router.prefix("/dream-log ", this::dreamLog);
        router.exact("/dream-restore", this::dreamRestore);
        router.prefix("/dream-restore ", this::dreamRestore);
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
        return completedReply(ctx, "ricbot 命令：\n/new — 开始新对话\n/stop — 停止当前任务\n/summary — 查看当前任务摘要\n/subagent plan|explore|review|list|show — 角色化子代理摘要\n/team start|status|list|resume|archive|suggest|suggest-current|task|auto-verify|verifier-report|verify|events|whiteboard|abort — TeamEngine 状态机\n/workspace create|status|list|use|diff|cleanup — Local/Worktree workspace session\n/change create|status|diff|commit-message|approve|commit|rollback — GitChangeSet 工作流\n/trace last|list|show|events|export — Coding Harness trace\n/help — 查看可用命令");
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

    private CompletableFuture<OutboundMessage> experience(CommandRouter.CommandContext ctx) {
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "list" : args.split("\\s+")[0].toLowerCase();
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceRenderer renderer = new ExperienceRenderer(store);
        try {
            return switch (action) {
                case "extract" -> experienceExtract(ctx, store, renderer);
                case "list" -> completedReply(ctx, renderer.renderList(store.listCandidates()));
                case "show" -> completedReply(ctx, renderer.renderDetail(store.find(commandArg(args, 1))));
                case "verify" -> completedReply(ctx, "experience verified\n"
                        + renderer.renderDetail(store.verify(commandArg(args, 1))));
                case "reject" -> completedReply(ctx, "experience rejected\n"
                        + renderer.renderDetail(store.reject(commandArg(args, 1))));
                case "feedback" -> completedReply(ctx, "experience feedback recorded\n"
                        + renderer.renderDetail(store.feedback(commandArg(args, 1), parseOutcome(commandArg(args, 2)))));
                case "usage" -> completedReply(ctx, renderer.renderUsage(store.listUsage(commandArg(args, 1))));
                case "stale" -> completedReply(ctx, renderer.renderStale(store.listStaleVerified()));
                case "archive" -> completedReply(ctx, "experience archived\n"
                        + renderer.renderDetail(store.archive(commandArg(args, 1))));
                case "promote" -> completedReply(ctx, "experience promoted\n"
                        + renderer.renderDetail(new ExperiencePromoter(new NoteService(workspace), store).promote(commandArg(args, 1))));
                case "promote-skill" -> completedReply(ctx, renderExperienceSkillPromotion(
                        new ExperienceSkillPromoter(workspace, store).promote(commandArg(args, 1), args.contains("--force"))));
                case "demote" -> completedReply(ctx, "experience demoted\n"
                        + renderer.renderDetail(store.demote(commandArg(args, 1))));
                case "restore" -> completedReply(ctx, "experience restored\n"
                        + renderer.renderDetail(store.restore(commandArg(args, 1))));
                case "stats" -> completedReply(ctx, renderer.renderStats(store.stats()));
                case "review" -> completedReply(ctx, renderer.renderReview(store.review(20)));
                default -> completedReply(ctx, "用法：/experience extract|list|show <id>|verify <id>|reject <id>|feedback <id> success|failure|neutral|usage <id>|stale|archive <id>|promote <id>|promote-skill <id>|demote <id>|restore <id>|stats|review");
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return completedReply(ctx, "experience error: " + e.getMessage());
        }
    }

    private CompletableFuture<OutboundMessage> experienceExtract(
            CommandRouter.CommandContext ctx,
            ExperienceStore store,
            ExperienceRenderer renderer
    ) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);
        List<ExperienceEntry> extracted = new ExperienceExtractor().extract(summary);
        List<ExperienceEntry> stored = new java.util.ArrayList<>();
        for (ExperienceEntry entry : extracted) {
            stored.add(store.addCandidate(entry));
        }
        return completedReply(ctx, "experience extracted: " + stored.size()
                + "\nfile: " + workspace.relativize(store.candidatesFile())
                + "\n\n" + renderer.renderList(stored));
    }

    private String renderExperienceSkillPromotion(ExperienceSkillPromoter.PromotionResult result) {
        String relativePath;
        try {
            relativePath = workspace.relativize(result.skillPath().toAbsolutePath().normalize()).toString();
        } catch (Exception e) {
            relativePath = result.skillPath().toString();
        }
        if (result.alreadyExists()) {
            return "experience skill already exists\n"
                    + "sourceExperienceId: " + result.sourceExperienceId() + "\n"
                    + "skillName: " + result.skillName() + "\n"
                    + "path: " + relativePath + "\n"
                    + "hint: rerun with --force to overwrite";
        }
        return "experience skill promoted\n"
                + "sourceExperienceId: " + result.sourceExperienceId() + "\n"
                + "skillName: " + result.skillName() + "\n"
                + "path: " + relativePath + "\n"
                + "created: " + result.created();
    }

    private CompletableFuture<OutboundMessage> trace(CommandRouter.CommandContext ctx) {
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "last" : args.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        TraceRenderer renderer = new TraceRenderer();
        try {
            return switch (action) {
                case "last" -> {
                    TraceStore.TraceSummary latest = traceStore.loadLatestTrace();
                    yield completedReply(ctx, renderer.renderSummary(latest, latest != null ? traceStore.loadEvents(latest.traceId()) : List.of()));
                }
                case "list" -> completedReply(ctx, renderer.renderList(traceStore.listTraces().stream()
                        .map(traceStore::summarize)
                        .toList()));
                case "show" -> {
                    String traceId = commandArg(args, 1);
                    yield completedReply(ctx, renderer.renderSummary(traceStore.summarize(traceId), traceStore.loadEvents(traceId)));
                }
                case "events" -> completedReply(ctx, renderer.renderEvents(traceStore.loadEvents(commandArg(args, 1))));
                case "export" -> completedReply(ctx, renderer.renderExport(traceStore.loadEvents(commandArg(args, 1))));
                default -> completedReply(ctx, "用法：/trace last|list|show <traceId>|events <traceId>|export <traceId>");
            };
        } catch (IllegalArgumentException e) {
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
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "status" : args.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
        WorkspaceRenderer renderer = new WorkspaceRenderer();
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        try {
            return switch (action) {
                case "create" -> workspaceCreate(ctx, session, store, renderer);
                case "status" -> completedReply(ctx, renderer.renderStatus(activeWorkspaceSession(session, store)));
                case "list" -> completedReply(ctx, renderer.renderList(store.list()));
                case "use" -> workspaceUse(ctx, session, store, renderer, commandArg(args, 1));
                case "diff" -> workspaceDiff(ctx, session, store, renderer, commandArg(args, 1));
                case "cleanup" -> workspaceCleanup(ctx, session, store, renderer, commandArg(args, 1));
                default -> completedReply(ctx, "用法：/workspace create --mode local|worktree <goal>|status|list|use <id>|diff <id>|cleanup <id>");
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return completedReply(ctx, "workspace error: " + e.getMessage());
        }
    }

    private CompletableFuture<OutboundMessage> workspaceCreate(
            CommandRouter.CommandContext ctx,
            Session session,
            WorkspaceSessionStore store,
            WorkspaceRenderer renderer
    ) {
        String args = trim(ctx.getArgs());
        String mode = optionValue(args, "--mode", "local").toLowerCase(java.util.Locale.ROOT);
        String goal = workspaceCreateGoal(args);
        WorkspaceBackend backend = switch (mode) {
            case "local" -> new LocalWorkspaceBackend(store);
            case "worktree", "git_worktree" -> new GitWorktreeWorkspaceBackend(workspace, store);
            default -> throw new IllegalArgumentException("unsupported workspace mode: " + mode);
        };
        WorkspaceSession created = backend.createSession(workspace, goal);
        storeWorkspaceContext(session, created, renderer);
        traceEvent(session, TraceEventType.WORKSPACE_CREATED, "workspace", "workspace session created", Map.of(
                "workspaceSessionId", created.id(),
                "type", created.type().name(),
                "workspacePath", created.workspacePath(),
                "goal", created.goal()
        ), "", "", "");
        return completedReply(ctx, "workspace created\n"
                + "id: " + created.id() + "\n"
                + "source: .workspaces/" + created.id() + "/session.json\n\n"
                + renderer.renderStatus(created));
    }

    private CompletableFuture<OutboundMessage> workspaceUse(
            CommandRouter.CommandContext ctx,
            Session session,
            WorkspaceSessionStore store,
            WorkspaceRenderer renderer,
            String sessionId
    ) {
        WorkspaceSession selected = requireWorkspaceSession(store, sessionId);
        storeWorkspaceContext(session, selected, renderer);
        traceEvent(session, TraceEventType.WORKSPACE_SELECTED, "workspace", "workspace session selected", Map.of(
                "workspaceSessionId", selected.id(),
                "type", selected.type().name(),
                "workspacePath", selected.workspacePath()
        ), "", "", "");
        return completedReply(ctx, "workspace selected\n" + renderer.renderStatus(selected));
    }

    private CompletableFuture<OutboundMessage> workspaceDiff(
            CommandRouter.CommandContext ctx,
            Session session,
            WorkspaceSessionStore store,
            WorkspaceRenderer renderer,
            String sessionId
    ) {
        WorkspaceSession target = requireWorkspaceSession(store, sessionId);
        String diff = backendFor(target, store).diff(target.id());
        traceEvent(session, TraceEventType.WORKSPACE_DIFFED, "workspace", "workspace diff rendered", Map.of(
                "workspaceSessionId", target.id(),
                "type", target.type().name(),
                "diffChars", diff != null ? diff.length() : 0
        ), "", "", "");
        return completedReply(ctx, renderer.renderDiff(target, diff, 4_000));
    }

    private CompletableFuture<OutboundMessage> workspaceCleanup(
            CommandRouter.CommandContext ctx,
            Session session,
            WorkspaceSessionStore store,
            WorkspaceRenderer renderer,
            String sessionId
    ) {
        WorkspaceSession target = requireWorkspaceSession(store, sessionId);
        WorkspaceSession cleaned = backendFor(target, store).cleanup(target.id());
        if (activeWorkspaceSessionId(session).equals(cleaned.id())) {
            session.getMetadata().remove(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY);
            session.getMetadata().remove(SessionRuntimeKeys.WORKSPACE_SUMMARY_KEY);
            session.getMetadata().remove(SessionRuntimeKeys.WORKSPACE_SOURCE_KEY);
            sessionManager.save(session);
        }
        traceEvent(session, TraceEventType.WORKSPACE_CLEANED, "workspace", "workspace session cleaned", Map.of(
                "workspaceSessionId", cleaned.id(),
                "type", cleaned.type().name(),
                "workspacePath", cleaned.workspacePath(),
                "status", cleaned.status().name()
        ), "", "", "");
        return completedReply(ctx, "workspace cleaned\n" + renderer.renderStatus(cleaned));
    }

    private WorkspaceBackend backendFor(WorkspaceSession session, WorkspaceSessionStore store) {
        if (session.type() == WorkspaceBackendType.GIT_WORKTREE) {
            return new GitWorktreeWorkspaceBackend(workspace, store);
        }
        return new LocalWorkspaceBackend(store);
    }

    private WorkspaceSession activeWorkspaceSession(Session session, WorkspaceSessionStore store) {
        String id = activeWorkspaceSessionId(session);
        WorkspaceSession active = !id.isBlank() ? store.load(id) : null;
        if (active != null) {
            return active;
        }
        return store.loadActive().stream().findFirst().orElse(null);
    }

    private WorkspaceSession requireWorkspaceSession(WorkspaceSessionStore store, String sessionId) {
        WorkspaceSession workspaceSession = store.load(sessionId);
        if (workspaceSession == null) {
            throw new IllegalArgumentException("workspace session not found: " + sessionId);
        }
        return workspaceSession;
    }

    private void storeWorkspaceContext(Session session, WorkspaceSession workspaceSession, WorkspaceRenderer renderer) {
        if (session == null || workspaceSession == null) {
            return;
        }
        session.getMetadata().put(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY, workspaceSession.id());
        session.getMetadata().put(SessionRuntimeKeys.WORKSPACE_SUMMARY_KEY, renderer.renderStatus(workspaceSession).replace("\n", " | "));
        session.getMetadata().put(SessionRuntimeKeys.WORKSPACE_SOURCE_KEY, ".workspaces/" + workspaceSession.id() + "/session.json");
        sessionManager.save(session);
    }

    private String activeWorkspaceSessionId(Session session) {
        if (session == null || session.getMetadata() == null) {
            return "";
        }
        Object raw = session.getMetadata().get(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY);
        return raw != null ? String.valueOf(raw).trim() : "";
    }

    private String workspaceCreateGoal(String args) {
        String value = afterCommand(args);
        String mode = optionValue(args, "--mode", "");
        if (!mode.isBlank()) {
            value = value.replaceFirst("--mode\\s+" + java.util.regex.Pattern.quote(mode), "").trim();
        }
        return value.isBlank() ? "workspace session" : value;
    }

    private CompletableFuture<OutboundMessage> change(CommandRouter.CommandContext ctx) {
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "status" : args.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        ChangeSetService service = new ChangeSetService(workspace);
        ChangeSetRenderer renderer = new ChangeSetRenderer();
        try {
            return switch (action) {
                case "create" -> changeCreate(ctx, service, renderer);
                case "status" -> completedReply(ctx, renderer.renderStatus(latestChangeSet(ctx, service)));
                case "diff" -> completedReply(ctx, renderer.renderDiff(latestChangeSet(ctx, service), 4_000));
                case "commit-message" -> changeCommitMessage(ctx, service, renderer);
                case "approve" -> changeApprove(ctx, service, renderer);
                case "commit" -> changeCommit(ctx, service);
                case "rollback" -> args.contains("--execute")
                        ? changeRollbackExecute(ctx, service)
                        : completedReply(ctx, renderer.renderRollback(latestChangeSet(ctx, service)));
                default -> completedReply(ctx, "用法：/change create|status|diff|commit-message|approve|commit [--message \"...\"]|rollback [--execute]");
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return completedReply(ctx, "change error: " + e.getMessage());
        }
    }

    private CompletableFuture<OutboundMessage> changeCreate(
            CommandRouter.CommandContext ctx,
            ChangeSetService service,
            ChangeSetRenderer renderer
    ) {
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
        WorkspaceSession activeWorkspace = !activeWorkspaceId.isBlank() ? workspaceStore.load(activeWorkspaceId) : null;
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

    private CompletableFuture<OutboundMessage> subagent(CommandRouter.CommandContext ctx) {
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "list" : args.split("\\s+")[0].toLowerCase();
        SubAgentOrchestrator orchestrator = new SubAgentOrchestrator(sessionManager, workspace);
        try {
            return switch (action) {
                case "plan" -> subagentPlan(ctx, orchestrator, afterCommand(args));
                case "explore" -> subagentExplore(ctx, orchestrator, afterCommand(args));
                case "review" -> subagentReview(ctx, orchestrator);
                case "list" -> completedReply(ctx, renderSubAgentList(orchestrator.listRecentResults(ctx.getKey())));
                case "show" -> completedReply(ctx, renderSubAgentShow(orchestrator.listRecentResults(ctx.getKey()), commandArg(args, 1)));
                default -> completedReply(ctx, "用法：/subagent plan <goal>|explore <goal>|review|list|show <id>");
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return completedReply(ctx, "subagent error: " + e.getMessage());
        }
    }

    private CompletableFuture<OutboundMessage> subagentPlan(
            CommandRouter.CommandContext ctx,
            SubAgentOrchestrator orchestrator,
            String rawGoal
    ) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        TaskState taskState = TaskState.fromSession(session);
        String goal = !rawGoal.isBlank() ? rawGoal : !taskState.goal().isBlank() ? taskState.goal() : "Plan current task";
        SubAgentTask task = orchestrator.createPlannerTask(goal, taskState.renderStatus());
        SubAgentResult result = new SubAgentResult(
                task.id(),
                SubAgentRole.PLANNER,
                "Planner scoped the task and proposed a small-step execution path.",
                List.of("Goal: " + goal, "Next action: " + (!taskState.nextAction().isBlank() ? taskState.nextAction() : "identify the next smallest safe step")),
                taskState.blockedReason().isBlank() ? List.of() : List.of(taskState.blockedReason()),
                List.of(),
                task.relatedFiles(),
                0.62d,
                null
        );
        orchestrator.recordResult(ctx.getKey(), result);
        return completedReply(ctx, renderSubAgentCreated(task, result));
    }

    private CompletableFuture<OutboundMessage> subagentExplore(
            CommandRouter.CommandContext ctx,
            SubAgentOrchestrator orchestrator,
            String goal
    ) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        String actualGoal = !goal.isBlank() ? goal : "Explore current task context";
        List<String> relatedFiles = pathLike(actualGoal);
        List<String> sources = contextSourcePaths(session);
        SubAgentTask task = orchestrator.createExplorerTask(actualGoal, relatedFiles, sources);
        SubAgentResult result = new SubAgentResult(
                task.id(),
                SubAgentRole.EXPLORER,
                "Explorer summarized likely files and context sources for the goal.",
                List.of(
                        relatedFiles.isEmpty() ? "No explicit related files in the request." : "Related files: " + String.join(", ", relatedFiles),
                        sources.isEmpty() ? "No prior context sources recorded." : "Context sources available: " + String.join(", ", sources.stream().limit(5).toList())
                ),
                List.of("Exploration is a structured placeholder; verify by reading code before edits."),
                List.of(),
                relatedFiles,
                0.58d,
                null
        );
        orchestrator.recordResult(ctx.getKey(), result);
        return completedReply(ctx, renderSubAgentCreated(task, result));
    }

    private CompletableFuture<OutboundMessage> subagentReview(
            CommandRouter.CommandContext ctx,
            SubAgentOrchestrator orchestrator
    ) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);
        SubAgentTask task = orchestrator.createReviewerTask(null, summary, verifiedExperienceSources(session));
        List<String> findings = !summary.diffReviews().isEmpty()
                ? summary.diffReviews()
                : List.of("No DiffReview recorded yet; review current summary and tool trace first.");
        List<String> risks = !summary.blockers().isEmpty()
                ? summary.blockers()
                : !summary.rollbackHints().isEmpty()
                ? summary.rollbackHints()
                : List.of("No explicit blocker recorded.");
        SubAgentResult result = new SubAgentResult(
                task.id(),
                SubAgentRole.REVIEWER,
                "Reviewer summarized current diff/task risks and follow-up tests.",
                findings,
                risks,
                summary.suggestedTests(),
                summary.changedFiles(),
                0.64d,
                null
        );
        orchestrator.recordResult(ctx.getKey(), result);
        return completedReply(ctx, renderSubAgentCreated(task, result));
    }

    private String renderSubAgentCreated(SubAgentTask task, SubAgentResult result) {
        return "subagent task created\n"
                + "id: " + task.id() + "\n"
                + "role: " + task.role() + "\n"
                + "status: " + task.status() + "\n"
                + "note: notes/temporary\n\n"
                + "subagent result recorded\n"
                + SubAgentOrchestrator.renderDetail(result);
    }

    private String renderSubAgentList(List<SubAgentResult> results) {
        if (results == null || results.isEmpty()) {
            return "No subagent results.";
        }
        StringBuilder sb = new StringBuilder("subagent results (" + results.size() + ")\n");
        for (SubAgentResult result : results) {
            sb.append("- ").append(result.taskId())
                    .append(" [").append(result.role()).append("] ")
                    .append(result.summary())
                    .append(" confidence=")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", result.confidence()))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String renderSubAgentShow(List<SubAgentResult> results, String id) {
        for (SubAgentResult result : results != null ? results : List.<SubAgentResult>of()) {
            if (result.taskId().equals(id)) {
                return SubAgentOrchestrator.renderDetail(result);
            }
        }
        return "SubAgent result not found: " + id;
    }

    private CompletableFuture<OutboundMessage> team(CommandRouter.CommandContext ctx) {
        String args = trim(ctx.getArgs());
        String action = args.isBlank() ? "status" : args.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        try {
            return switch (action) {
                case "start" -> teamStart(ctx, afterCommand(args));
                case "status" -> teamStatus(ctx);
                case "list" -> teamList(ctx);
                case "resume" -> teamResume(ctx, afterCommand(args));
                case "archive" -> teamArchive(ctx, afterCommand(args));
                case "suggest" -> teamSuggest(ctx, afterCommand(args));
                case "suggest-current" -> teamSuggestCurrent(ctx);
                case "run" -> teamRun(ctx, afterCommand(args));
                case "run-worker" -> teamRunWorker(ctx, afterCommand(args));
                case "run-verifier" -> teamRunVerifier(ctx, afterCommand(args));
                case "worker-report" -> teamWorkerReport(ctx, afterCommand(args));
                case "report" -> teamTaskReport(ctx, afterCommand(args));
                case "tool-call" -> teamToolCall(ctx, afterCommand(args));
                case "plan-steps" -> teamPlanSteps(ctx, afterCommand(args));
                case "steps" -> teamSteps(ctx, afterCommand(args));
                case "show-step" -> teamShowStep(ctx, afterCommand(args));
                case "next-step" -> teamNextStep(ctx, afterCommand(args));
                case "update-step" -> teamUpdateStep(ctx, afterCommand(args));
                case "apply-step" -> teamApplyStep(ctx, afterCommand(args));
                case "reject-step" -> teamRejectStep(ctx, afterCommand(args));
                case "step-timeline" -> teamStepTimeline(ctx, afterCommand(args));
                case "task-timeline", "audit" -> teamTaskTimeline(ctx, afterCommand(args));
                case "auto-verify" -> teamAutoVerify(ctx, afterCommand(args));
                case "verifier-report" -> teamVerifierReport(ctx, afterCommand(args));
                case "task" -> teamTask(ctx, afterCommand(args));
                case "verify" -> teamVerify(ctx, afterCommand(args));
                case "events" -> teamEvents(ctx);
                case "whiteboard" -> teamWhiteboard(ctx);
                case "abort" -> teamAbort(ctx, afterCommand(args));
                default -> completedReply(ctx, "用法：/team start <goal>|status|list|resume <sessionId>|archive <sessionId>|suggest <goal>|suggest-current|run <task> [--worktree] [--verify]|task <role> <goal>|run-worker <taskId>|run-verifier <taskId>|worker-report <taskId>|report <taskId>|tool-call <taskId> <toolName> <jsonArgs>|plan-steps <taskId>|steps <taskId>|show-step <stepId>|next-step <taskId>|update-step <stepId> <jsonUpdate>|apply-step <stepId>|reject-step <stepId>|step-timeline <stepId>|task-timeline <taskId>|audit <taskId>|auto-verify <taskId>|verifier-report <taskId>|verify <taskId> pass|reject|needs-human <reason>|events|whiteboard|abort <taskId>");
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return completedReply(ctx, "team error: " + e.getMessage());
        }
    }

    private CompletableFuture<OutboundMessage> teamStart(CommandRouter.CommandContext ctx, String rawGoal) {
        String goal = trim(rawGoal);
        if (goal.isBlank()) {
            throw new IllegalArgumentException("missing team goal");
        }
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        TeamSession teamSession = teamEngine.createSession(goal);
        storeTeamContext(session, teamSession.id());
        return completedReply(ctx, "team session started\n"
                + "id: " + teamSession.id() + "\n"
                + "state: " + teamSession.state() + "\n"
                + "goal: " + teamSession.goal() + "\n"
                + "whiteboard: " + teamEngine.whiteboard(teamSession.id()).relativeWhiteboardPath());
    }

    private CompletableFuture<OutboundMessage> teamStatus(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        String sessionId = resolveActiveTeamSessionId(session);
        if (sessionId.isBlank()) {
            return completedReply(ctx, "No active team session.");
        }
        storeTeamContext(session, sessionId);
        return completedReply(ctx, teamEngine.getStatus(sessionId));
    }

    private CompletableFuture<OutboundMessage> teamList(CommandRouter.CommandContext ctx) {
        List<TeamSession> sessions = teamEngine.listSessions();
        if (sessions.isEmpty()) {
            return completedReply(ctx, "No team sessions.");
        }
        StringBuilder sb = new StringBuilder("team sessions\n");
        for (TeamSession session : sessions) {
            sb.append("- ").append(session.id())
                    .append(" [").append(session.state()).append("]")
                    .append(teamEngine.isArchived(session.id()) ? " archived=true" : "")
                    .append(" updatedAt=").append(session.updatedAt())
                    .append(" goal=").append(session.goal())
                    .append("\n");
        }
        return completedReply(ctx, sb.toString().trim());
    }

    private CompletableFuture<OutboundMessage> teamResume(CommandRouter.CommandContext ctx, String rawArgs) {
        String sessionId = commandArg(rawArgs, 0);
        TeamSession teamSession = teamEngine.resumeSession(sessionId);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        storeTeamContext(session, teamSession.id());
        return completedReply(ctx, "team session resumed\n"
                + "id: " + teamSession.id() + "\n"
                + "state: " + teamSession.state() + "\n"
                + "goal: " + teamSession.goal() + "\n"
                + "whiteboard: " + teamEngine.whiteboard(teamSession.id()).relativeWhiteboardPath());
    }

    private CompletableFuture<OutboundMessage> teamArchive(CommandRouter.CommandContext ctx, String rawArgs) {
        String sessionId = commandArg(rawArgs, 0);
        TeamSession archived = teamEngine.archiveSession(sessionId);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        if (sessionId.equals(activeTeamSessionId(session))) {
            session.getMetadata().remove(SessionRuntimeKeys.TEAM_SESSION_ID_KEY);
            session.getMetadata().remove(SessionRuntimeKeys.TEAM_CONTEXT_KEY);
            sessionManager.save(session);
        }
        return completedReply(ctx, "team session archived\n"
                + "id: " + archived.id() + "\n"
                + "state: " + archived.state());
    }

    private CompletableFuture<OutboundMessage> teamSuggest(CommandRouter.CommandContext ctx, String rawGoal) {
        String goal = trim(rawGoal);
        if (goal.isBlank()) {
            throw new IllegalArgumentException("missing team goal");
        }
        List<String> files = pathLike(goal);
        String lower = goal.toLowerCase(java.util.Locale.ROOT);
        CommandRiskLevel riskLevel = lower.contains("security")
                || lower.contains("approval")
                || lower.contains("risk")
                || lower.contains("permission")
                || lower.contains("provider")
                || lower.contains("config")
                ? CommandRiskLevel.HIGH
                : CommandRiskLevel.SAFE;
        boolean requiresResearch = lower.contains("research") || lower.contains("explore") || lower.contains("inspect")
                || lower.contains("调查") || lower.contains("研究");
        boolean requiresVerification = lower.contains("verify") || lower.contains("test") || lower.contains("review")
                || lower.contains("测试") || lower.contains("验证");
        int estimatedSteps = Math.max(1, files.size());
        if (lower.contains("state") || lower.contains("flow") || lower.contains("restore") || requiresResearch || requiresVerification) {
            estimatedSteps = Math.max(estimatedSteps, 3);
        }
        TeamDecisionPolicy.Decision decision = new TeamDecisionPolicy().evaluate(
                goal,
                riskLevel,
                files,
                estimatedSteps,
                requiresResearch,
                requiresVerification
        );
        return completedReply(ctx, "team suggestion\n"
                + "useTeam: " + decision.useTeam() + "\n"
                + "riskLevel: " + riskLevel + "\n"
                + "estimatedSteps: " + estimatedSteps + "\n"
                + "reasons: " + (decision.reasons().isEmpty() ? "none" : String.join(", ", decision.reasons())) + "\n"
                + "suggestedRoles: " + (decision.suggestedRoles().isEmpty()
                ? "none"
                : String.join(", ", decision.suggestedRoles().stream().map(Enum::name).toList())));
    }

    private CompletableFuture<OutboundMessage> teamSuggestCurrent(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        resolveActiveTeamSessionId(session);
        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);
        List<String> changedFiles = summary.changedFiles();
        boolean highRiskDiff = containsAny(summary.diffReviews(), "risk=high", "high risk", "blocked", "security", "approval", "provider", "agentloop", "toolregistry", "config", "ci");
        boolean missingSuggestedTests = !summary.suggestedTests().isEmpty()
                && !suggestedTestsCovered(summary.suggestedTests(), summary.testCommands());
        boolean hasVerifierReport = !summary.verifierReports().isEmpty();
        CommandRiskLevel riskLevel = highRiskDiff ? CommandRiskLevel.HIGH : CommandRiskLevel.SAFE;
        int estimatedSteps = Math.max(1, changedFiles.size());
        if (highRiskDiff || missingSuggestedTests || hasVerifierReport) {
            estimatedSteps = Math.max(estimatedSteps, 3);
        }
        TeamDecisionPolicy.Decision decision = new TeamDecisionPolicy().evaluate(
                !summary.goal().isBlank() ? summary.goal() : "Current task",
                riskLevel,
                changedFiles,
                estimatedSteps,
                !summary.diffReviews().isEmpty(),
                highRiskDiff || missingSuggestedTests || hasVerifierReport
        );
        java.util.ArrayList<String> reasons = new java.util.ArrayList<>(decision.reasons());
        if (highRiskDiff && !reasons.contains("high risk diff present")) {
            reasons.add("high risk diff present");
        }
        if (missingSuggestedTests && !reasons.contains("suggested tests are not covered")) {
            reasons.add("suggested tests are not covered");
        }
        if (hasVerifierReport && !reasons.contains("existing verifier report should be reviewed")) {
            reasons.add("existing verifier report should be reviewed");
        }
        java.util.ArrayList<TeamRole> roles = new java.util.ArrayList<>(decision.suggestedRoles());
        if ((highRiskDiff || missingSuggestedTests || hasVerifierReport) && !roles.contains(TeamRole.VERIFIER)) {
            roles.add(TeamRole.VERIFIER);
        }
        if (missingSuggestedTests && !roles.contains(TeamRole.TESTER)) {
            roles.add(TeamRole.TESTER);
        }
        boolean useTeam = decision.useTeam() || highRiskDiff || missingSuggestedTests || hasVerifierReport;
        return completedReply(ctx, "team suggestion\n"
                + "useTeam: " + useTeam + "\n"
                + "riskLevel: " + riskLevel + "\n"
                + "changedFiles: " + (changedFiles.isEmpty() ? "none" : String.join(", ", changedFiles)) + "\n"
                + "reasons: " + (reasons.isEmpty() ? "none" : String.join(", ", reasons)) + "\n"
                + "suggestedRoles: " + (roles.isEmpty() ? "none" : String.join(", ", roles.stream().map(Enum::name).toList())));
    }

    private CompletableFuture<OutboundMessage> teamRun(CommandRouter.CommandContext ctx, String rawArgs) {
        String args = trim(rawArgs);
        boolean useWorktree = containsFlag(args, "--worktree");
        boolean verify = containsFlag(args, "--verify");
        String taskValue = stripFlags(args, "--worktree", "--verify");
        if (taskValue.isBlank()) {
            throw new IllegalArgumentException("missing team run task");
        }
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        String activeTeamId = resolveActiveTeamSessionId(session);
        TeamExecutionService service = new TeamExecutionService(workspace, teamEngine);
        TeamExecutionService.TeamExecutionResult result;
        if (taskValue.startsWith("teamtask_") && !taskValue.contains(" ")) {
            result = service.runTask(taskValue, new TeamExecutionService.TeamExecutionOptions(useWorktree, verify));
        } else {
            result = service.runUserTask(activeTeamId, taskValue, new TeamExecutionService.TeamExecutionOptions(useWorktree, verify));
        }
        storeTeamContext(session, result.teamSessionId());
        if (!result.workspaceSessionId().isBlank()) {
            WorkspaceSession workspaceSession = new WorkspaceSessionStore(workspace).load(result.workspaceSessionId());
            if (workspaceSession != null) {
                storeWorkspaceContext(session, workspaceSession, new WorkspaceRenderer());
            }
        }
        return completedReply(ctx, renderTeamExecutionResult(result));
    }

    private CompletableFuture<OutboundMessage> teamTask(CommandRouter.CommandContext ctx, String rawArgs) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        String sessionId = requireActiveTeamSessionId(session);
        String roleRaw = commandArg(rawArgs, 0);
        TeamRole role = parseTeamRole(roleRaw);
        String goal = afterCommand(rawArgs);
        if (goal.isBlank()) {
            throw new IllegalArgumentException("missing team task goal");
        }
        TeamTask task = teamEngine.createTask(sessionId, role, goal);
        storeTeamContext(session, sessionId);
        return completedReply(ctx, "team task created\n"
                + "id: " + task.id() + "\n"
                + "role: " + task.role() + "\n"
                + "state: " + task.state() + "\n"
                + "goal: " + task.goal() + "\n"
                + "whiteboard: " + teamEngine.whiteboard(sessionId).relativeWhiteboardPath());
    }

    private CompletableFuture<OutboundMessage> teamVerify(CommandRouter.CommandContext ctx, String rawArgs) {
        String[] parts = trim(rawArgs).split("\\s+", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("usage: /team verify <taskId> pass|reject|needs-human <reason>");
        }
        String taskId = parts[0];
        String reason = parts.length >= 3 && !parts[2].isBlank() ? parts[2].trim() : "manual verifier result";
        VerificationResult verification = switch (parts[1].toLowerCase(java.util.Locale.ROOT)) {
            case "pass", "passed" -> VerificationResult.pass(reason);
            case "reject", "rejected" -> VerificationResult.reject(reason);
            case "needs-human", "needs_human", "human" -> VerificationResult.needsHuman(reason);
            default -> throw new IllegalArgumentException("verification status must be pass, reject, or needs-human");
        };
        teamEngine.startVerifying(taskId);
        TeamTask task = teamEngine.submitVerification(taskId, verification);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        storeTeamContext(session, task.sessionId());
        return completedReply(ctx, "team verification recorded\n"
                + "taskId: " + task.id() + "\n"
                + "state: " + task.state() + "\n"
                + "status: " + task.verificationResult().status() + "\n"
                + "reason: " + task.verificationResult().reason()
                + (!task.revisionRequest().isBlank() ? "\nrevisionRequest: " + task.revisionRequest() : ""));
    }

    private CompletableFuture<OutboundMessage> teamRunWorker(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        resolveActiveTeamSessionId(session);
        TeamTask task = teamEngine.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        WorkerExecutionInput input = workerExecutionInput(task, session, false);
        WorkerExecutionResult result = teamEngine.runWorker(taskId, input);
        if (result.role() == TeamRole.DEVELOPER) {
            session.getMetadata().put(SessionRuntimeKeys.DEVELOPER_TASK_ID_KEY, task.id());
            sessionManager.save(session);
        }
        storeTeamContext(session, task.sessionId());
        traceEvent(session, TraceEventType.WORKER_FINISHED, "team", "team worker executed", workerTracePayload(result), task.sessionId(), "", "");
        return completedReply(ctx, "team worker executed\n" + renderWorkerExecutionResult(result, teamEngine.findTask(taskId)));
    }

    private CompletableFuture<OutboundMessage> teamRunVerifier(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        resolveActiveTeamSessionId(session);
        TeamTask task = teamEngine.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        WorkerExecutionInput input = workerExecutionInput(task, session, true);
        WorkerExecutionResult result = teamEngine.runVerifier(taskId, input);
        TeamTask updated = teamEngine.findTask(taskId);
        ChangeSetService changeSetService = new ChangeSetService(workspace);
        GitChangeSet latest = changeSetService.latest();
        String activeWorkspaceId = activeWorkspaceSessionId(session);
        boolean activeDiff = activeWorkspaceHasDiff(session);
        boolean latestMatchesActiveWorkspace = latest != null && (activeWorkspaceId.isBlank() || activeWorkspaceId.equals(latest.workspaceSessionId()));
        if (activeDiff && !latestMatchesActiveWorkspace) {
            VerificationResult reject = new VerificationResult(
                    VerificationResult.Status.REJECT,
                    "active workspace diff requires ChangeSet before verifier acceptance",
                    "Verifier rejected active workspace diff until a ChangeSet is created.",
                    result.suggestedTests(),
                    CommandRiskLevel.MEDIUM,
                    List.of("active workspace has diff but no matching ChangeSet"),
                    List.of(),
                    List.of("active workspace diff"),
                    List.of("Run /change create for active workspace changes before accepting verification."),
                    List.of(),
                    false,
                    0.72d,
                    null
            );
            updated = teamEngine.submitVerification(taskId, reject);
            traceEvent(session, TraceEventType.WORKSPACE_DIFF_REQUIRES_CHANGESET, "verifier", "workspace diff requires changeset", Map.of(
                    "taskId", taskId,
                    "teamSessionId", task.sessionId(),
                    "workspaceSessionId", activeWorkspaceId,
                    "workspacePath", input.workspacePath(),
                    "requiredAction", "/change create"
            ), task.sessionId(), "", "");
        }
        if (latest != null && updated != null && updated.verificationResult() != null
                && latestMatchesActiveWorkspace
                && latest.verifierStatus().isBlank()) {
            changeSetService.attachVerifierResult(latest.id(), updated.verificationResult());
        }
        if (updated != null && updated.verificationResult() != null) {
            teamEngine.recordStepAudit(new StepAuditRecord(null, "", task.id(), task.sessionId(),
                    StepAuditEventType.STEP_VERIFIED, "", updated.state().name(),
                    "Verifier result linked to implementation task.", "", "", "",
                    latestMatchesActiveWorkspace && latest != null ? latest.id() : "",
                    updated.verificationResult().status().name(), "", null,
                    Map.of("reason", updated.verificationResult().reason())));
        }
        storeTeamContext(session, task.sessionId());
        traceEvent(session, TraceEventType.VERIFIER_FINISHED, "team", "team verifier executed", workerTracePayload(result), task.sessionId(), latestMatchesActiveWorkspace && latest != null ? latest.id() : "", "");
        String changeHint = activeDiff
                ? "\nchangeSetHint: active workspace has diff; run /change create"
                : "";
        String workspaceSource = !activeWorkspaceId.isBlank() ? "\nworkspaceSource: active workspace " + activeWorkspaceId : "\nworkspaceSource: base workspace";
        return completedReply(ctx, "team verifier executed\n" + renderWorkerExecutionResult(result, updated) + workspaceSource + changeHint);
    }

    private CompletableFuture<OutboundMessage> teamWorkerReport(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        resolveActiveTeamSessionId(session);
        List<WorkerExecutionResult> reports = teamEngine.workerReports(taskId);
        if (reports.isEmpty()) {
            return completedReply(ctx, "No worker report for task: " + taskId);
        }
        StringBuilder sb = new StringBuilder("team worker report\n");
        for (WorkerExecutionResult result : reports) {
            sb.append(renderWorkerExecutionResult(result, teamEngine.findTask(taskId))).append("\n\n");
        }
        return completedReply(ctx, sb.toString().trim());
    }

    private CompletableFuture<OutboundMessage> teamTaskReport(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        TeamTask task = teamEngine.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        storeTeamContext(session, task.sessionId());
        TeamTaskReport report = teamEngine.taskReport(taskId);
        if (teamReportFlags(rawArgs).contains("--json")) {
            try {
                return completedReply(ctx, MAPPER.writeValueAsString(report.toMap()));
            } catch (Exception e) {
                throw new IllegalStateException("failed to render team task report json: " + e.getMessage());
            }
        }
        return completedReply(ctx, renderTeamTaskReport(report));
    }

    private CompletableFuture<OutboundMessage> teamToolCall(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        String toolName = commandArg(rawArgs, 1);
        String jsonArgs = afterNthArg(rawArgs, 2);
        if (jsonArgs.isBlank()) {
            throw new IllegalArgumentException("missing jsonArgs");
        }
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        resolveActiveTeamSessionId(session);
        TeamTask task = teamEngine.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        Map<String, Object> args = parseJsonArgs(jsonArgs);
        WorkspaceSession workspaceSession = activeWorkspaceSession(session);
        PolicyAwareToolExecutor executor = new PolicyAwareToolExecutor(
                new PolicyEngine(workspace),
                toolRegistry,
                approvalService,
                traceStore
        );
        PolicyAwareToolExecutor.PolicyToolResult result = teamEngine.executeToolAsRole(
                task.id(),
                executor,
                args,
                workspaceSession,
                session.getKey(),
                toolName
        );
        if (task.role() == TeamRole.DEVELOPER) {
            session.getMetadata().put(SessionRuntimeKeys.DEVELOPER_TASK_ID_KEY, task.id());
            sessionManager.save(session);
            if (result.decision().requiresApproval()) {
                traceEvent(session, TraceEventType.DEVELOPER_TOOL_APPROVAL_REQUIRED, "developer", "developer tool approval required", Map.of(
                        "taskId", task.id(),
                        "teamSessionId", task.sessionId(),
                        "toolName", result.decision().toolName(),
                        "requestId", result.approvalRequestId(),
                        "workspaceSessionId", workspaceSession != null ? workspaceSession.id() : "",
                        "workspacePath", workspaceSession != null ? workspaceSession.workspacePath() : workspace.toString()
                ), task.sessionId(), "", result.approvalRequestId());
            }
        }
        WorkerExecutionResult report = roleToolCallReport(task, result, workspaceSession);
        teamEngine.recordRoleToolCall(task.id(), report);
        storeTeamContext(session, task.sessionId());
        return completedReply(ctx, renderPolicyToolResult(result, report));
    }

    private CompletableFuture<OutboundMessage> teamPlanSteps(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        TeamTask task = teamEngine.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        List<PendingImplementationStep> steps = teamEngine.createImplementationSteps(taskId);
        storeTeamContext(session, task.sessionId());
        for (PendingImplementationStep step : steps) {
            traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_CREATED, step);
        }
        return completedReply(ctx, "implementation steps planned\n" + renderImplementationSteps(steps));
    }

    private CompletableFuture<OutboundMessage> teamSteps(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        TeamTask task = teamEngine.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        List<PendingImplementationStep> steps = teamEngine.listImplementationSteps(taskId);
        storeTeamContext(session, task.sessionId());
        return completedReply(ctx, steps.isEmpty() ? "No implementation steps for task: " + taskId : "implementation steps\n" + renderImplementationSteps(steps));
    }

    private CompletableFuture<OutboundMessage> teamShowStep(CommandRouter.CommandContext ctx, String rawArgs) {
        PendingImplementationStep step = requireImplementationStep(commandArg(rawArgs, 0));
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        StepGateResult gate = teamEngine.checkImplementationStepGate(step.id(), stepGateContext(session, step));
        return completedReply(ctx, renderImplementationStepDetail(step) + "\n\n" + renderStepGate(gate));
    }

    private CompletableFuture<OutboundMessage> teamNextStep(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        TeamTask task = teamEngine.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        List<PendingImplementationStep> steps = teamEngine.listImplementationSteps(taskId);
        PendingImplementationStep next = new ImplementationStepGate().nextStep(steps, stepGateContext(session, null));
        storeTeamContext(session, task.sessionId());
        if (next == null) {
            return completedReply(ctx, "No pending implementation step for task: " + taskId);
        }
        StepGateResult gate = teamEngine.checkImplementationStepGate(next.id(), stepGateContext(session, next));
        return completedReply(ctx, "next implementation step\n" + renderImplementationStepDetail(next) + "\n\n" + renderStepGate(gate));
    }

    private CompletableFuture<OutboundMessage> teamUpdateStep(CommandRouter.CommandContext ctx, String rawArgs) {
        String stepId = commandArg(rawArgs, 0);
        String jsonUpdate = afterNthArg(rawArgs, 1);
        if (jsonUpdate.isBlank()) {
            throw new IllegalArgumentException("missing jsonUpdate");
        }
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        PendingImplementationStep before = requireImplementationStep(stepId);
        StepUpdateRequest request = StepUpdateRequest.fromMap(parseJsonArgs(jsonUpdate));
        PendingImplementationStep updated = teamEngine.updateImplementationStep(stepId, request, stepGateContext(session, before), "user");
        storeTeamContext(session, updated.teamSessionId());
        traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_UPDATED, updated);
        if (!updated.validationErrors().isEmpty()) {
            traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_VALIDATION_FAILED, updated);
        } else if (updated.status() == ImplementationStepStatus.READY) {
            traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_READY, updated);
        }
        return completedReply(ctx, "implementation step updated\n"
                + "updatedFields: " + renderListInline(request.updatedFields()) + "\n"
                + renderImplementationStepDetail(updated));
    }

    private CompletableFuture<OutboundMessage> teamRejectStep(CommandRouter.CommandContext ctx, String rawArgs) {
        PendingImplementationStep rejected = teamEngine.rejectImplementationStep(commandArg(rawArgs, 0));
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        storeTeamContext(session, rejected.teamSessionId());
        traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_REJECTED, rejected);
        return completedReply(ctx, "implementation step rejected\n" + renderImplementationStepDetail(rejected));
    }

    private CompletableFuture<OutboundMessage> teamStepTimeline(CommandRouter.CommandContext ctx, String rawArgs) {
        String stepId = commandArg(rawArgs, 0);
        PendingImplementationStep step = requireImplementationStep(stepId);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        storeTeamContext(session, step.teamSessionId());
        return completedReply(ctx, teamEngine.renderStepTimeline(stepId));
    }

    private CompletableFuture<OutboundMessage> teamTaskTimeline(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        TeamTask task = teamEngine.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        storeTeamContext(session, task.sessionId());
        List<String> flags = teamAuditFlags(rawArgs);
        boolean compact = flags.contains("--compact");
        boolean json = flags.contains("--json");
        if (json && compact) {
            return completedReply(ctx, teamEngine.renderJsonCompactTaskAudit(taskId));
        }
        if (json) {
            return completedReply(ctx, teamEngine.renderJsonTaskAudit(taskId));
        }
        return completedReply(ctx, teamEngine.renderTaskAudit(taskId, compact));
    }

    private CompletableFuture<OutboundMessage> teamApplyStep(CommandRouter.CommandContext ctx, String rawArgs) {
        PendingImplementationStep step = requireImplementationStep(commandArg(rawArgs, 0));
        if (step.status() == ImplementationStepStatus.REJECTED || step.status() == ImplementationStepStatus.APPLIED) {
            return completedReply(ctx, "implementation step not applicable\n" + renderImplementationStepDetail(step));
        }
        if (step.status() == ImplementationStepStatus.DRAFT) {
            List<String> errors = new ImplementationStepGate().validateFields(step);
            return completedReply(ctx, "implementation step is DRAFT; run /team update-step before apply-step\n"
                    + "validationErrors: " + renderListInline(errors.isEmpty() ? step.validationErrors() : errors) + "\n\n"
                    + renderImplementationStepDetail(step));
        }
        if (step.status() == ImplementationStepStatus.BLOCKED) {
            return completedReply(ctx, "implementation step is BLOCKED\n" + renderImplementationStepDetail(step));
        }
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        StepGateResult gate = teamEngine.checkImplementationStepGate(step.id(), stepGateContext(session, step));
        traceImplementationStepGate(session, TraceEventType.IMPLEMENTATION_STEP_GATE_CHECKED, step, gate);
        if (gate.blocked()) {
            PendingImplementationStep blocked = teamEngine.blockImplementationStep(step.id(), gate);
            storeTeamContext(session, blocked.teamSessionId());
            traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_BLOCKED, blocked);
            traceImplementationStepGate(session, TraceEventType.IMPLEMENTATION_STEP_BLOCKED, blocked, gate);
            return completedReply(ctx, "implementation step blocked\n"
                    + renderImplementationStepDetail(blocked)
                    + "\n\n" + renderStepGate(gate));
        }
        return switch (step.type()) {
            case READ, EDIT, WRITE, EXEC_TEST -> applyToolBackedStep(ctx, step);
            case CREATE_CHANGESET -> applyCreateChangeSetStep(ctx, step);
            case RUN_VERIFIER -> teamRunVerifier(ctx, step.taskId()).thenApply(message -> {
                PendingImplementationStep applied = teamEngine.applyImplementationStep(step.id());
                Session currentSession = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
                storeTeamContext(currentSession, applied.teamSessionId());
                return OutboundMessages.of(ctx.getMsg().getChannel(), ctx.getMsg().getChatId(),
                        message.getContent() + "\n\nimplementation step applied\n" + renderImplementationStepDetail(applied));
            });
        };
    }

    private CompletableFuture<OutboundMessage> applyToolBackedStep(CommandRouter.CommandContext ctx, PendingImplementationStep step) {
        if (step.status() == ImplementationStepStatus.DRAFT && (step.type() == ImplementationStepType.EDIT || step.type() == ImplementationStepType.WRITE)) {
            PendingImplementationStep failed = teamEngine.failImplementationStep(step.id(), Map.of("reason", "draft step is missing executable edit/write arguments"));
            return completedReply(ctx, "implementation step failed\n" + renderImplementationStepDetail(failed));
        }
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        WorkspaceSession workspaceSession = activeWorkspaceSession(session);
        Map<String, Object> args = stepArgs(step);
        args.put("__implementation_step_id", step.id());
        PolicyAwareToolExecutor executor = new PolicyAwareToolExecutor(new PolicyEngine(workspace), toolRegistry, approvalService, traceStore);
        PolicyAwareToolExecutor.PolicyToolResult result = executor.execute(
                step.role(),
                toolNameForStep(step),
                args,
                workspaceSession,
                session.getKey(),
                step.teamSessionId(),
                step.taskId()
        );
        PendingImplementationStep updated;
        if (result.decision().denied()) {
            updated = teamEngine.failImplementationStep(step.id(), result.decision().toMap());
            traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_FAILED, updated);
        } else if (result.decision().requiresApproval()) {
            updated = teamEngine.markImplementationStepApprovalRequired(step.id(), result.decision().toMap());
            teamEngine.recordStepAudit(new StepAuditRecord(null, updated.id(), updated.taskId(), updated.teamSessionId(),
                    StepAuditEventType.STEP_APPROVAL_REQUIRED, step.status().name(), updated.status().name(),
                    "Implementation step approval required.", result.approvalRequestId(), toolNameForStep(step), "",
                    "", "", "", null, result.decision().toMap()));
            traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_APPROVAL_REQUIRED, updated);
        } else if (result.executed()) {
            updated = teamEngine.applyImplementationStep(step.id());
            teamEngine.recordStepAudit(new StepAuditRecord(null, updated.id(), updated.taskId(), updated.teamSessionId(),
                    StepAuditEventType.STEP_TOOL_APPLIED, step.status().name(), updated.status().name(),
                    "Implementation step tool applied.", "", toolNameForStep(step), result.resultSummary(),
                    "", "", "", null, Map.of()));
            traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_APPLIED, updated);
        } else {
            updated = teamEngine.failImplementationStep(step.id(), result.decision().toMap());
            traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_FAILED, updated);
        }
        storeTeamContext(session, updated.teamSessionId());
        return completedReply(ctx, "implementation step apply result\n"
                + "policy decision: " + result.decision().decisionType() + "\n"
                + "reasons: " + renderListInline(result.decision().reasons()) + "\n"
                + (!result.approvalRequestId().isBlank() ? "approval requestId: " + result.approvalRequestId() + "\n" : "")
                + "tool result: " + (result.resultSummary().isBlank() ? "none" : result.resultSummary()) + "\n\n"
                + renderImplementationStepDetail(updated));
    }

    private CompletableFuture<OutboundMessage> applyCreateChangeSetStep(CommandRouter.CommandContext ctx, PendingImplementationStep step) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        ChangeSetService service = new ChangeSetService(workspace);
        ChangeSetRenderer renderer = new ChangeSetRenderer();
        WorkspaceSession activeWorkspace = activeWorkspaceSession(session);
        GitChangeSet changeSet = activeWorkspace != null
                ? service.createFromWorkspace(activeWorkspace.id(), Path.of(activeWorkspace.workspacePath()), ctx.getKey(), step.teamSessionId(), step.taskId())
                : service.createFromWorkingTree(ctx.getKey(), step.teamSessionId(), step.taskId());
        teamEngine.recordArtifact(step.teamSessionId(), new TeamArtifact(null, step.taskId(), ".changesets/" + changeSet.id() + "/changeset.json", "ChangeSet " + changeSet.id(), "changeset", null));
        storeChangeSetContext(session, changeSet, renderer);
        PendingImplementationStep applied = teamEngine.applyImplementationStep(step.id());
        teamEngine.recordStepAudit(new StepAuditRecord(null, applied.id(), applied.taskId(), applied.teamSessionId(),
                StepAuditEventType.STEP_CHANGESET_LINKED, step.status().name(), applied.status().name(),
                "ChangeSet linked to implementation step.", "", "", "",
                changeSet.id(), "", "", null, Map.of("changedFiles", changeSet.changedFiles())));
        storeTeamContext(session, step.teamSessionId());
        traceImplementationStep(session, TraceEventType.IMPLEMENTATION_STEP_APPLIED, applied);
        traceEvent(session, activeWorkspace != null ? TraceEventType.CHANGESET_CREATED_FROM_WORKSPACE : TraceEventType.CHANGESET_CREATED, "change", "changeset created from implementation step", Map.of(
                "stepId", step.id(),
                "status", changeSet.status().name(),
                "changedFiles", changeSet.changedFiles(),
                "workspaceSessionId", changeSet.workspaceSessionId(),
                "workspacePath", changeSet.workspacePath()
        ), changeSet.teamSessionId(), changeSet.id(), "");
        return completedReply(ctx, "implementation step applied\n"
                + renderImplementationStepDetail(applied)
                + "\n\nchangeset created\nid: " + changeSet.id() + "\n" + renderer.renderStatus(changeSet));
    }

    private CompletableFuture<OutboundMessage> teamAutoVerify(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        resolveActiveTeamSessionId(session);
        TeamTask existing = teamEngine.findTask(taskId);
        if (existing == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);
        VerificationInput input = verificationInput(existing, summary, session);
        TeamTask task = teamEngine.autoVerify(taskId, input);
        storeTeamContext(session, task.sessionId());
        VerificationResult result = task.verificationResult();
        String changeHint = result.status() == VerificationResult.Status.PASS && new ChangeSetService(workspace).hasWorkingTreeChanges()
                ? "\nchangeSetHint: working tree has changes; run /change create"
                : "";
        return completedReply(ctx, "team auto verification recorded\n"
                + "taskId: " + task.id() + "\n"
                + "state: " + task.state() + "\n"
                + "status: " + result.status() + "\n"
                + "riskLevel: " + result.riskLevel() + "\n"
                + "reasons: " + renderListInline(result.reasons()) + "\n"
                + "missingTests: " + renderListInline(result.missingTests()) + "\n"
                + "requiredActions: " + renderListInline(result.requiredActions())
                + changeHint);
    }

    private CompletableFuture<OutboundMessage> teamVerifierReport(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        resolveActiveTeamSessionId(session);
        List<Map<String, Object>> reports = teamEngine.verificationReports(taskId);
        if (reports.isEmpty()) {
            return completedReply(ctx, "No verifier report for task: " + taskId);
        }
        return completedReply(ctx, renderVerifierReports(reports));
    }

    private CompletableFuture<OutboundMessage> teamEvents(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        String sessionId = requireActiveTeamSessionId(session);
        storeTeamContext(session, sessionId);
        List<TeamEvent> events = teamEngine.listEvents(sessionId);
        if (events.isEmpty()) {
            return completedReply(ctx, "No team events.");
        }
        StringBuilder sb = new StringBuilder("team events\n");
        for (TeamEvent event : events) {
            sb.append("- ").append(event.createdAt())
                    .append(" ").append(event.type())
                    .append(" role=").append(event.role())
                    .append(!event.taskId().isBlank() ? " task=" + event.taskId() : "")
                    .append(" ").append(event.message())
                    .append("\n");
        }
        return completedReply(ctx, sb.toString().trim());
    }

    private CompletableFuture<OutboundMessage> teamWhiteboard(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        String sessionId = requireActiveTeamSessionId(session);
        storeTeamContext(session, sessionId);
        String summary = teamEngine.whiteboard(sessionId).readSummary();
        return completedReply(ctx, "team whiteboard\n"
                + "path: " + teamEngine.whiteboard(sessionId).relativeWhiteboardPath()
                + "\n\n" + (summary.isBlank() ? "(empty)" : summary));
    }

    private CompletableFuture<OutboundMessage> teamAbort(CommandRouter.CommandContext ctx, String rawArgs) {
        String taskId = commandArg(rawArgs, 0);
        TeamTask task = teamEngine.abortTask(taskId);
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        storeTeamContext(session, task.sessionId());
        return completedReply(ctx, "team task aborted\n"
                + "taskId: " + task.id() + "\n"
                + "state: " + task.state());
    }

    private TeamRole parseTeamRole(String raw) {
        try {
            return TeamRole.valueOf(trim(raw).replace('-', '_').toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("unknown team role: " + raw);
        }
    }

    private VerificationInput verificationInput(TeamTask task, TaskSummaryService.TaskSummary summary, Session session) {
        return new VerificationInput(
                task.id(),
                task.goal(),
                task.summary(),
                summary.diffReviews(),
                renderTaskSummaryForVerifier(summary),
                summary.approvalRecords(),
                summary.suggestedTests(),
                summary.testCommands(),
                verifiedExperienceSources(session),
                teamEngine.whiteboard(task.sessionId()).readSummary()
        );
    }

    private WorkerExecutionInput workerExecutionInput(TeamTask task, Session session, boolean verifier) {
        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);
        String workspacePath = activeWorkspacePath(session);
        List<String> findings = new java.util.ArrayList<>();
        findings.addAll(summary.diffReviews());
        findings.addAll(summary.changeSetSummaries());
        if (!summary.traceSummary().isBlank()) {
            findings.add("trace=" + abbreviate(summary.traceSummary(), 260));
        }
        List<String> risks = new java.util.ArrayList<>(summary.blockers());
        if (activeWorkspaceHasDiff(session)) {
            risks.add("active workspace has diff; create ChangeSet before final acceptance");
        }
        return new WorkerExecutionInput(
                task.id(),
                task.sessionId(),
                verifier ? TeamRole.VERIFIER : task.role(),
                task.goal(),
                workspacePath,
                teamEngine.whiteboard(task.sessionId()).readSummary() + "\n" + renderTaskSummaryForVerifier(summary),
                summary.changedFiles(),
                verifiedExperienceSources(session),
                summary.testCommands(),
                !task.summary().isBlank() ? task.summary() : summary.goal(),
                findings,
                risks,
                summary.suggestedTests(),
                List.of(),
                0d,
                ""
        );
    }

    private String activeWorkspacePath(Session session) {
        String id = activeWorkspaceSessionId(session);
        if (!id.isBlank()) {
            try {
                WorkspaceSession workspaceSession = new WorkspaceSessionStore(workspace).load(id);
                if (workspaceSession != null && !workspaceSession.workspacePath().isBlank()) {
                    return workspaceSession.workspacePath();
                }
            } catch (Exception ignored) {
            }
        }
        return workspace.toString();
    }

    private WorkspaceSession activeWorkspaceSession(Session session) {
        String id = activeWorkspaceSessionId(session);
        if (id.isBlank()) {
            return null;
        }
        try {
            return new WorkspaceSessionStore(workspace).load(id);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean activeWorkspaceHasDiff(Session session) {
        String id = activeWorkspaceSessionId(session);
        if (id.isBlank()) {
            return false;
        }
        try {
            WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
            ricbot.domain.workspace.WorkspaceSession workspaceSession = store.load(id);
            if (workspaceSession == null) {
                return false;
            }
            String diff = backendFor(workspaceSession, store).diff(id);
            return diff != null && !diff.isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private ImplementationStepGate.GateContext stepGateContext(Session session, PendingImplementationStep step) {
        return new ImplementationStepGate.GateContext(
                activeWorkspaceHasDiff(session) || new ChangeSetService(workspace).hasWorkingTreeChanges(),
                changeSetExistsFor(step)
        );
    }

    private boolean changeSetExistsFor(PendingImplementationStep step) {
        try {
            GitChangeSet latest = new ChangeSetService(workspace).latest();
            if (latest == null) {
                return false;
            }
            if (step == null) {
                return true;
            }
            return step.teamSessionId().isBlank()
                    || latest.teamSessionId().isBlank()
                    || step.teamSessionId().equals(latest.teamSessionId());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String renderWorkerExecutionResult(WorkerExecutionResult result, TeamTask task) {
        return "taskId: " + result.taskId() + "\n"
                + "role: " + result.role() + "\n"
                + "workspacePath: " + result.workspacePath() + "\n"
                + "status: " + result.status() + "\n"
                + "summary: " + result.summary() + "\n"
                + "findings: " + renderListInline(result.findings()) + "\n"
                + "risks: " + renderListInline(result.risks()) + "\n"
                + "suggestedTests: " + renderListInline(result.suggestedTests()) + "\n"
                + "policy: " + renderListInline(result.policySummary()) + "\n"
                + "developerPlan: " + renderListInline(result.developerPlan()) + "\n"
                + "requiredApprovals: " + renderListInline(result.requiredApprovals()) + "\n"
                + "nextActions: " + renderListInline(result.nextActions()) + "\n"
                + "changeSetRecommendation: " + (result.changeSetRecommendation().isBlank() ? "none" : result.changeSetRecommendation()) + "\n"
                + "artifacts: " + (result.artifacts().isEmpty() ? "none" : String.join(", ", result.artifacts().stream().map(TeamArtifact::path).toList())) + "\n"
                + "confidence: " + String.format(java.util.Locale.ROOT, "%.2f", result.confidence()) + "\n"
                + "nextState: " + (task != null ? task.state() : "(unknown)");
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

    private String renderImplementationSteps(List<PendingImplementationStep> steps) {
        StringBuilder sb = new StringBuilder();
        List<PendingImplementationStep> safeSteps = steps != null ? steps : List.of();
        sb.append("counts: DRAFT=").append(countSteps(safeSteps, ImplementationStepStatus.DRAFT))
                .append(" READY=").append(countSteps(safeSteps, ImplementationStepStatus.READY))
                .append(" BLOCKED=").append(countSteps(safeSteps, ImplementationStepStatus.BLOCKED))
                .append(" APPLIED=").append(countSteps(safeSteps, ImplementationStepStatus.APPLIED))
                .append("\n");
        for (PendingImplementationStep step : safeSteps) {
            sb.append("- ").append(step.id())
                    .append(" [").append(step.type()).append("] ")
                    .append(step.status())
                    .append(" order=").append(step.orderIndex())
                    .append(!step.targetPath().isBlank() ? " target=" + step.targetPath() : "")
                    .append(!step.command().isBlank() ? " command=" + step.command() : "")
                    .append(!step.dependsOnStepIds().isEmpty() ? " dependsOn=" + String.join(",", step.dependsOnStepIds()) : "")
                    .append(!step.blockedReason().isBlank() ? " blockedReason=" + step.blockedReason() : "")
                    .append(!step.requiredBeforeApply().isEmpty() ? " requiredBeforeApply=" + String.join("; ", step.requiredBeforeApply()) : "")
                    .append(" reason=").append(step.reason())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String renderTeamTaskReport(TeamTaskReport report) {
        return "team task report\n"
                + "taskId: " + report.taskId() + "\n"
                + "teamSessionId: " + (report.teamSessionId().isBlank() ? "none" : report.teamSessionId()) + "\n"
                + "title: " + (report.title().isBlank() ? "none" : report.title()) + "\n"
                + "status: " + report.status() + "\n"
                + "health: " + report.health() + "\n"
                + "progress: " + report.completedSteps() + "/" + report.totalSteps()
                + " failed=" + report.failedSteps()
                + " pending=" + report.pendingSteps() + "\n"
                + "linkedAuditRecords: " + report.linkedAuditRecords() + "\n"
                + "latestEvent: " + (report.latestEvent().isBlank() ? "none" : report.latestEvent()) + "\n"
                + "latestChangeSet: " + (report.latestChangeSet().isBlank() ? "none" : report.latestChangeSet()) + "\n"
                + "latestVerifier: " + (report.latestVerifier().isBlank() ? "none" : report.latestVerifier()) + "\n"
                + "durationMillis: " + report.durationMillis() + "\n"
                + "warnings: " + renderListInline(report.warnings()) + "\n"
                + "suggestedNextActions: " + renderListInline(report.suggestedNextActions());
    }

    private String renderTeamExecutionResult(TeamExecutionService.TeamExecutionResult result) {
        return "team execution\n"
                + "taskId: " + result.taskId() + "\n"
                + "teamSessionId: " + result.teamSessionId() + "\n"
                + "workspaceSessionId: " + (result.workspaceSessionId().isBlank() ? "none" : result.workspaceSessionId()) + "\n"
                + "workspacePath: " + result.workspacePath() + "\n"
                + "workerStatus: " + (result.workerResult() != null ? result.workerResult().status() : "none") + "\n"
                + "verifierStatus: " + (result.verificationResult() != null ? result.verificationResult().status() : "SKIPPED") + "\n"
                + "reportStatus: " + (result.report() != null ? result.report().status() : "UNKNOWN") + "\n"
                + "reportHealth: " + (result.report() != null ? result.report().health() : "UNKNOWN") + "\n"
                + "diffSummary: " + diffSummary(result.diff()) + "\n"
                + (!result.verifierOutput().isBlank() ? "verifierOutput: " + abbreviate(result.verifierOutput(), 500) + "\n" : "")
                + "next: /team report " + result.taskId()
                + (result.usedWorktree() ? " | /workspace diff " + result.workspaceSessionId() + " | /change create" : "");
    }

    private String diffSummary(String diff) {
        if (diff == null || diff.isBlank()) {
            return "none";
        }
        long lines = diff.lines().count();
        return lines + " diff lines";
    }

    private long countSteps(List<PendingImplementationStep> steps, ImplementationStepStatus status) {
        return steps.stream().filter(step -> step.status() == status).count();
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

    private Map<String, Object> workerTracePayload(WorkerExecutionResult result) {
        if (result == null) {
            return Map.of();
        }
        return Map.of(
                "taskId", result.taskId(),
                "teamSessionId", result.teamSessionId(),
                "role", result.role().name(),
                "workspacePath", result.workspacePath(),
                "workspaceSessionId", workspaceSessionIdFromPath(result.workspacePath()),
                "status", result.status(),
                "confidence", result.confidence()
        );
    }

    private String workspaceSessionIdFromPath(String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            return "";
        }
        String normalized = workspacePath.replace('\\', '/');
        int index = normalized.indexOf("/.workspaces/");
        if (index < 0) {
            return "";
        }
        String tail = normalized.substring(index + "/.workspaces/".length());
        int slash = tail.indexOf('/');
        return slash >= 0 ? tail.substring(0, slash) : tail;
    }

    private String renderTaskSummaryForVerifier(TaskSummaryService.TaskSummary summary) {
        if (summary == null) {
            return "";
        }
        return "goal=" + summary.goal()
                + " changedFiles=" + String.join(",", summary.changedFiles())
                + " blockers=" + String.join(",", summary.blockers())
                + " suggestedTests=" + String.join(",", summary.suggestedTests())
                + " executedTests=" + String.join(",", summary.testCommands());
    }

    private String renderVerifierReports(List<Map<String, Object>> reports) {
        StringBuilder sb = new StringBuilder("team verifier report\n");
        for (Map<String, Object> report : reports) {
            Map<?, ?> result = report.get("verificationResult") instanceof Map<?, ?> map ? map : Map.of();
            sb.append("- taskId: ").append(report.getOrDefault("taskId", ""))
                    .append("\n  status: ").append(mapValue(result, "status"))
                    .append("\n  riskLevel: ").append(mapValue(result, "riskLevel"))
                    .append("\n  reasons: ").append(renderRawList(result.get("reasons")))
                    .append("\n  missingTests: ").append(renderRawList(result.get("missingTests")))
                    .append("\n  requiredActions: ").append(renderRawList(result.get("requiredActions")))
                    .append("\n  createdAt: ").append(report.getOrDefault("createdAt", ""))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String renderListInline(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join("; ", values);
    }

    private String mapValue(Map<?, ?> map, String key) {
        Object value = map != null ? map.get(key) : null;
        return value != null ? String.valueOf(value) : "";
    }

    private String stringArg(Map<String, Object> map, String key) {
        Object value = map != null ? map.get(key) : null;
        return value != null ? String.valueOf(value).trim() : "";
    }

    private List<String> changedFilesFromApprovedArgs(Map<String, Object> args) {
        String path = stringArg(args, "path");
        return path.isBlank() ? List.of() : List.of(path);
    }

    private String renderRawList(Object raw) {
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return String.join("; ", list.stream().map(String::valueOf).toList());
        }
        return "none";
    }

    private boolean suggestedTestsCovered(List<String> suggestedTests, List<String> executedTests) {
        for (String suggested : suggestedTests != null ? suggestedTests : List.<String>of()) {
            String normalized = normalizeCommand(suggested);
            boolean covered = false;
            for (String executed : executedTests != null ? executedTests : List.<String>of()) {
                String normalizedExecuted = normalizeCommand(executed);
                if (normalizedExecuted.contains(normalized) || normalized.contains(normalizedExecuted)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAny(List<String> values, String... needles) {
        for (String value : values != null ? values : List.<String>of()) {
            String lower = value != null ? value.toLowerCase(java.util.Locale.ROOT) : "";
            for (String needle : needles) {
                if (lower.contains(needle.toLowerCase(java.util.Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalizeCommand(String raw) {
        return raw != null
                ? raw.toLowerCase(java.util.Locale.ROOT).replace("'", "").replace("\"", "").replaceAll("\\s+", " ").trim()
                : "";
    }

    private String activeTeamSessionId(Session session) {
        if (session == null || session.getMetadata() == null) {
            return "";
        }
        Object raw = session.getMetadata().get(SessionRuntimeKeys.TEAM_SESSION_ID_KEY);
        return raw != null ? String.valueOf(raw).trim() : "";
    }

    private String requireActiveTeamSessionId(Session session) {
        String sessionId = resolveActiveTeamSessionId(session);
        if (sessionId.isBlank()) {
            throw new IllegalStateException("no active team session. Run /team start <goal> first");
        }
        return sessionId;
    }

    private String resolveActiveTeamSessionId(Session session) {
        String sessionId = activeTeamSessionId(session);
        if (!sessionId.isBlank() && teamEngine.findSession(sessionId) != null && !teamEngine.isArchived(sessionId)) {
            storeTeamContext(session, sessionId);
            return sessionId;
        }
        TeamSession latest = teamEngine.loadLatestActiveSession();
        if (latest == null) {
            return "";
        }
        storeTeamContext(session, latest.id());
        return latest.id();
    }

    private void storeTeamContext(Session session, String teamSessionId) {
        if (session == null || teamSessionId == null || teamSessionId.isBlank()) {
            return;
        }
        session.getMetadata().put(SessionRuntimeKeys.TEAM_SESSION_ID_KEY, teamSessionId);
        session.getMetadata().put(SessionRuntimeKeys.TEAM_CONTEXT_KEY, teamEngine.contextSnapshot(teamSessionId));
        sessionManager.save(session);
    }

    private ExperienceOutcome parseOutcome(String raw) {
        String value = raw != null ? raw.trim().toUpperCase(java.util.Locale.ROOT) : "";
        try {
            return ExperienceOutcome.valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("feedback outcome must be success, failure, or neutral");
        }
    }

    private CompletableFuture<OutboundMessage> approve(CommandRouter.CommandContext ctx) {
        String requestId = trim(ctx.getArgs()).split("\\s+")[0];
        ApprovalRequest request = approvalService.approve(requestId);
        if (request == null) {
            return completedReply(ctx, "未找到审批请求：" + requestId);
        }
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        traceEvent(session, TraceEventType.APPROVAL_APPROVED, "approval", "approval approved", Map.of(
                "status", request.status().name(),
                "hasChangeAction", request.pendingChangeAction() != null,
                "hasToolCall", request.pendingToolCall() != null
        ), "", request.pendingChangeAction() != null ? request.pendingChangeAction().changeSetId() : "", request.requestId());
        if (request.pendingChangeAction() != null) {
            return approveChangeAction(ctx, requestId, request);
        }
        if (toolRegistry == null) {
            return completedReply(ctx, "已批准审批请求：" + request.requestId()
                    + "\nstatus: " + request.status()
                    + "\n该请求没有接入 ToolRegistry，无法自动恢复执行。");
        }
        PendingToolCall pendingToolCall;
        try {
            pendingToolCall = approvalService.consumeApprovedToolCall(requestId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return completedReply(ctx, "已批准审批请求：" + request.requestId()
                    + "\nstatus: " + request.status()
                    + "\n无法恢复执行：" + e.getMessage());
        }
        Object result = toolRegistry.executeApproved(pendingToolCall.toolName(), pendingToolCall.arguments());
        String developerHint = recordApprovedDeveloperToolCall(session, pendingToolCall, result, request.requestId());
        return completedReply(ctx, "已批准并恢复执行：" + request.requestId()
                + "\ntool: " + pendingToolCall.toolName()
                + "\n\n" + String.valueOf(result)
                + developerHint);
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

    private CompletableFuture<OutboundMessage> approveChangeAction(
            CommandRouter.CommandContext ctx,
            String requestId,
            ApprovalRequest request
    ) {
        PendingChangeAction action;
        try {
            action = approvalService.consumeApprovedChangeAction(requestId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return completedReply(ctx, "已批准审批请求：" + request.requestId()
                    + "\nstatus: " + request.status()
                    + "\n无法恢复执行：" + e.getMessage());
        }
        ChangeSetService service = new ChangeSetService(workspace);
        ChangeSetRenderer renderer = new ChangeSetRenderer();
        try {
            GitChangeSet result = switch (action.actionType()) {
                case COMMIT -> service.commit(action.changeSetId(), action.commitMessage());
                case ROLLBACK -> service.rollback(action.changeSetId());
            };
            Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
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
        } catch (IllegalArgumentException | IllegalStateException e) {
            return completedReply(ctx, "已批准审批请求：" + request.requestId()
                    + "\nstatus: " + request.status()
                    + "\n执行变更动作失败：" + e.getMessage());
        }
    }

    private CompletableFuture<OutboundMessage> reject(CommandRouter.CommandContext ctx) {
        String requestId = trim(ctx.getArgs()).split("\\s+")[0];
        ApprovalRequest request = approvalService.reject(requestId);
        if (request == null) {
            return completedReply(ctx, "未找到审批请求：" + requestId);
        }
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        traceEvent(session, TraceEventType.APPROVAL_REJECTED, "approval", "approval rejected", Map.of(
                "status", request.status().name()
        ), "", "", request.requestId());
        return completedReply(ctx, "已拒绝审批请求：" + request.requestId() + "\nstatus: " + request.status());
    }

    private CompletableFuture<OutboundMessage> dream(CommandRouter.CommandContext ctx) {
        if (!dreamEnabled()) {
            return completedReply(ctx, "Dream 未启用。");
        }
        return completedReply(ctx, dream.run()
                ? "Dream 已完成一次整合，记忆文件已更新。"
                : "Dream 本次没有检测到可更新内容。");
    }

    private CompletableFuture<OutboundMessage> dreamLog(CommandRouter.CommandContext ctx) {
        if (!dreamEnabled()) {
            return completedReply(ctx, "Dream 未启用。");
        }

        int maxEntries = 10;
        String args = trim(ctx.getArgs());
        if (!args.isBlank()) {
            maxEntries = Math.max(1, Math.min(50, parseInt(args, 10)));
        }

        var git = memoryStore.getGit();
        if (!git.isInitialized()) {
            return completedReply(ctx, "Dream 日志仓库尚未初始化。先执行一次 /dream 后再查看日志。");
        }

        var logs = git.log(maxEntries);
        if (logs.isEmpty()) {
            return completedReply(ctx, "暂无 Dream 历史记录。");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("dream log (latest ").append(logs.size()).append(")\n");
        sb.append("cursor: ").append(memoryStore.getLastDreamCursor())
                .append("/").append(memoryStore.getLastCursor()).append("\n\n");
        for (var c : logs) {
            sb.append(c.sha()).append("  ").append(c.timestamp()).append("  ").append(c.message()).append("\n");
        }
        return completedReply(ctx, sb.toString().trim());
    }

    private CompletableFuture<OutboundMessage> dreamRestore(CommandRouter.CommandContext ctx) {
        if (!dreamEnabled()) {
            return completedReply(ctx, "Dream 未启用。");
        }

        String args = trim(ctx.getArgs());
        if (args.isBlank()) {
            return completedReply(ctx, "用法：/dream-restore <commit_sha>");
        }

        String sha = args.split("\\s+")[0];
        var git = memoryStore.getGit();
        if (!git.isInitialized()) {
            return completedReply(ctx, "Dream 日志仓库尚未初始化，无法 restore。请先执行 /dream。");
        }

        var found = git.findCommit(sha, 200);
        if (found == null) {
            return completedReply(ctx, "未找到对应提交：" + sha);
        }

        String reverted = git.revert(found.sha());
        if (reverted == null) {
            return completedReply(ctx, "restore 失败，请检查工作区状态后重试。");
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return completedReply(ctx, "Dream 已恢复到 " + found.sha() + "，新提交: " + reverted + " (" + now + ")");
    }

    private boolean dreamEnabled() {
        return dreamConfig != null && dreamConfig.isEnabled();
    }

    private String afterCommand(String args) {
        String value = trim(args);
        int firstSpace = value.indexOf(' ');
        return firstSpace >= 0 ? value.substring(firstSpace + 1).trim() : "";
    }

    private List<String> pathLike(String text) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String cleaned = text.replace("{", " ").replace("}", " ").replace(",", " ");
        for (String token : cleaned.split("\\s+")) {
            String value = token.replace("\"", "").replace("'", "").trim();
            if (value.contains("/") || value.endsWith(".java") || value.endsWith(".md") || value.endsWith(".json")
                    || value.endsWith(".yml") || value.endsWith(".yaml") || value.endsWith(".txt")) {
                if (!out.contains(value)) {
                    out.add(value);
                }
            }
        }
        return out;
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

    private List<String> verifiedExperienceSources(Session session) {
        return contextSourcePaths(session).stream()
                .filter(path -> path.contains("experience/verified.jsonl"))
                .toList();
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

    private static String commandArg(String args, int index) {
        String[] parts = trim(args).split("\\s+");
        if (parts.length <= index || parts[index].isBlank()) {
            throw new IllegalArgumentException("missing id");
        }
        return parts[index];
    }

    private static List<String> teamAuditFlags(String args) {
        String[] parts = trim(args).split("\\s+");
        List<String> flags = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String flag = parts[i].trim().toLowerCase(java.util.Locale.ROOT);
            if (flag.isBlank()) {
                continue;
            }
            if (!flag.equals("--compact") && !flag.equals("--json")) {
                throw new IllegalArgumentException("unsupported audit flag: " + parts[i]);
            }
            if (!flags.contains(flag)) {
                flags.add(flag);
            }
        }
        return flags;
    }

    private static List<String> teamReportFlags(String args) {
        String[] parts = trim(args).split("\\s+");
        List<String> flags = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String flag = parts[i].trim().toLowerCase(java.util.Locale.ROOT);
            if (flag.isBlank()) {
                continue;
            }
            if (!flag.equals("--json")) {
                throw new IllegalArgumentException("unsupported report flag: " + parts[i]);
            }
            if (!flags.contains(flag)) {
                flags.add(flag);
            }
        }
        return flags;
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
