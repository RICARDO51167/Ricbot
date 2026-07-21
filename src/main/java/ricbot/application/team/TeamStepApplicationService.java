package ricbot.application.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSet;
import ricbot.domain.session.Session;
import ricbot.domain.team.ImplementationStepGate;
import ricbot.domain.team.ImplementationStepStatus;
import ricbot.domain.team.PendingImplementationStep;
import ricbot.domain.team.StepGateResult;
import ricbot.domain.team.StepUpdateRequest;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamTask;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;
import ricbot.domain.workspace.GitWorktreeWorkspaceBackend;
import ricbot.domain.workspace.LocalWorkspaceBackend;
import ricbot.domain.workspace.WorkspaceBackend;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceLifecycleService;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.domain.agent.SessionRuntimeKeys;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Application boundary for planning and editing implementation steps before execution. */
public final class TeamStepApplicationService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final Path workspace;
    private final TeamEngine teams;
    private final TeamSessionApplicationService teamSessions;
    private final TraceStore traces;

    public TeamStepApplicationService(Path workspace, TeamEngine teams,
                                      TeamSessionApplicationService teamSessions, TraceStore traces) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.teams = teams;
        this.teamSessions = teamSessions;
        this.traces = traces;
    }

    public String execute(Session session, String action, String rawArgs) {
        return switch (clean(action)) {
            case "plan-steps" -> plan(session, requiredArg(rawArgs));
            case "steps" -> list(session, requiredArg(rawArgs));
            case "show-step" -> show(session, requiredArg(rawArgs));
            case "next-step" -> next(session, requiredArg(rawArgs));
            case "update-step" -> update(session, rawArgs);
            case "reject-step" -> reject(session, requiredArg(rawArgs));
            default -> throw new IllegalArgumentException("unsupported team step action: " + action);
        };
    }

    private String plan(Session session, String taskId) {
        TeamTask task = requireTask(taskId);
        List<PendingImplementationStep> steps = teams.createImplementationSteps(taskId);
        teamSessions.storeContext(session, task.sessionId());
        for (PendingImplementationStep step : steps) trace(session, TraceEventType.IMPLEMENTATION_STEP_CREATED, step);
        return "implementation steps planned\n" + renderSteps(steps);
    }

    private String list(Session session, String taskId) {
        TeamTask task = requireTask(taskId);
        List<PendingImplementationStep> steps = teams.listImplementationSteps(taskId);
        teamSessions.storeContext(session, task.sessionId());
        return steps.isEmpty() ? "No implementation steps for task: " + taskId : "implementation steps\n" + renderSteps(steps);
    }

    private String show(Session session, String stepId) {
        PendingImplementationStep step = requireStep(stepId);
        StepGateResult gate = teams.checkImplementationStepGate(step.id(), gateContext(session, step));
        return renderDetail(step) + "\n\n" + renderGate(gate);
    }

    private String next(Session session, String taskId) {
        TeamTask task = requireTask(taskId);
        PendingImplementationStep next = new ImplementationStepGate().nextStep(
                teams.listImplementationSteps(taskId), gateContext(session, null));
        teamSessions.storeContext(session, task.sessionId());
        if (next == null) return "No pending implementation step for task: " + taskId;
        StepGateResult gate = teams.checkImplementationStepGate(next.id(), gateContext(session, next));
        return "next implementation step\n" + renderDetail(next) + "\n\n" + renderGate(gate);
    }

    private String update(Session session, String rawArgs) {
        String[] parts = clean(rawArgs).split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) throw new IllegalArgumentException("missing jsonUpdate");
        PendingImplementationStep before = requireStep(parts[0]);
        StepUpdateRequest request;
        try {
            request = StepUpdateRequest.fromMap(MAPPER.readValue(parts[1], MAP_TYPE));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid json arguments: " + e.getMessage());
        }
        PendingImplementationStep updated = teams.updateImplementationStep(
                before.id(), request, gateContext(session, before), "user");
        teamSessions.storeContext(session, updated.teamSessionId());
        trace(session, TraceEventType.IMPLEMENTATION_STEP_UPDATED, updated);
        if (!updated.validationErrors().isEmpty()) trace(session, TraceEventType.IMPLEMENTATION_STEP_VALIDATION_FAILED, updated);
        else if (updated.status() == ImplementationStepStatus.READY) trace(session, TraceEventType.IMPLEMENTATION_STEP_READY, updated);
        return "implementation step updated\nupdatedFields: " + inline(request.updatedFields()) + "\n" + renderDetail(updated);
    }

    private String reject(Session session, String stepId) {
        PendingImplementationStep rejected = teams.rejectImplementationStep(stepId);
        teamSessions.storeContext(session, rejected.teamSessionId());
        trace(session, TraceEventType.IMPLEMENTATION_STEP_REJECTED, rejected);
        return "implementation step rejected\n" + renderDetail(rejected);
    }

    private ImplementationStepGate.GateContext gateContext(Session session, PendingImplementationStep step) {
        return new ImplementationStepGate.GateContext(activeWorkspaceHasDiff(session)
                || new ChangeSetService(workspace).hasWorkingTreeChanges(), changeSetExistsFor(step));
    }

    private boolean activeWorkspaceHasDiff(Session session) {
        String id = activeWorkspaceId(session);
        if (id.isBlank()) return false;
        try {
            WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
            WorkspaceSession active = store.load(id);
            if (active == null) return false;
            if (active.type() == WorkspaceBackendType.GIT_WORKTREE) {
                return !new WorkspaceLifecycleService(workspace).diff(id).changedFiles().isEmpty();
            }
            String diff = backend(active, store).diff(id);
            return diff != null && !diff.isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean changeSetExistsFor(PendingImplementationStep step) {
        try {
            GitChangeSet latest = new ChangeSetService(workspace).latest();
            return latest != null && (step == null || step.teamSessionId().isBlank()
                    || latest.teamSessionId().isBlank() || step.teamSessionId().equals(latest.teamSessionId()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private WorkspaceBackend backend(WorkspaceSession session, WorkspaceSessionStore store) {
        return session.type() == WorkspaceBackendType.GIT_WORKTREE
                ? new GitWorktreeWorkspaceBackend(workspace, store) : new LocalWorkspaceBackend(store);
    }

    private void trace(Session session, TraceEventType type, PendingImplementationStep step) {
        if (traces == null || step == null) return;
        try {
            String sessionId = session != null ? session.getKey() : "";
            traces.append(new TraceEvent(traces.traceIdForSession(sessionId), null, "", sessionId,
                    step.teamSessionId(), "", "", type, "team", "implementation step",
                    Map.of("stepId", step.id(), "taskId", step.taskId(), "teamSessionId", step.teamSessionId(),
                            "type", step.type().name(), "status", step.status().name(), "riskLevel", step.riskLevel().name(),
                            "requiresApproval", step.requiresApproval(), "validationErrors", step.validationErrors()), null, null));
        } catch (Exception ignored) {
        }
    }

    private TeamTask requireTask(String id) {
        TeamTask task = teams.findTask(id);
        if (task == null) throw new IllegalArgumentException("team task not found: " + id);
        return task;
    }

    private PendingImplementationStep requireStep(String id) {
        PendingImplementationStep step = teams.findImplementationStep(id);
        if (step == null) throw new IllegalArgumentException("implementation step not found: " + id);
        return step;
    }

    private static String renderSteps(List<PendingImplementationStep> steps) {
        List<PendingImplementationStep> safe = steps != null ? steps : List.of();
        StringBuilder out = new StringBuilder("counts: DRAFT=").append(count(safe, ImplementationStepStatus.DRAFT))
                .append(" READY=").append(count(safe, ImplementationStepStatus.READY))
                .append(" BLOCKED=").append(count(safe, ImplementationStepStatus.BLOCKED))
                .append(" APPLIED=").append(count(safe, ImplementationStepStatus.APPLIED)).append("\n");
        for (PendingImplementationStep step : safe) {
            out.append("- ").append(step.id()).append(" [").append(step.type()).append("] ").append(step.status())
                    .append(" order=").append(step.orderIndex())
                    .append(step.targetPath().isBlank() ? "" : " target=" + step.targetPath())
                    .append(step.command().isBlank() ? "" : " command=" + step.command())
                    .append(step.dependsOnStepIds().isEmpty() ? "" : " dependsOn=" + String.join(",", step.dependsOnStepIds()))
                    .append(step.blockedReason().isBlank() ? "" : " blockedReason=" + step.blockedReason())
                    .append(step.requiredBeforeApply().isEmpty() ? "" : " requiredBeforeApply=" + String.join("; ", step.requiredBeforeApply()))
                    .append(" reason=").append(step.reason()).append("\n");
        }
        return out.toString().trim();
    }

    public static String renderDetail(PendingImplementationStep step) {
        return "implementation step\nid: " + step.id() + "\ntaskId: " + step.taskId()
                + "\nteamSessionId: " + step.teamSessionId() + "\nrole: " + step.role() + "\ntype: " + step.type()
                + "\nstatus: " + step.status() + "\ntargetPath: " + none(step.targetPath())
                + "\ncommand: " + none(step.command()) + "\noldText: " + none(abbreviate(step.oldText(), 120))
                + "\nnewText: " + none(abbreviate(step.newText(), 120)) + "\nriskLevel: " + step.riskLevel()
                + "\nrequiresApproval: " + step.requiresApproval() + "\norderIndex: " + step.orderIndex()
                + "\ndependsOn: " + inline(step.dependsOnStepIds()) + "\nunblocks: " + inline(step.unblocksStepIds())
                + "\nblockedBy: " + inline(step.blockedBy()) + "\nblockedReason: " + none(step.blockedReason())
                + "\nqualityGate: " + none(step.qualityGate()) + "\nrequiredBeforeApply: " + inline(step.requiredBeforeApply())
                + "\nvalidationErrors: " + inline(step.validationErrors()) + "\nlastUpdatedBy: " + none(step.lastUpdatedBy())
                + "\nupdateReason: " + none(step.updateReason()) + "\nreason: " + step.reason();
    }

    public static String renderGate(StepGateResult gate) {
        if (gate == null) return "gate: unknown";
        return "gate: " + (gate.allowed() ? "ALLOW" : "BLOCKED") + "\nreasons: " + inline(gate.reasons())
                + "\nrequiredActions: " + inline(gate.requiredActions())
                + "\nnextSuggestedCommand: " + none(gate.nextSuggestedCommand());
    }

    private static long count(List<PendingImplementationStep> steps, ImplementationStepStatus status) {
        return steps.stream().filter(step -> step.status() == status).count();
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
