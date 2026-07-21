package ricbot.application.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.agent.SessionRuntimeKeys;
import ricbot.domain.change.ChangeSetRenderer;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSet;
import ricbot.domain.policy.PolicyAwareToolExecutor;
import ricbot.domain.policy.PolicyDecision;
import ricbot.domain.policy.PolicyEngine;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.domain.team.ImplementationStepGate;
import ricbot.domain.team.ImplementationStepStatus;
import ricbot.domain.team.ImplementationStepType;
import ricbot.domain.team.PendingImplementationStep;
import ricbot.domain.team.StepAuditEventType;
import ricbot.domain.team.StepAuditRecord;
import ricbot.domain.team.StepGateResult;
import ricbot.domain.team.TeamArtifact;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamRole;
import ricbot.domain.team.TeamTask;
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
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Application boundary for policy-gated Team tools and implementation-step side effects. */
public final class TeamToolApplicationService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final Path workspace;
    private final SessionManager sessions;
    private final TeamEngine teams;
    private final TeamSessionApplicationService teamSessions;
    private final TeamWorkerApplicationService teamWorkers;
    private final ToolRegistry tools;
    private final ApprovalService approvals;
    private final TraceStore traces;

    public TeamToolApplicationService(Path workspace, SessionManager sessions, TeamEngine teams,
                                      TeamSessionApplicationService teamSessions,
                                      TeamWorkerApplicationService teamWorkers, ToolRegistry tools,
                                      ApprovalService approvals, TraceStore traces) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.sessions = sessions;
        this.teams = teams;
        this.teamSessions = teamSessions;
        this.teamWorkers = teamWorkers;
        this.tools = tools;
        this.approvals = approvals;
        this.traces = traces;
    }

    public String execute(Session session, String action, String rawArgs) {
        return switch (clean(action)) {
            case "tool-call" -> toolCall(session, rawArgs);
            case "apply-step" -> applyStep(session, requiredArg(rawArgs));
            default -> throw new IllegalArgumentException("unsupported team tool action: " + action);
        };
    }

    private String toolCall(Session session, String rawArgs) {
        String[] parts = clean(rawArgs).split("\\s+", 3);
        if (parts.length < 3 || parts[2].isBlank()) throw new IllegalArgumentException("missing jsonArgs");
        teamSessions.resolveActiveSessionId(session);
        TeamTask task = requireTask(parts[0]);
        Map<String, Object> args = parseJson(parts[2]);
        WorkspaceSession active = activeWorkspace(session);
        PolicyAwareToolExecutor.PolicyToolResult result = teams.executeToolAsRole(task.id(), executor(), args,
                active, session.getKey(), parts[1]);
        if (task.role() == TeamRole.DEVELOPER) {
            session.getMetadata().put(SessionRuntimeKeys.DEVELOPER_TASK_ID_KEY, task.id());
            sessions.save(session);
            if (result.decision().requiresApproval()) {
                trace(session, TraceEventType.DEVELOPER_TOOL_APPROVAL_REQUIRED, "developer tool approval required",
                        Map.of("taskId", task.id(), "teamSessionId", task.sessionId(),
                                "toolName", result.decision().toolName(), "requestId", result.approvalRequestId(),
                                "workspaceSessionId", active != null ? active.id() : "",
                                "workspacePath", active != null ? active.workspacePath() : workspace.toString()),
                        task.sessionId(), "", result.approvalRequestId());
            }
        }
        WorkerExecutionResult report = roleToolCallReport(task, result, active);
        teams.recordRoleToolCall(task.id(), report);
        teamSessions.storeContext(session, task.sessionId());
        return renderToolResult(result, report);
    }

    private String applyStep(Session session, String stepId) {
        PendingImplementationStep step = requireStep(stepId);
        if (step.status() == ImplementationStepStatus.REJECTED || step.status() == ImplementationStepStatus.APPLIED) {
            return "implementation step not applicable\n" + TeamStepApplicationService.renderDetail(step);
        }
        if (step.status() == ImplementationStepStatus.DRAFT) {
            List<String> errors = new ImplementationStepGate().validateFields(step);
            return "implementation step is DRAFT; run /team update-step before apply-step\nvalidationErrors: "
                    + inline(errors.isEmpty() ? step.validationErrors() : errors) + "\n\n"
                    + TeamStepApplicationService.renderDetail(step);
        }
        if (step.status() == ImplementationStepStatus.BLOCKED) {
            return "implementation step is BLOCKED\n" + TeamStepApplicationService.renderDetail(step);
        }
        StepGateResult gate = teams.checkImplementationStepGate(step.id(), gateContext(session, step));
        traceGate(session, TraceEventType.IMPLEMENTATION_STEP_GATE_CHECKED, step, gate);
        if (gate.blocked()) {
            PendingImplementationStep blocked = teams.blockImplementationStep(step.id(), gate);
            teamSessions.storeContext(session, blocked.teamSessionId());
            traceStep(session, TraceEventType.IMPLEMENTATION_STEP_BLOCKED, blocked);
            traceGate(session, TraceEventType.IMPLEMENTATION_STEP_BLOCKED, blocked, gate);
            return "implementation step blocked\n" + TeamStepApplicationService.renderDetail(blocked)
                    + "\n\n" + TeamStepApplicationService.renderGate(gate);
        }
        return switch (step.type()) {
            case READ, EDIT, WRITE, EXEC_TEST -> applyToolStep(session, step);
            case CREATE_CHANGESET -> applyChangeSetStep(session, step);
            case RUN_VERIFIER -> {
                String result = teamWorkers.execute(session, "run-verifier", step.taskId());
                PendingImplementationStep applied = teams.applyImplementationStep(step.id());
                teamSessions.storeContext(session, applied.teamSessionId());
                yield result + "\n\nimplementation step applied\n" + TeamStepApplicationService.renderDetail(applied);
            }
        };
    }

    private String applyToolStep(Session session, PendingImplementationStep step) {
        if (step.status() == ImplementationStepStatus.DRAFT
                && (step.type() == ImplementationStepType.EDIT || step.type() == ImplementationStepType.WRITE)) {
            PendingImplementationStep failed = teams.failImplementationStep(step.id(),
                    Map.of("reason", "draft step is missing executable edit/write arguments"));
            return "implementation step failed\n" + TeamStepApplicationService.renderDetail(failed);
        }
        Map<String, Object> args = stepArgs(step);
        args.put("__implementation_step_id", step.id());
        PolicyAwareToolExecutor.PolicyToolResult result = executor().execute(step.role(), toolName(step), args,
                activeWorkspace(session), session.getKey(), step.teamSessionId(), step.taskId());
        PendingImplementationStep updated;
        if (result.decision().denied()) {
            updated = teams.failImplementationStep(step.id(), result.decision().toMap());
            traceStep(session, TraceEventType.IMPLEMENTATION_STEP_FAILED, updated);
        } else if (result.decision().requiresApproval()) {
            updated = teams.markImplementationStepApprovalRequired(step.id(), result.decision().toMap());
            teams.recordStepAudit(new StepAuditRecord(null, updated.id(), updated.taskId(), updated.teamSessionId(),
                    StepAuditEventType.STEP_APPROVAL_REQUIRED, step.status().name(), updated.status().name(),
                    "Implementation step approval required.", result.approvalRequestId(), toolName(step), "",
                    "", "", "", null, result.decision().toMap()));
            traceStep(session, TraceEventType.IMPLEMENTATION_STEP_APPROVAL_REQUIRED, updated);
        } else if (result.executed()) {
            updated = teams.applyImplementationStep(step.id());
            teams.recordStepAudit(new StepAuditRecord(null, updated.id(), updated.taskId(), updated.teamSessionId(),
                    StepAuditEventType.STEP_TOOL_APPLIED, step.status().name(), updated.status().name(),
                    "Implementation step tool applied.", "", toolName(step), result.resultSummary(),
                    "", "", "", null, Map.of()));
            traceStep(session, TraceEventType.IMPLEMENTATION_STEP_APPLIED, updated);
        } else {
            updated = teams.failImplementationStep(step.id(), result.decision().toMap());
            traceStep(session, TraceEventType.IMPLEMENTATION_STEP_FAILED, updated);
        }
        teamSessions.storeContext(session, updated.teamSessionId());
        return "implementation step apply result\npolicy decision: " + result.decision().decisionType()
                + "\nreasons: " + inline(result.decision().reasons())
                + (!result.approvalRequestId().isBlank() ? "\napproval requestId: " + result.approvalRequestId() : "")
                + "\ntool result: " + none(result.resultSummary()) + "\n\n" + TeamStepApplicationService.renderDetail(updated);
    }

    private String applyChangeSetStep(Session session, PendingImplementationStep step) {
        ChangeSetService service = new ChangeSetService(workspace);
        ChangeSetRenderer renderer = new ChangeSetRenderer();
        WorkspaceSession active = activeWorkspace(session);
        GitChangeSet changeSet = active != null
                ? service.createFromWorkspace(active.id(), Path.of(active.workspacePath()), session.getKey(), step.teamSessionId(), step.taskId())
                : service.createFromWorkingTree(session.getKey(), step.teamSessionId(), step.taskId());
        teams.recordArtifact(step.teamSessionId(), new TeamArtifact(null, step.taskId(),
                ".changesets/" + changeSet.id() + "/changeset.json", "ChangeSet " + changeSet.id(), "changeset", null));
        storeChangeSetContext(session, changeSet, renderer);
        PendingImplementationStep applied = teams.applyImplementationStep(step.id());
        teams.recordStepAudit(new StepAuditRecord(null, applied.id(), applied.taskId(), applied.teamSessionId(),
                StepAuditEventType.STEP_CHANGESET_LINKED, step.status().name(), applied.status().name(),
                "ChangeSet linked to implementation step.", "", "", "", changeSet.id(), "", "", null,
                Map.of("changedFiles", changeSet.changedFiles())));
        teamSessions.storeContext(session, step.teamSessionId());
        traceStep(session, TraceEventType.IMPLEMENTATION_STEP_APPLIED, applied);
        trace(session, active != null ? TraceEventType.CHANGESET_CREATED_FROM_WORKSPACE : TraceEventType.CHANGESET_CREATED,
                "changeset created from implementation step",
                Map.of("stepId", step.id(), "status", changeSet.status().name(), "changedFiles", changeSet.changedFiles(),
                        "workspaceSessionId", changeSet.workspaceSessionId(), "workspacePath", changeSet.workspacePath()),
                changeSet.teamSessionId(), changeSet.id(), "");
        return "implementation step applied\n" + TeamStepApplicationService.renderDetail(applied)
                + "\n\nchangeset created\nid: " + changeSet.id() + "\n" + renderer.renderStatus(changeSet);
    }

    private PolicyAwareToolExecutor executor() {
        return new PolicyAwareToolExecutor(new PolicyEngine(workspace), tools, approvals, traces);
    }

    private ImplementationStepGate.GateContext gateContext(Session session, PendingImplementationStep step) {
        return new ImplementationStepGate.GateContext(activeWorkspaceHasDiff(session)
                || new ChangeSetService(workspace).hasWorkingTreeChanges(), changeSetExistsFor(step));
    }

    private boolean changeSetExistsFor(PendingImplementationStep step) {
        try {
            GitChangeSet latest = new ChangeSetService(workspace).latest();
            return latest != null && (step.teamSessionId().isBlank() || latest.teamSessionId().isBlank()
                    || step.teamSessionId().equals(latest.teamSessionId()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean activeWorkspaceHasDiff(Session session) {
        WorkspaceSession active = activeWorkspace(session);
        if (active == null) return false;
        try {
            if (active.type() == WorkspaceBackendType.GIT_WORKTREE) {
                return !new WorkspaceLifecycleService(workspace).diff(active.id()).changedFiles().isEmpty();
            }
            WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
            String diff = backend(active, store).diff(active.id());
            return diff != null && !diff.isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private WorkspaceSession activeWorkspace(Session session) {
        if (session == null || session.getMetadata() == null) return null;
        String id = String.valueOf(session.getMetadata().getOrDefault(SessionRuntimeKeys.ACTIVE_WORKSPACE_SESSION_ID_KEY, "")).trim();
        if (id.isBlank()) return null;
        try {
            return new WorkspaceSessionStore(workspace).load(id);
        } catch (Exception ignored) {
            return null;
        }
    }

    private WorkspaceBackend backend(WorkspaceSession active, WorkspaceSessionStore store) {
        return active.type() == WorkspaceBackendType.GIT_WORKTREE
                ? new GitWorktreeWorkspaceBackend(workspace, store) : new LocalWorkspaceBackend(store);
    }

    private WorkerExecutionResult roleToolCallReport(TeamTask task,
            PolicyAwareToolExecutor.PolicyToolResult result, WorkspaceSession active) {
        PolicyDecision decision = result.decision();
        ArrayList<String> findings = new ArrayList<>(List.of("tool=" + decision.toolName(), "decision=" + decision.decisionType()));
        if (result.executed()) findings.add("tool result=" + abbreviate(result.resultSummary(), 240));
        ArrayList<String> risks = new ArrayList<>();
        if (decision.denied()) risks.add("policy denied tool execution");
        if (decision.requiresApproval()) risks.add("approval required before execution");
        if (task.role() == TeamRole.DEVELOPER && active == null) {
            risks.add("local workspace warning: create a worktree with /workspace create --mode worktree before editing shared code");
        }
        String recommendation = task.role() == TeamRole.DEVELOPER
                ? "After approved edit/write tool calls, run /change create, then /team run-verifier " + task.id() + ", then /summary." : "";
        List<String> actions = task.role() == TeamRole.DEVELOPER
                ? List.of("/change create", "/team run-verifier " + task.id(), "/summary") : List.of();
        List<String> policy = List.of("role=" + decision.role(), "tool=" + decision.toolName(),
                "decision=" + decision.decisionType(), "requiresApproval=" + decision.requiresApproval(),
                "denied=" + decision.denied(), "requestId=" + result.approvalRequestId(),
                "reasons=" + String.join(",", decision.reasons()));
        String status = decision.denied() ? "DENIED" : decision.requiresApproval() ? "APPROVAL_REQUIRED"
                : result.executed() ? "EXECUTED" : "SKIPPED";
        return new WorkerExecutionResult(task.id(), task.sessionId(), task.role(), task.goal(),
                active != null && !active.workspacePath().isBlank() ? active.workspacePath() : workspace.toString(),
                teams.whiteboard(task.sessionId()).readSummary(), List.of(), List.of(),
                "Policy-gated role tool-call " + decision.toolName() + " -> " + decision.decisionType(),
                findings, risks, List.of(), List.of(new TeamArtifact(null, task.id(),
                ".team/" + task.sessionId() + "/workers.jsonl", "Policy-gated tool call for " + task.id(),
                "role_tool_call", null)), policy, List.of(),
                decision.requiresApproval() ? List.of(decision.toolName() + " requires approval requestId=" + result.approvalRequestId()) : List.of(),
                actions, recommendation, result.executed() ? 0.72d : 0.45d, status, null);
    }

    private static String renderToolResult(PolicyAwareToolExecutor.PolicyToolResult result, WorkerExecutionResult report) {
        PolicyDecision decision = result.decision();
        return "team role tool-call\ntaskId: " + report.taskId() + "\nrole: " + decision.role()
                + "\ntoolName: " + decision.toolName() + "\nworkspacePath: " + report.workspacePath()
                + "\npolicy decision: " + decision.decisionType() + "\nreasons: " + inline(decision.reasons())
                + "\nrequiresApproval: " + decision.requiresApproval() + "\ndenied: " + decision.denied()
                + (!result.approvalRequestId().isBlank() ? "\napproval requestId: " + result.approvalRequestId() : "")
                + "\ntool result: " + none(result.resultSummary()) + "\n"
                + (report.risks().stream().anyMatch(risk -> risk.startsWith("local workspace warning"))
                    ? "warning: local workspace is active; consider /workspace create --mode worktree <goal>\n" : "")
                + (!report.changeSetRecommendation().isBlank() ? "next: " + report.changeSetRecommendation() + "\n" : "")
                + "status: " + report.status();
    }

    private Map<String, Object> stepArgs(PendingImplementationStep step) {
        Map<String, Object> args = new LinkedHashMap<>();
        switch (step.type()) {
            case READ -> { args.put("path", step.targetPath()); args.put("offset", 1); args.put("limit", 200); }
            case EDIT -> { args.put("path", step.targetPath()); args.put("old_text", step.oldText());
                args.put("new_text", step.newText()); args.put("replace_all", false); }
            case WRITE -> { args.put("path", step.targetPath()); args.put("content", step.newText()); }
            case EXEC_TEST -> args.put("command", step.command());
            default -> { }
        }
        return args;
    }

    private static String toolName(PendingImplementationStep step) {
        return switch (step.type()) {
            case READ -> "read_file";
            case EDIT -> "edit_file";
            case WRITE -> "write_file";
            case EXEC_TEST -> "exec";
            default -> throw new IllegalArgumentException("step is not tool-backed: " + step.type());
        };
    }

    private void storeChangeSetContext(Session session, GitChangeSet changeSet, ChangeSetRenderer renderer) {
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_ID_KEY, changeSet.id());
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_SUMMARY_KEY, renderer.summaryLine(changeSet));
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_STATUS_KEY, changeSet.status().name());
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_COMMIT_HASH_KEY, changeSet.commitHash());
        session.getMetadata().put(SessionRuntimeKeys.CHANGESET_ROLLBACK_STATUS_KEY, changeSet.rollbackStatus());
        sessions.save(session);
    }

    private void traceStep(Session session, TraceEventType type, PendingImplementationStep step) {
        trace(session, type, "implementation step lifecycle",
                Map.of("stepId", step.id(), "taskId", step.taskId(), "teamSessionId", step.teamSessionId(),
                        "type", step.type().name(), "status", step.status().name(), "validationErrors", step.validationErrors()),
                step.teamSessionId(), "", "");
    }

    private void traceGate(Session session, TraceEventType type, PendingImplementationStep step, StepGateResult gate) {
        trace(session, type, "implementation step gate",
                Map.of("stepId", step.id(), "taskId", step.taskId(), "teamSessionId", step.teamSessionId(),
                        "type", step.type().name(), "status", step.status().name(), "reasons", gate.reasons(),
                        "requiredActions", gate.requiredActions(), "nextSuggestedCommand", gate.nextSuggestedCommand()),
                step.teamSessionId(), "", "");
    }

    private void trace(Session session, TraceEventType type, String message, Map<String, Object> payload,
                       String teamSessionId, String changeSetId, String approvalId) {
        if (traces == null) return;
        try {
            String sessionId = session != null ? session.getKey() : "";
            TraceEvent event = traces.append(new TraceEvent(traces.traceIdForSession(sessionId), null, "", sessionId,
                    teamSessionId, changeSetId, approvalId, type, "team", message, payload, null, null));
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

    private PendingImplementationStep requireStep(String id) {
        PendingImplementationStep step = teams.findImplementationStep(id);
        if (step == null) throw new IllegalArgumentException("implementation step not found: " + id);
        return step;
    }

    private static Map<String, Object> parseJson(String raw) {
        try {
            return MAPPER.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid json arguments: " + e.getMessage());
        }
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
