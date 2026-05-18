package ricbot.domain.team;

import ricbot.domain.agent.SessionRuntimeKeys;
import ricbot.domain.session.Session;

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
    private final VerificationService verificationService = new VerificationService();
    private final Map<String, TeamSession> sessions = new LinkedHashMap<>();
    private final Map<String, TeamTask> tasks = new LinkedHashMap<>();
    private final Map<String, List<TeamEvent>> events = new LinkedHashMap<>();

    public TeamEngine(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.store = new TeamSessionStore(this.workspace);
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
        for (TeamTask task : session.tasks()) {
            if (task.verificationResult() != null) {
                verifierResults.add(task.id() + ": " + task.verificationResult().status() + " - " + task.verificationResult().reason());
                verificationReports.add(renderVerificationReport(task));
            }
            if (!task.revisionRequest().isBlank()) {
                revisionRequests.add(task.id() + ": " + task.revisionRequest());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", session.toMap());
        out.put("recentEvents", recentEvents);
        out.put("whiteboardPath", whiteboard.relativeWhiteboardPath());
        out.put("whiteboardSummary", whiteboard.readSummary());
        out.put("verifierResults", verifierResults);
        out.put("verificationReports", verificationReports);
        out.put("verificationPath", workspace.relativize(store.sessionDir(sessionId).resolve("verification.jsonl")).toString().replace('\\', '/'));
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
