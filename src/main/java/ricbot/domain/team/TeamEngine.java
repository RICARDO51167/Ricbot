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
    private final Map<String, TeamSession> sessions = new LinkedHashMap<>();
    private final Map<String, TeamTask> tasks = new LinkedHashMap<>();
    private final Map<String, List<TeamEvent>> events = new LinkedHashMap<>();

    public TeamEngine(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    public TeamSession createSession(String goal) {
        TeamSession session = new TeamSession(null, goal, TeamTaskState.PLANNING, List.of(), null, null);
        sessions.put(session.id(), session);
        TeamWhiteboard whiteboard = whiteboard(session.id());
        whiteboard.appendNote("Leader started team session.\n\nGoal: " + session.goal());
        appendEvent(TeamEvent.of(session.id(), "", TeamRole.LEADER, "session_created", session.goal()));
        return session;
    }

    public TeamTask createTask(String sessionId, TeamRole role, String goal) {
        TeamSession session = requireSession(sessionId);
        TeamTask task = new TeamTask(null, session.id(), role, goal, TeamTaskState.CREATED, "", List.of(), null, "", null, null);
        tasks.put(task.id(), task);
        refreshSessionTasks(session.id());
        whiteboard(session.id()).appendNote("Task created: " + task.id() + "\nRole: " + task.role() + "\nGoal: " + task.goal());
        appendEvent(TeamEvent.of(session.id(), task.id(), task.role(), "task_created", task.goal()));
        return task;
    }

    public TeamTask startProducing(String taskId) {
        TeamTask task = updateTask(taskId, requireTask(taskId).withState(TeamTaskState.PRODUCING));
        appendEvent(TeamEvent.of(task.sessionId(), task.id(), task.role(), "producing_started", task.goal()));
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
        appendEvent(TeamEvent.of(task.sessionId(), task.id(), task.role(), "worker_result", summary));
        return task;
    }

    public TeamTask startVerifying(String taskId) {
        TeamTask task = updateTask(taskId, requireTask(taskId).withState(TeamTaskState.VERIFYING));
        appendEvent(TeamEvent.of(task.sessionId(), task.id(), TeamRole.VERIFIER, "verifying_started", task.goal()));
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
                + "\nReason: " + result.reason()
                + (!revisionRequest.isBlank() ? "\n" + revisionRequest : ""));
        appendEvent(TeamEvent.of(task.sessionId(), task.id(), TeamRole.VERIFIER, "verification_" + result.status().name().toLowerCase(java.util.Locale.ROOT), result.reason()));
        return task;
    }

    public TeamTask abortTask(String taskId) {
        TeamTask task = updateTask(taskId, requireTask(taskId).withState(TeamTaskState.ABORTED));
        appendEvent(TeamEvent.of(task.sessionId(), task.id(), TeamRole.LEADER, "task_aborted", task.goal()));
        return task;
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
        return events.getOrDefault(sessionId, List.of()).stream()
                .sorted(Comparator.comparing(TeamEvent::createdAt))
                .toList();
    }

    public TeamWhiteboard whiteboard(String sessionId) {
        return new TeamWhiteboard(workspace, sessionId);
    }

    public TeamSession findSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public TeamTask findTask(String taskId) {
        return tasks.get(taskId);
    }

    public Map<String, Object> contextSnapshot(String sessionId) {
        TeamSession session = sessions.get(sessionId);
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
        for (TeamTask task : session.tasks()) {
            if (task.verificationResult() != null) {
                verifierResults.add(task.id() + ": " + task.verificationResult().status() + " - " + task.verificationResult().reason());
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
        sessions.put(sessionId, new TeamSession(session.id(), session.goal(), nextState, sessionTasks, session.createdAt(), java.time.Instant.now().toString()));
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
        TeamSession session = sessions.get(sessionId);
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
}
