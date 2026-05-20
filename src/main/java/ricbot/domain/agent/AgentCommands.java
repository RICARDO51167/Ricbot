package ricbot.domain.agent;

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
import ricbot.domain.experience.ExperienceStore;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.message.OutboundMessages;
import ricbot.domain.note.NoteService;
import ricbot.domain.note.TaskNoteWriter;
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
import ricbot.domain.team.TeamRole;
import ricbot.domain.team.TeamSession;
import ricbot.domain.team.TeamTask;
import ricbot.domain.team.VerificationInput;
import ricbot.domain.team.VerificationResult;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class AgentCommands {

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
                case "demote" -> completedReply(ctx, "experience demoted\n"
                        + renderer.renderDetail(store.demote(commandArg(args, 1))));
                case "restore" -> completedReply(ctx, "experience restored\n"
                        + renderer.renderDetail(store.restore(commandArg(args, 1))));
                case "stats" -> completedReply(ctx, renderer.renderStats(store.stats()));
                case "review" -> completedReply(ctx, renderer.renderReview(store.review(20)));
                default -> completedReply(ctx, "用法：/experience extract|list|show <id>|verify <id>|reject <id>|feedback <id> success|failure|neutral|usage <id>|stale|archive <id>|promote <id>|demote <id>|restore <id>|stats|review");
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
                case "auto-verify" -> teamAutoVerify(ctx, afterCommand(args));
                case "verifier-report" -> teamVerifierReport(ctx, afterCommand(args));
                case "task" -> teamTask(ctx, afterCommand(args));
                case "verify" -> teamVerify(ctx, afterCommand(args));
                case "events" -> teamEvents(ctx);
                case "whiteboard" -> teamWhiteboard(ctx);
                case "abort" -> teamAbort(ctx, afterCommand(args));
                default -> completedReply(ctx, "用法：/team start <goal>|status|list|resume <sessionId>|archive <sessionId>|suggest <goal>|suggest-current|task <role> <goal>|auto-verify <taskId>|verifier-report <taskId>|verify <taskId> pass|reject|needs-human <reason>|events|whiteboard|abort <taskId>");
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
        return completedReply(ctx, "已批准并恢复执行：" + request.requestId()
                + "\ntool: " + pendingToolCall.toolName()
                + "\n\n" + String.valueOf(result));
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
}
