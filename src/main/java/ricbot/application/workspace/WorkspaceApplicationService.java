package ricbot.application.workspace;

import ricbot.domain.agent.SessionRuntimeKeys;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceRenderer;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.workspace.GitWorktreeWorkspaceBackend;
import ricbot.domain.workspace.LocalWorkspaceBackend;
import ricbot.domain.workspace.interfacep.WorkspaceBackend;
import ricbot.domain.workspace.enump.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceLifecycleService;
import ricbot.domain.workspace.WorkspaceRenderer;
import ricbot.domain.workspace.dto.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Application boundary for workspace commands and active-workspace session context. */
public final class WorkspaceApplicationService {
    private final Path workspace;
    private final SessionManager sessions;
    private final TraceStore traces;

    public WorkspaceApplicationService(Path workspace, SessionManager sessions, TraceStore traces) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.sessions = sessions;
        this.traces = traces;
    }

    public String execute(Session session, String rawArgs) {
        String args = clean(rawArgs);
        String action = args.isBlank() ? "status" : args.split("\\s+")[0].toLowerCase(Locale.ROOT);
        WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
        WorkspaceRenderer renderer = new WorkspaceRenderer();
        try {
            return switch (action) {
                case "create" -> create(session, store, renderer, args);
                case "status" -> status(session, store, renderer, argOrBlank(args, 1));
                case "list" -> renderer.renderList(new WorkspaceLifecycleService(workspace).activeWorktrees());
                case "use" -> use(session, store, renderer, arg(args, 1));
                case "diff" -> diff(session, store, renderer, argOrBlank(args, 1));
                case "discard" -> discard(session, renderer, afterCommand(args));
                case "cleanup" -> cleanup(session, store, renderer, arg(args, 1));
                default -> "用法：/workspace create --mode local|worktree <goal>|status [taskId|workspaceId]|list|use <id>|diff <taskId|workspaceId>|discard <taskId|workspaceId> --force|cleanup <id>";
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "workspace error: " + e.getMessage();
        }
    }

    public void activate(Session session, WorkspaceSession workspaceSession) {
        if (session == null || workspaceSession == null) return;
        WorkspaceRenderer renderer = new WorkspaceRenderer();
        session.getMetadata().put(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY, workspaceSession.id());
        session.getMetadata().put(SessionRuntimeKeys.WORKSPACE_SUMMARY_KEY,
                renderer.renderStatus(workspaceSession).replace("\n", " | "));
        session.getMetadata().put(SessionRuntimeKeys.WORKSPACE_SOURCE_KEY,
                ".workspaces/" + workspaceSession.id() + "/session.json");
        sessions.save(session);
    }

    private String create(Session session, WorkspaceSessionStore store, WorkspaceRenderer renderer, String args) {
        String mode = optionValue(args, "--mode", "local").toLowerCase(Locale.ROOT);
        String goal = createGoal(args);
        WorkspaceBackend backend = switch (mode) {
            case "local" -> new LocalWorkspaceBackend(store);
            case "worktree", "git_worktree" -> new GitWorktreeWorkspaceBackend(workspace, store);
            default -> throw new IllegalArgumentException("unsupported workspace mode: " + mode);
        };
        WorkspaceSession created = backend.createSession(workspace, goal);
        activate(session, created);
        trace(session, TraceEventType.WORKSPACE_CREATED, "workspace session created", Map.of(
                "workspaceSessionId", created.id(), "type", created.type().name(),
                "workspacePath", created.workspacePath(), "goal", created.goal()));
        return "workspace created\nid: " + created.id() + "\nsource: .workspaces/" + created.id()
                + "/session.json\n\n" + renderer.renderStatus(created);
    }

    private String use(Session session, WorkspaceSessionStore store, WorkspaceRenderer renderer, String sessionId) {
        WorkspaceSession selected = require(store, sessionId);
        activate(session, selected);
        trace(session, TraceEventType.WORKSPACE_SELECTED, "workspace session selected", Map.of(
                "workspaceSessionId", selected.id(), "type", selected.type().name(),
                "workspacePath", selected.workspacePath()));
        return "workspace selected\n" + renderer.renderStatus(selected);
    }

    private String status(Session session, WorkspaceSessionStore store, WorkspaceRenderer renderer, String target) {
        if (clean(target).isBlank()) return renderer.renderStatus(active(session, store));
        WorkspaceSession direct = store.load(target);
        if (direct != null && direct.type() != WorkspaceBackendType.GIT_WORKTREE) return renderer.renderStatus(direct);
        return renderer.renderLifecycleStatus(new WorkspaceLifecycleService(workspace).status(target));
    }

    private String diff(Session session, WorkspaceSessionStore store, WorkspaceRenderer renderer, String sessionId) {
        rejectTeamSessionId(sessionId);
        WorkspaceSession direct = !clean(sessionId).isBlank() ? store.load(sessionId) : active(session, store);
        if (direct != null && direct.type() != WorkspaceBackendType.GIT_WORKTREE) {
            String patch = backend(direct, store).diff(direct.id());
            trace(session, TraceEventType.WORKSPACE_DIFFED, "workspace diff rendered", Map.of(
                    "workspaceSessionId", direct.id(), "type", direct.type().name(),
                    "diffChars", patch != null ? patch.length() : 0));
            return renderer.renderDiff(direct, patch, 4_000);
        }
        String token = clean(sessionId).isBlank() && direct != null ? direct.id() : clean(sessionId);
        WorkspaceLifecycleService.WorkspaceDiff lifecycleDiff = new WorkspaceLifecycleService(workspace).diff(token);
        WorkspaceSession target = lifecycleDiff.session();
        trace(session, TraceEventType.WORKSPACE_DIFFED, "workspace diff rendered", Map.of(
                "workspaceSessionId", target.id(), "type", target.type().name(),
                "diffChars", lifecycleDiff.patch().length(), "changedFiles", lifecycleDiff.changedFiles()));
        return renderer.renderLifecycleDiff(lifecycleDiff, 4_000);
    }

    private String discard(Session session, WorkspaceRenderer renderer, String rawArgs) {
        String args = clean(rawArgs);
        boolean force = containsFlag(args, "--force");
        WorkspaceSession discarded = new WorkspaceLifecycleService(workspace).discard(stripFlags(args, "--force"), force);
        clearIfActive(session, discarded.id());
        trace(session, TraceEventType.WORKSPACE_CLEANED, "workspace session discarded", Map.of(
                "workspaceSessionId", discarded.id(), "type", discarded.type().name(),
                "workspacePath", discarded.workspacePath(), "status", discarded.status().name()));
        return "workspace discarded\n" + renderer.renderStatus(discarded);
    }

    private String cleanup(Session session, WorkspaceSessionStore store, WorkspaceRenderer renderer, String sessionId) {
        WorkspaceSession target = require(store, sessionId);
        WorkspaceSession cleaned = backend(target, store).cleanup(target.id());
        clearIfActive(session, cleaned.id());
        trace(session, TraceEventType.WORKSPACE_CLEANED, "workspace session cleaned", Map.of(
                "workspaceSessionId", cleaned.id(), "type", cleaned.type().name(),
                "workspacePath", cleaned.workspacePath(), "status", cleaned.status().name()));
        return "workspace cleaned\n" + renderer.renderStatus(cleaned);
    }

    private void clearIfActive(Session session, String workspaceId) {
        if (session == null || !activeId(session).equals(workspaceId)) return;
        session.getMetadata().remove(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY);
        session.getMetadata().remove(SessionRuntimeKeys.WORKSPACE_SUMMARY_KEY);
        session.getMetadata().remove(SessionRuntimeKeys.WORKSPACE_SOURCE_KEY);
        sessions.save(session);
    }

    private WorkspaceBackend backend(WorkspaceSession session, WorkspaceSessionStore store) {
        return session.type() == WorkspaceBackendType.GIT_WORKTREE
                ? new GitWorktreeWorkspaceBackend(workspace, store)
                : new LocalWorkspaceBackend(store);
    }

    private WorkspaceSession active(Session session, WorkspaceSessionStore store) {
        WorkspaceSession active = !activeId(session).isBlank() ? store.load(activeId(session)) : null;
        return active != null ? active : store.loadActive().stream().findFirst().orElse(null);
    }

    private static WorkspaceSession require(WorkspaceSessionStore store, String sessionId) {
        WorkspaceSession value = store.load(sessionId);
        if (value == null) throw new IllegalArgumentException("workspace session not found: " + sessionId);
        return value;
    }

    private void trace(Session session, TraceEventType type, String message, Map<String, Object> payload) {
        if (traces == null) return;
        try {
            String sessionId = session != null ? session.getKey() : "";
            TraceEvent event = traces.append(new TraceEvent(
                    traces.traceIdForSession(sessionId), null, "", sessionId, "", "", "",
                    type, "workspace", message, payload, null, null));
            if (session != null && event != null) {
                session.getMetadata().put(SessionRuntimeKeys.TRACE_ID_KEY, event.traceId());
                session.getMetadata().put(SessionRuntimeKeys.TRACE_SUMMARY_KEY,
                        new TraceRenderer().renderSummary(traces.summarize(event.traceId()), traces.loadEvents(event.traceId())));
                sessions.save(session);
            }
        } catch (Exception ignored) {
        }
    }

    private static void rejectTeamSessionId(String value) {
        String id = clean(value);
        if (id.startsWith("team_") && !id.startsWith("teamtask_")) {
            throw new IllegalArgumentException("你传入的是 teamSessionId：" + id + "。\n"
                    + "/workspace diff 需要 taskId 或 workspaceId，例如 teamtask_xxx。\n"
                    + "请使用最近输出中的 taskId；也可以用 /trace show " + id + " 查看相关事件。");
        }
    }

    private String createGoal(String args) {
        String value = afterCommand(args);
        String mode = optionValue(args, "--mode", "");
        if (!mode.isBlank()) value = value.replaceFirst("--mode\\s+" + java.util.regex.Pattern.quote(mode), "").trim();
        return value.isBlank() ? "workspace session" : value;
    }

    private static String activeId(Session session) {
        if (session == null || session.getMetadata() == null) return "";
        return String.valueOf(session.getMetadata().getOrDefault(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY, "")).trim();
    }

    private static String optionValue(String args, String option, String fallback) {
        String[] parts = clean(args).split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) if (option.equals(parts[i])) return parts[i + 1];
        return fallback;
    }

    private static String arg(String args, int index) {
        String value = argOrBlank(args, index);
        if (value.isBlank()) throw new IllegalArgumentException("missing id");
        return value;
    }

    private static String argOrBlank(String args, int index) {
        String[] parts = clean(args).split("\\s+");
        return parts.length > index ? parts[index] : "";
    }

    private static String afterCommand(String args) {
        String value = clean(args);
        int firstSpace = value.indexOf(' ');
        return firstSpace >= 0 ? value.substring(firstSpace + 1).trim() : "";
    }

    private static boolean containsFlag(String args, String flag) {
        for (String part : clean(args).split("\\s+")) if (flag.equalsIgnoreCase(part)) return true;
        return false;
    }

    private static String stripFlags(String args, String... flags) {
        Set<String> excluded = new HashSet<>();
        for (String flag : flags) excluded.add(flag.toLowerCase(Locale.ROOT));
        List<String> kept = new ArrayList<>();
        for (String part : clean(args).split("\\s+")) {
            if (!part.isBlank() && !excluded.contains(part.toLowerCase(Locale.ROOT))) kept.add(part);
        }
        return String.join(" ", kept).trim();
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
