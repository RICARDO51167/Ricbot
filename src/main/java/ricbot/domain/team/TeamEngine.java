package ricbot.domain.team;

import ricbot.domain.agent.SessionRuntimeKeys;
import ricbot.domain.session.Session;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TeamEngine {
    public static final String TEAM_SESSION_ID_KEY = SessionRuntimeKeys.TEAM_SESSION_ID_KEY;
    public static final String TEAM_CONTEXT_KEY = SessionRuntimeKeys.TEAM_CONTEXT_KEY;

    private final Path workspace;
    private final TeamSessionStore store;
    private final TraceStore traceStore;
    private final VerificationService verificationService = new VerificationService();
    private final TeamWorkerExecutor workerExecutor = new TeamWorkerExecutor(verificationService);
    private final Map<String, TeamSession> sessions = new LinkedHashMap<>();
    private final Map<String, TeamTask> tasks = new LinkedHashMap<>();
    private final Map<String, List<TeamEvent>> events = new LinkedHashMap<>();

    public TeamEngine(Path workspace) {
        this(workspace, null);
    }

    public TeamEngine(Path workspace, TraceStore traceStore) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.store = new TeamSessionStore(this.workspace);
        this.traceStore = traceStore;
        restoreKnownSessions();
    }

    public TeamSession createSession(String goal) {
        TeamSession session = new TeamSession(null, goal, TeamTaskState.PLANNING, List.of(), null, null);
        sessions.put(session.id(), session);
        TeamWhiteboard whiteboard = whiteboard(session.id());
        whiteboard.appendNote("Leader started team session.\n\nGoal: " + session.goal());
        store.saveSession(session);
        appendEvent(TeamEvent.of(session.id(), "", TeamRole.LEADER, TeamEvent.TEAM_STARTED, session.goal()));
        return session;
    }

    public TeamTask createTask(String sessionId, TeamRole role, String goal) {
        TeamSession session = requireSession(sessionId);
        TeamTask task = new TeamTask(null, session.id(), role, goal, TeamTaskState.CREATED, "", List.of(), null, "", null, null);
        tasks.put(task.id(), task);
        refreshSessionTasks(session.id());
        whiteboard(session.id()).appendNote("Task created: " + task.id() + "\nRole: " + task.role() + "\nGoal: " + task.goal());
        appendEvent(TeamEvent.of(session.id(), task.id(), task.role(), TeamEvent.TASK_CREATED, task.goal()));
        return task;
    }

    public TeamTask createVerifierTaskFromDiffReview(String sessionId, String diffReviewSummary) {
        String summary = diffReviewSummary != null && !diffReviewSummary.isBlank()
                ? diffReviewSummary.trim()
                : "Review current diff and task summary.";
        TeamTask task = createTask(sessionId, TeamRole.VERIFIER, "Verify diff review: " + summary);
        whiteboard(task.sessionId()).appendNote("Verifier task prepared from DiffReview.\n\n" + summary);
        return task;
    }

    public TeamTask startProducing(String taskId) {
        TeamTask task = updateTask(taskId, requireTask(taskId).withState(TeamTaskState.PRODUCING));
        appendEvent(TeamEvent.of(task.sessionId(), task.id(), task.role(), TeamEvent.TASK_PRODUCING, task.goal()));
        return task;
    }

    public TeamTask submitWorkerResult(String taskId, String summary, List<TeamArtifact> artifacts) {
        TeamTask current = requireTask(taskId);
        List<TeamArtifact> safeArtifacts = artifacts != null ? artifacts : List.of();
        TeamTask task = updateTask(current.id(), current.withWorkerResult(summary, safeArtifacts));
        TeamWhiteboard whiteboard = whiteboard(task.sessionId());
        whiteboard.appendNote("Worker result for " + task.id() + "\n\n" + task.summary());
        for (TeamArtifact artifact : safeArtifacts) {
            whiteboard.appendArtifact(artifact);
        }
        appendEvent(TeamEvent.of(task.sessionId(), task.id(), task.role(), TeamEvent.WORKER_RESULT_SUBMITTED, summary));
        return task;
    }

    public WorkerExecutionResult runWorker(String taskId, WorkerExecutionInput input) {
        TeamTask current = requireTask(taskId);
        if (current.role() == TeamRole.DEVELOPER) {
            throw new IllegalStateException("DEVELOPER automatic execution is disabled in V4.8");
        }
        WorkerExecutionInput merged = mergeWorkerInput(current, input);
        traceWorkerLifecycle(TraceEventType.WORKER_STARTED, current, merged, null, "");
        try {
            startProducing(taskId);
            WorkerExecutionResult result = workerExecutor.execute(merged);
            store.appendWorkerExecution(current.sessionId(), result);
            TeamTask task = submitWorkerResult(taskId, result.summary(), result.artifacts());
            startVerifying(task.id());
            whiteboard(current.sessionId()).appendNote(renderWorkerExecutionNote("Worker execution", result));
            appendEvent(TeamEvent.of(current.sessionId(), task.id(), result.role(), TeamEvent.WORKER_RESULT_SUBMITTED, result.summary(), Map.of(
                    "workspacePath", result.workspacePath(),
                    "status", result.status(),
                    "confidence", result.confidence()
            )));
            traceWorkerLifecycle(TraceEventType.WORKER_FINISHED, task, merged, result, "");
            return result;
        } catch (RuntimeException e) {
            traceWorkerLifecycle(TraceEventType.WORKER_FAILED, current, merged, null, e.getMessage());
            throw e;
        }
    }

    public WorkerExecutionResult runVerifier(String taskId, WorkerExecutionInput input) {
        TeamTask current = requireTask(taskId);
        WorkerExecutionInput merged = mergeWorkerInput(current, input, TeamRole.VERIFIER);
        traceWorkerLifecycle(TraceEventType.VERIFIER_STARTED, current, merged, null, "");
        try {
            startVerifying(taskId);
            WorkerExecutionResult result = workerExecutor.verify(merged);
            store.appendWorkerExecution(current.sessionId(), result);
            VerificationResult verification = verificationService.verify(verificationInputFromWorker(merged));
            TeamTask task = submitVerification(taskId, verification);
            whiteboard(current.sessionId()).appendNote(renderWorkerExecutionNote("Verifier execution", result));
            traceWorkerLifecycle(TraceEventType.VERIFIER_FINISHED, task, merged, result, "");
            return result;
        } catch (RuntimeException e) {
            traceWorkerLifecycle(TraceEventType.WORKER_FAILED, current, merged, null, e.getMessage());
            throw e;
        }
    }

    public TeamTask runVerifier(String taskId, VerificationInput input) {
        startVerifying(taskId);
        VerificationResult result = verificationService.verify(input != null ? input : VerificationInput.ofTask(requireTask(taskId)));
        return submitVerification(taskId, result);
    }

    public TeamTask startVerifying(String taskId) {
        TeamTask task = updateTask(taskId, requireTask(taskId).withState(TeamTaskState.VERIFYING));
        appendEvent(TeamEvent.of(task.sessionId(), task.id(), TeamRole.VERIFIER, TeamEvent.VERIFICATION_STARTED, task.goal()));
        return task;
    }

    public TeamTask submitVerification(String taskId, VerificationResult verification) {
        TeamTask current = requireTask(taskId);
        VerificationResult result = verification != null ? verification : VerificationResult.needsHuman("missing verifier result");
        TeamTaskState nextState = switch (result.status()) {
            case PASS -> TeamTaskState.DONE;
            case REJECT -> TeamTaskState.REVISING;
            case NEEDS_HUMAN -> TeamTaskState.NEEDS_HUMAN;
        };
        String revisionRequest = result.status() == VerificationResult.Status.REJECT
                ? "Revision requested: " + result.reason()
                : "";
        TeamTask task = updateTask(taskId, current.withVerification(result, nextState, revisionRequest));
        whiteboard(task.sessionId()).appendNote("Verifier result for " + task.id()
                + "\nStatus: " + result.status()
                + "\nRiskLevel: " + result.riskLevel()
                + "\nReason: " + result.reason()
                + (!result.missingTests().isEmpty() ? "\nMissingTests: " + String.join("; ", result.missingTests()) : "")
                + (!result.requiredActions().isEmpty() ? "\nRequiredActions: " + String.join("; ", result.requiredActions()) : "")
                + (!revisionRequest.isBlank() ? "\n" + revisionRequest : ""));
        store.appendVerification(task.sessionId(), task);
        appendEvent(TeamEvent.of(
                task.sessionId(),
                task.id(),
                TeamRole.VERIFIER,
                verificationEventType(result.status()),
                result.reason(),
                Map.of("confidence", result.confidence())
        ));
        if (result.status() == VerificationResult.Status.REJECT) {
            appendEvent(TeamEvent.of(task.sessionId(), task.id(), TeamRole.VERIFIER, TeamEvent.REVISION_REQUESTED, revisionRequest));
        }
        traceVerification(task, result);
        return task;
    }

    public TeamTask autoVerify(String taskId, VerificationInput input) {
        TeamTask current = requireTask(taskId);
        VerificationInput base = input != null ? input : VerificationInput.ofTask(current);
        VerificationInput merged = new VerificationInput(
                !base.taskId().isBlank() ? base.taskId() : current.id(),
                !base.taskGoal().isBlank() ? base.taskGoal() : current.goal(),
                !base.workerSummary().isBlank() ? base.workerSummary() : current.summary(),
                base.diffReviews(),
                base.taskSummary(),
                base.approvalRecords(),
                base.suggestedTests(),
                base.executedTests(),
                base.verifiedExperience(),
                !base.teamWhiteboardSummary().isBlank() ? base.teamWhiteboardSummary() : whiteboard(current.sessionId()).readSummary()
        );
        startVerifying(taskId);
        VerificationResult result = verificationService.verify(merged);
        return submitVerification(taskId, result);
    }

    public TeamTask abortTask(String taskId) {
        TeamTask task = updateTask(taskId, requireTask(taskId).withState(TeamTaskState.ABORTED));
        appendEvent(TeamEvent.of(task.sessionId(), task.id(), TeamRole.LEADER, TeamEvent.TASK_ABORTED, task.goal()));
        return task;
    }

    public TeamSession resumeSession(String sessionId) {
        TeamSession session = store.restoreActiveSession(sessionId);
        rememberSession(session);
        events.put(session.id(), new ArrayList<>(store.loadEvents(session.id())));
        appendEvent(TeamEvent.of(session.id(), "", TeamRole.LEADER, TeamEvent.TEAM_RESUMED, "Team session resumed."));
        return requireSession(session.id());
    }

    public TeamSession archiveSession(String sessionId) {
        TeamSession session = requireSession(sessionId);
        appendEvent(TeamEvent.of(session.id(), "", TeamRole.LEADER, TeamEvent.TEAM_ARCHIVED, "Team session archived."));
        return store.archiveSession(session.id());
    }

    public List<TeamSession> listSessions() {
        List<TeamSession> out = store.listSessions();
        for (TeamSession session : out) {
            rememberSession(session);
        }
        return out;
    }

    public TeamSession loadLatestActiveSession() {
        TeamSession session = store.loadLatestActiveSession();
        if (session != null) {
            rememberSession(session);
            events.put(session.id(), new ArrayList<>(store.loadEvents(session.id())));
        }
        return session;
    }

    public boolean isArchived(String sessionId) {
        return store.isArchived(sessionId);
    }

    public List<Map<String, Object>> verificationReports(String taskId) {
        TeamTask task = requireTask(taskId);
        return store.loadVerificationReportsForTask(task.sessionId(), task.id());
    }

    public List<WorkerExecutionResult> workerReports(String taskId) {
        TeamTask task = requireTask(taskId);
        return store.loadWorkerReportsForTask(task.sessionId(), task.id());
    }

    public String getStatus(String sessionId) {
        TeamSession session = requireSession(sessionId);
        StringBuilder sb = new StringBuilder();
        sb.append("team ").append(session.id()).append("\n");
        sb.append("goal: ").append(session.goal()).append("\n");
        sb.append("state: ").append(session.state()).append("\n");
        sb.append("tasks: ").append(session.tasks().size()).append("\n");
        for (TeamTask task : session.tasks()) {
            sb.append("- ").append(task.id())
                    .append(" [").append(task.role()).append("] ")
                    .append(task.state())
                    .append(" goal=").append(task.goal());
            if (!task.revisionRequest().isBlank()) {
                sb.append(" revision=").append(task.revisionRequest());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public List<TeamEvent> listEvents(String sessionId) {
        if (!events.containsKey(sessionId)) {
            events.put(sessionId, new ArrayList<>(store.loadEvents(sessionId)));
        }
        return events.getOrDefault(sessionId, List.of()).stream()
                .sorted(Comparator.comparing(TeamEvent::createdAt))
                .toList();
    }

    public TeamWhiteboard whiteboard(String sessionId) {
        return new TeamWhiteboard(workspace, sessionId);
    }

    public void recordArtifact(String sessionId, TeamArtifact artifact) {
        if (sessionId == null || sessionId.isBlank() || artifact == null) {
            return;
        }
        requireSession(sessionId);
        whiteboard(sessionId).appendArtifact(artifact);
        appendEvent(TeamEvent.of(sessionId, artifact.taskId(), TeamRole.LEADER, TeamEvent.ARTIFACT_RECORDED, artifact.summary(), Map.of("path", artifact.path(), "kind", artifact.kind())));
    }

    public TeamSession findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        TeamSession session = sessions.get(sessionId);
        if (session == null) {
            session = store.loadSession(sessionId);
            if (session != null) {
                rememberSession(session);
                events.put(session.id(), new ArrayList<>(store.loadEvents(session.id())));
            }
        }
        return session;
    }

    public TeamTask findTask(String taskId) {
        return tasks.get(taskId);
    }

    public Map<String, Object> contextSnapshot(String sessionId) {
        TeamSession session = findSession(sessionId);
        if (session == null) {
            return Map.of();
        }
        TeamWhiteboard whiteboard = whiteboard(sessionId);
        List<Map<String, Object>> recentEvents = listEvents(sessionId).stream()
                .sorted(Comparator.comparing(TeamEvent::createdAt).reversed())
                .limit(5)
                .map(TeamEvent::toMap)
                .toList();
        List<String> verifierResults = new ArrayList<>();
        List<String> revisionRequests = new ArrayList<>();
        List<String> verificationReports = new ArrayList<>();
        List<String> workerResults = new ArrayList<>();
        List<String> workerReports = new ArrayList<>();
        for (TeamTask task : session.tasks()) {
            if (task.verificationResult() != null) {
                verifierResults.add(task.id() + ": " + task.verificationResult().status() + " - " + task.verificationResult().reason());
                verificationReports.add(renderVerificationReport(task));
            }
            if (!task.revisionRequest().isBlank()) {
                revisionRequests.add(task.id() + ": " + task.revisionRequest());
            }
        }
        for (WorkerExecutionResult result : store.loadWorkerReports(sessionId).stream()
                .sorted(Comparator.comparing(WorkerExecutionResult::createdAt).reversed())
                .limit(5)
                .toList()) {
            workerResults.add(result.taskId() + ": " + result.role() + " " + result.status() + " - " + result.summary());
            workerReports.add(renderWorkerReport(result));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", session.toMap());
        out.put("recentEvents", recentEvents);
        out.put("whiteboardPath", whiteboard.relativeWhiteboardPath());
        out.put("whiteboardSummary", whiteboard.readSummary());
        out.put("verifierResults", verifierResults);
        out.put("verificationReports", verificationReports);
        out.put("workerResults", workerResults);
        out.put("workerReports", workerReports);
        out.put("verificationPath", workspace.relativize(store.sessionDir(sessionId).resolve("verification.jsonl")).toString().replace('\\', '/'));
        out.put("workerPath", workspace.relativize(store.sessionDir(sessionId).resolve("workers.jsonl")).toString().replace('\\', '/'));
        out.put("revisionRequests", revisionRequests);
        return out;
    }

    public static Map<String, Object> contextFromSession(Session session) {
        if (session == null || session.getMetadata() == null) {
            return Map.of();
        }
        Object raw = session.getMetadata().get(TEAM_CONTEXT_KEY);
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return out;
        }
        return Map.of();
    }

    private TeamTask updateTask(String taskId, TeamTask next) {
        tasks.put(taskId, next);
        refreshSessionTasks(next.sessionId());
        return next;
    }

    private void refreshSessionTasks(String sessionId) {
        TeamSession session = requireSession(sessionId);
        List<TeamTask> sessionTasks = tasks.values().stream()
                .filter(task -> sessionId.equals(task.sessionId()))
                .sorted(Comparator.comparing(TeamTask::createdAt))
                .toList();
        TeamTaskState nextState = deriveSessionState(sessionTasks, session.state());
        TeamSession next = new TeamSession(session.id(), session.goal(), nextState, sessionTasks, session.createdAt(), java.time.Instant.now().toString());
        sessions.put(sessionId, next);
        store.saveSession(next);
    }

    private TeamTaskState deriveSessionState(List<TeamTask> sessionTasks, TeamTaskState fallback) {
        if (sessionTasks.stream().anyMatch(task -> task.state() == TeamTaskState.NEEDS_HUMAN)) {
            return TeamTaskState.NEEDS_HUMAN;
        }
        if (sessionTasks.stream().anyMatch(task -> task.state() == TeamTaskState.REVISING)) {
            return TeamTaskState.REVISING;
        }
        if (sessionTasks.stream().anyMatch(task -> task.state() == TeamTaskState.VERIFYING)) {
            return TeamTaskState.VERIFYING;
        }
        if (sessionTasks.stream().anyMatch(task -> task.state() == TeamTaskState.PRODUCING)) {
            return TeamTaskState.PRODUCING;
        }
        if (!sessionTasks.isEmpty() && sessionTasks.stream().allMatch(task -> task.state() == TeamTaskState.DONE || task.state() == TeamTaskState.ABORTED)) {
            return TeamTaskState.DONE;
        }
        return fallback != null ? fallback : TeamTaskState.PLANNING;
    }

    private void appendEvent(TeamEvent event) {
        events.computeIfAbsent(event.sessionId(), ignored -> new ArrayList<>()).add(event);
        whiteboard(event.sessionId()).appendEvent(event);
        traceTeamEvent(event);
    }

    private void traceVerification(TeamTask task, VerificationResult result) {
        if (traceStore == null || task == null || result == null) {
            return;
        }
        try {
            traceStore.append(new TraceEvent(
                    traceStore.traceIdForSession(task.sessionId()),
                    null,
                    "",
                    "",
                    task.sessionId(),
                    "",
                    "",
                    TraceEventType.VERIFICATION_RESULT,
                    "verifier",
                    result.reason(),
                    Map.of(
                            "taskId", task.id(),
                            "status", result.status().name(),
                            "riskLevel", result.riskLevel().name(),
                            "missingTests", result.missingTests(),
                            "requiredActions", result.requiredActions(),
                            "confidence", result.confidence()
                    ),
                    null,
                    null
            ));
        } catch (Exception ignored) {
        }
    }

    private void traceTeamEvent(TeamEvent event) {
        if (traceStore == null || event == null) {
            return;
        }
        try {
            traceStore.append(new TraceEvent(
                    traceStore.traceIdForSession(event.sessionId()),
                    null,
                    "",
                    "",
                    event.sessionId(),
                    "",
                    "",
                    TraceEventType.TEAM_EVENT,
                    event.actor(),
                    event.message(),
                    Map.of(
                            "teamEventType", event.type(),
                            "taskId", event.taskId(),
                            "eventId", event.eventId()
                    ),
                    null,
                    null
            ));
        } catch (Exception ignored) {
        }
    }

    private WorkerExecutionInput mergeWorkerInput(TeamTask task, WorkerExecutionInput input) {
        return mergeWorkerInput(task, input, task != null ? task.role() : TeamRole.EXPLORER);
    }

    private WorkerExecutionInput mergeWorkerInput(TeamTask task, WorkerExecutionInput input, TeamRole roleOverride) {
        WorkerExecutionInput safe = input != null ? input : WorkerExecutionInput.ofTask(task, workspace.toString(), "");
        return new WorkerExecutionInput(
                !safe.taskId().isBlank() ? safe.taskId() : task.id(),
                !safe.teamSessionId().isBlank() ? safe.teamSessionId() : task.sessionId(),
                roleOverride != null ? roleOverride : task.role(),
                !safe.goal().isBlank() ? safe.goal() : task.goal(),
                !safe.workspacePath().isBlank() ? safe.workspacePath() : workspace.toString(),
                !safe.whiteboardSummary().isBlank() ? safe.whiteboardSummary() : whiteboard(task.sessionId()).readSummary(),
                safe.relatedFiles(),
                safe.verifiedExperience(),
                safe.constraints(),
                !safe.summary().isBlank() ? safe.summary() : task.summary(),
                safe.findings(),
                safe.risks(),
                safe.suggestedTests(),
                safe.artifacts(),
                safe.confidence(),
                safe.status()
        );
    }

    private VerificationInput verificationInputFromWorker(WorkerExecutionInput input) {
        return new VerificationInput(
                input.taskId(),
                input.goal(),
                !input.summary().isBlank() ? input.summary() : String.join("; ", input.findings()),
                input.findings(),
                input.whiteboardSummary(),
                List.of(),
                input.suggestedTests(),
                input.constraints(),
                input.verifiedExperience(),
                input.whiteboardSummary()
        );
    }

    private String renderWorkerExecutionNote(String title, WorkerExecutionResult result) {
        if (result == null) {
            return title + "\n(no result)";
        }
        return title + " for " + result.taskId()
                + "\nRole: " + result.role()
                + "\nStatus: " + result.status()
                + "\nWorkspace: " + result.workspacePath()
                + "\nSummary: " + result.summary()
                + (!result.findings().isEmpty() ? "\nFindings: " + String.join("; ", result.findings()) : "")
                + (!result.risks().isEmpty() ? "\nRisks: " + String.join("; ", result.risks()) : "")
                + (!result.suggestedTests().isEmpty() ? "\nSuggestedTests: " + String.join("; ", result.suggestedTests()) : "");
    }

    private String renderWorkerReport(WorkerExecutionResult result) {
        if (result == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("task=" + result.taskId());
        parts.add("role=" + result.role());
        parts.add("status=" + result.status());
        parts.add("workspacePath=" + result.workspacePath());
        parts.add("summary=" + result.summary());
        if (!result.findings().isEmpty()) {
            parts.add("findings=" + String.join("; ", result.findings()));
        }
        if (!result.risks().isEmpty()) {
            parts.add("risks=" + String.join("; ", result.risks()));
        }
        if (!result.suggestedTests().isEmpty()) {
            parts.add("suggestedTests=" + String.join("; ", result.suggestedTests()));
        }
        return String.join(" | ", parts);
    }

    private void traceWorkerLifecycle(
            TraceEventType type,
            TeamTask task,
            WorkerExecutionInput input,
            WorkerExecutionResult result,
            String error
    ) {
        if (traceStore == null || task == null || type == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", task.id());
            payload.put("teamSessionId", task.sessionId());
            payload.put("role", input != null ? input.role().name() : task.role().name());
            payload.put("workspacePath", input != null ? input.workspacePath() : "");
            payload.put("workspaceSessionId", workspaceSessionIdFromPath(input != null ? input.workspacePath() : ""));
            payload.put("status", result != null ? result.status() : "");
            payload.put("confidence", result != null ? result.confidence() : 0d);
            payload.put("error", error != null ? error : "");
            traceStore.append(new TraceEvent(
                    traceStore.traceIdForSession(task.sessionId()),
                    null,
                    "",
                    "",
                    task.sessionId(),
                    "",
                    "",
                    type,
                    "team",
                    result != null ? result.summary() : type.name(),
                    payload,
                    null,
                    null
            ));
        } catch (Exception ignored) {
        }
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

    private TeamSession requireSession(String sessionId) {
        TeamSession session = findSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("team session not found: " + sessionId);
        }
        return session;
    }

    private TeamTask requireTask(String taskId) {
        TeamTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        return task;
    }

    private void restoreKnownSessions() {
        try {
            for (TeamSession session : store.listSessions()) {
                rememberSession(session);
                events.put(session.id(), new ArrayList<>(store.loadEvents(session.id())));
            }
        } catch (Exception ignored) {
        }
    }

    private void rememberSession(TeamSession session) {
        if (session == null) {
            return;
        }
        sessions.put(session.id(), session);
        for (TeamTask task : session.tasks()) {
            tasks.put(task.id(), task);
        }
    }

    private String verificationEventType(VerificationResult.Status status) {
        return switch (status) {
            case PASS -> TeamEvent.VERIFICATION_PASSED;
            case REJECT -> TeamEvent.VERIFICATION_REJECTED;
            case NEEDS_HUMAN -> TeamEvent.HUMAN_NEEDED;
        };
    }

    private String renderVerificationReport(TeamTask task) {
        VerificationResult result = task.verificationResult();
        if (result == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("task=" + task.id());
        parts.add("status=" + result.status());
        parts.add("riskLevel=" + result.riskLevel());
        if (!result.reasons().isEmpty()) {
            parts.add("reasons=" + String.join("; ", result.reasons()));
        }
        if (!result.missingTests().isEmpty()) {
            parts.add("missingTests=" + String.join("; ", result.missingTests()));
        }
        if (!result.requiredActions().isEmpty()) {
            parts.add("requiredActions=" + String.join("; ", result.requiredActions()));
        }
        if (!result.suggestedExperienceActions().isEmpty()) {
            parts.add("suggestedExperienceActions=" + String.join("; ", result.suggestedExperienceActions()));
        }
        return String.join(" | ", parts);
    }
}
