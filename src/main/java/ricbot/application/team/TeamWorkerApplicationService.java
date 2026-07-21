package ricbot.application.team;

import ricbot.domain.agent.SessionRuntimeKeys;
import ricbot.domain.agent.TaskSummaryService;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSet;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.domain.team.StepAuditEventType;
import ricbot.domain.team.StepAuditRecord;
import ricbot.domain.team.TeamArtifact;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamRole;
import ricbot.domain.team.TeamTask;
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
import ricbot.domain.workspace.WorkspaceLifecycleService;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Application boundary for durable worker and verifier execution. */
public final class TeamWorkerApplicationService {
    private final Path workspace;
    private final SessionManager sessions;
    private final TeamEngine teams;
    private final TeamSessionApplicationService teamSessions;
    private final TraceStore traces;

    public TeamWorkerApplicationService(Path workspace, SessionManager sessions, TeamEngine teams,
                                        TeamSessionApplicationService teamSessions, TraceStore traces) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.sessions = sessions;
        this.teams = teams;
        this.teamSessions = teamSessions;
        this.traces = traces;
    }

    public String execute(Session session, String action, String rawArgs) {
        String taskId = requiredArg(rawArgs);
        return switch (clean(action)) {
            case "run-worker" -> runWorker(session, taskId);
            case "run-verifier" -> runVerifier(session, taskId);
            default -> throw new IllegalArgumentException("unsupported team worker action: " + action);
        };
    }

    private String runWorker(Session session, String taskId) {
        teamSessions.resolveActiveSessionId(session);
        TeamTask task = requireTask(taskId);
        WorkerExecutionResult result = teams.runWorker(taskId, input(task, session, false));
        if (result.role() == TeamRole.DEVELOPER) {
            session.getMetadata().put(SessionRuntimeKeys.DEVELOPER_TASK_ID_KEY, task.id());
            sessions.save(session);
        }
        teamSessions.storeContext(session, task.sessionId());
        trace(session, TraceEventType.WORKER_FINISHED, "team worker executed", payload(result), task.sessionId(), "");
        return "team worker executed\n" + render(result, teams.findTask(taskId));
    }

    private String runVerifier(Session session, String taskId) {
        teamSessions.resolveActiveSessionId(session);
        TeamTask task = requireTask(taskId);
        WorkerExecutionInput input = input(task, session, true);
        WorkerExecutionResult result = teams.runVerifier(taskId, input);
        TeamTask updated = teams.findTask(taskId);
        ChangeSetService changes = new ChangeSetService(workspace);
        GitChangeSet latest = changes.latest();
        String workspaceId = activeWorkspaceId(session);
        boolean hasDiff = activeWorkspaceHasDiff(session);
        boolean latestMatches = latest != null && (workspaceId.isBlank() || workspaceId.equals(latest.workspaceSessionId()));
        if (hasDiff && !latestMatches) {
            updated = teams.submitVerification(taskId, new VerificationResult(
                    VerificationResult.Status.REJECT,
                    "active workspace diff requires ChangeSet before verifier acceptance",
                    "Verifier rejected active workspace diff until a ChangeSet is created.",
                    result.suggestedTests(), CommandRiskLevel.MEDIUM,
                    List.of("active workspace has diff but no matching ChangeSet"), List.of(),
                    List.of("active workspace diff"),
                    List.of("Run /change create for active workspace changes before accepting verification."),
                    false, 0.72d, null));
            trace(session, TraceEventType.WORKSPACE_DIFF_REQUIRES_CHANGESET, "workspace diff requires changeset",
                    Map.of("taskId", taskId, "teamSessionId", task.sessionId(), "workspaceSessionId", workspaceId,
                            "workspacePath", input.workspacePath(), "requiredAction", "/change create"), task.sessionId(), "");
        }
        if (latest != null && updated != null && updated.verificationResult() != null
                && latestMatches && latest.verifierStatus().isBlank()) {
            changes.attachVerifierResult(latest.id(), updated.verificationResult());
        }
        if (updated != null && updated.verificationResult() != null) {
            teams.recordStepAudit(new StepAuditRecord(null, "", task.id(), task.sessionId(),
                    StepAuditEventType.STEP_VERIFIED, "", updated.state().name(),
                    "Verifier result linked to implementation task.", "", "", "",
                    latestMatches && latest != null ? latest.id() : "", updated.verificationResult().status().name(),
                    "", null, Map.of("reason", updated.verificationResult().reason())));
        }
        teamSessions.storeContext(session, task.sessionId());
        trace(session, TraceEventType.VERIFIER_FINISHED, "team verifier executed", payload(result), task.sessionId(),
                latestMatches && latest != null ? latest.id() : "");
        String changeHint = hasDiff ? "\nchangeSetHint: active workspace has diff; run /change create" : "";
        String source = workspaceId.isBlank() ? "\nworkspaceSource: base workspace" : "\nworkspaceSource: active workspace " + workspaceId;
        return "team verifier executed\n" + render(result, updated) + source + changeHint;
    }

    private WorkerExecutionInput input(TeamTask task, Session session, boolean verifier) {
        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);
        ArrayList<String> findings = new ArrayList<>(summary.diffReviews());
        findings.addAll(summary.changeSetSummaries());
        if (!summary.traceSummary().isBlank()) findings.add("trace=" + abbreviate(summary.traceSummary(), 260));
        ArrayList<String> risks = new ArrayList<>(summary.blockers());
        if (activeWorkspaceHasDiff(session)) risks.add("active workspace has diff; create ChangeSet before final acceptance");
        return new WorkerExecutionInput(task.id(), task.sessionId(), verifier ? TeamRole.VERIFIER : task.role(),
                task.goal(), activeWorkspacePath(session),
                teams.whiteboard(task.sessionId()).readSummary() + "\n" + renderSummary(summary),
                summary.changedFiles(), summary.testCommands(), !task.summary().isBlank() ? task.summary() : summary.goal(),
                findings, risks, summary.suggestedTests(), List.of(), 0d, "");
    }

    private String activeWorkspacePath(Session session) {
        String id = activeWorkspaceId(session);
        if (!id.isBlank()) {
            try {
                WorkspaceSession value = new WorkspaceSessionStore(workspace).load(id);
                if (value != null && !value.workspacePath().isBlank()) return value.workspacePath();
            } catch (Exception ignored) {
            }
        }
        return workspace.toString();
    }

    private boolean activeWorkspaceHasDiff(Session session) {
        String id = activeWorkspaceId(session);
        if (id.isBlank()) return false;
        try {
            WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
            WorkspaceSession value = store.load(id);
            if (value == null) return false;
            if (value.type() == WorkspaceBackendType.GIT_WORKTREE) {
                return !new WorkspaceLifecycleService(workspace).diff(id).changedFiles().isEmpty();
            }
            String diff = backend(value, store).diff(id);
            return diff != null && !diff.isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private WorkspaceBackend backend(WorkspaceSession value, WorkspaceSessionStore store) {
        return value.type() == WorkspaceBackendType.GIT_WORKTREE
                ? new GitWorktreeWorkspaceBackend(workspace, store) : new LocalWorkspaceBackend(store);
    }

    private void trace(Session session, TraceEventType type, String message, Map<String, Object> payload,
                       String teamSessionId, String changeSetId) {
        if (traces == null) return;
        try {
            String sessionId = session != null ? session.getKey() : "";
            TraceEvent event = traces.append(new TraceEvent(traces.traceIdForSession(sessionId), null, "", sessionId,
                    teamSessionId, changeSetId, "", type, "team", message, payload, null, null));
            if (session != null && event != null) {
                session.getMetadata().put(SessionRuntimeKeys.TRACE_ID_KEY, event.traceId());
                session.getMetadata().put(SessionRuntimeKeys.TRACE_SUMMARY_KEY,
                        new TraceRenderer().renderSummary(traces.summarize(event.traceId()), traces.loadEvents(event.traceId())));
                sessions.save(session);
            }
        } catch (Exception ignored) {
        }
    }

    private TeamTask requireTask(String id) {
        TeamTask task = teams.findTask(id);
        if (task == null) throw new IllegalArgumentException("team task not found: " + id);
        return task;
    }

    private static Map<String, Object> payload(WorkerExecutionResult result) {
        return Map.of("taskId", result.taskId(), "teamSessionId", result.teamSessionId(), "role", result.role().name(),
                "workspacePath", result.workspacePath(), "workspaceSessionId", workspaceIdFromPath(result.workspacePath()),
                "status", result.status(), "confidence", result.confidence());
    }

    private static String render(WorkerExecutionResult result, TeamTask task) {
        return "taskId: " + result.taskId() + "\nrole: " + result.role() + "\nworkspacePath: " + result.workspacePath()
                + "\nstatus: " + result.status() + "\nsummary: " + result.summary()
                + "\nfindings: " + inline(result.findings()) + "\nrisks: " + inline(result.risks())
                + "\nsuggestedTests: " + inline(result.suggestedTests()) + "\npolicy: " + inline(result.policySummary())
                + "\ndeveloperPlan: " + inline(result.developerPlan()) + "\nrequiredApprovals: " + inline(result.requiredApprovals())
                + "\nnextActions: " + inline(result.nextActions())
                + "\nchangeSetRecommendation: " + none(result.changeSetRecommendation())
                + "\nartifacts: " + (result.artifacts().isEmpty() ? "none" : String.join(", ", result.artifacts().stream().map(TeamArtifact::path).toList()))
                + "\nconfidence: " + String.format(java.util.Locale.ROOT, "%.2f", result.confidence())
                + "\nnextState: " + (task != null ? task.state() : "(unknown)");
    }

    private static String renderSummary(TaskSummaryService.TaskSummary summary) {
        return "goal=" + summary.goal() + " changedFiles=" + String.join(",", summary.changedFiles())
                + " blockers=" + String.join(",", summary.blockers())
                + " suggestedTests=" + String.join(",", summary.suggestedTests())
                + " executedTests=" + String.join(",", summary.testCommands());
    }

    private static String workspaceIdFromPath(String raw) {
        String normalized = clean(raw).replace('\\', '/');
        int index = normalized.indexOf("/.workspaces/");
        if (index < 0) return "";
        String tail = normalized.substring(index + "/.workspaces/".length());
        int slash = tail.indexOf('/');
        return slash >= 0 ? tail.substring(0, slash) : tail;
    }

    private static String activeWorkspaceId(Session session) {
        if (session == null || session.getMetadata() == null) return "";
        return String.valueOf(session.getMetadata().getOrDefault(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY, "")).trim();
    }

    private static String requiredArg(String raw) {
        String[] parts = clean(raw).split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) throw new IllegalArgumentException("missing argument");
        return parts[0];
    }

    private static String inline(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join("; ", values);
    }

    private static String none(String value) {
        return clean(value).isBlank() ? "none" : value;
    }

    private static String abbreviate(String value, int limit) {
        String text = clean(value);
        return text.length() <= limit ? text : text.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
