package ricbot.application.team;

import ricbot.domain.agent.SessionRuntimeKeys;
import ricbot.domain.agent.TaskSummaryService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.domain.team.TeamDecisionPolicy;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamEvent;
import ricbot.domain.team.TeamRole;
import ricbot.domain.team.TeamSession;
import ricbot.domain.team.TeamTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Application boundary for team-session lifecycle and team-use decisions. */
public final class TeamSessionApplicationService {
    private final SessionManager sessions;
    private final TeamEngine teams;

    public TeamSessionApplicationService(SessionManager sessions, TeamEngine teams) {
        this.sessions = sessions;
        this.teams = teams;
    }

    public String execute(Session session, String action, String rawArgs) {
        String args = clean(rawArgs);
        return switch (clean(action).toLowerCase(Locale.ROOT)) {
            case "start" -> start(session, args);
            case "status" -> status(session);
            case "list" -> list();
            case "resume" -> resume(session, requiredArg(args));
            case "archive" -> archive(session, requiredArg(args));
            case "suggest" -> suggest(args);
            case "suggest-current" -> suggestCurrent(session);
            case "events" -> events(session);
            case "whiteboard" -> whiteboard(session);
            case "abort" -> abort(session, requiredArg(args));
            default -> throw new IllegalArgumentException("unsupported team session action: " + action);
        };
    }

    public String resolveActiveSessionId(Session session) {
        String id = activeSessionId(session);
        if (!id.isBlank() && teams.findSession(id) != null && !teams.isArchived(id)) {
            storeContext(session, id);
            return id;
        }
        TeamSession latest = teams.loadLatestActiveSession();
        if (latest == null) return "";
        storeContext(session, latest.id());
        return latest.id();
    }

    public String requireActiveSessionId(Session session) {
        String id = resolveActiveSessionId(session);
        if (id.isBlank()) throw new IllegalStateException("no active team session. Run /team start <goal> first");
        return id;
    }

    public void storeContext(Session session, String teamSessionId) {
        if (session == null || clean(teamSessionId).isBlank()) return;
        session.getMetadata().put(SessionRuntimeKeys.TEAM_SESSION_ID_KEY, teamSessionId);
        session.getMetadata().put(SessionRuntimeKeys.TEAM_CONTEXT_KEY, teams.contextSnapshot(teamSessionId));
        sessions.save(session);
    }

    private String start(Session session, String goal) {
        if (goal.isBlank()) throw new IllegalArgumentException("missing team goal");
        TeamSession created = teams.createSession(goal);
        storeContext(session, created.id());
        return "team session started\nid: " + created.id() + "\nstate: " + created.state()
                + "\ngoal: " + created.goal() + "\nwhiteboard: " + teams.whiteboard(created.id()).relativeWhiteboardPath();
    }

    private String status(Session session) {
        String id = resolveActiveSessionId(session);
        if (id.isBlank()) return "No active team session.";
        storeContext(session, id);
        return teams.getStatus(id);
    }

    private String list() {
        List<TeamSession> values = teams.listSessions();
        if (values.isEmpty()) return "No team sessions.";
        StringBuilder out = new StringBuilder("team sessions\n");
        for (TeamSession value : values) {
            out.append("- ").append(value.id()).append(" [").append(value.state()).append("]")
                    .append(teams.isArchived(value.id()) ? " archived=true" : "")
                    .append(" updatedAt=").append(value.updatedAt()).append(" goal=").append(value.goal()).append("\n");
        }
        return out.toString().trim();
    }

    private String resume(Session session, String id) {
        TeamSession resumed = teams.resumeSession(id);
        storeContext(session, resumed.id());
        return "team session resumed\nid: " + resumed.id() + "\nstate: " + resumed.state()
                + "\ngoal: " + resumed.goal() + "\nwhiteboard: " + teams.whiteboard(resumed.id()).relativeWhiteboardPath();
    }

    private String archive(Session session, String id) {
        TeamSession archived = teams.archiveSession(id);
        if (id.equals(activeSessionId(session))) {
            session.getMetadata().remove(SessionRuntimeKeys.TEAM_SESSION_ID_KEY);
            session.getMetadata().remove(SessionRuntimeKeys.TEAM_CONTEXT_KEY);
            sessions.save(session);
        }
        return "team session archived\nid: " + archived.id() + "\nstate: " + archived.state();
    }

    private String suggest(String goal) {
        if (goal.isBlank()) throw new IllegalArgumentException("missing team goal");
        List<String> files = pathLike(goal);
        String lower = goal.toLowerCase(Locale.ROOT);
        CommandRiskLevel risk = containsAnyText(lower, "security", "approval", "risk", "permission", "provider", "config")
                ? CommandRiskLevel.HIGH : CommandRiskLevel.SAFE;
        boolean research = containsAnyText(lower, "research", "explore", "inspect", "调查", "研究");
        boolean verification = containsAnyText(lower, "verify", "test", "review", "测试", "验证");
        int steps = Math.max(1, files.size());
        if (containsAnyText(lower, "state", "flow", "restore") || research || verification) steps = Math.max(steps, 3);
        TeamDecisionPolicy.Decision decision = new TeamDecisionPolicy().evaluate(goal, risk, files, steps, research, verification);
        return renderSuggestion(decision.useTeam(), risk, steps, decision.reasons(), decision.suggestedRoles(), null);
    }

    private String suggestCurrent(Session session) {
        resolveActiveSessionId(session);
        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);
        List<String> changedFiles = summary.changedFiles();
        boolean highRisk = containsAny(summary.diffReviews(), "risk=high", "high risk", "blocked", "security",
                "approval", "provider", "agentloop", "toolregistry", "config", "ci");
        boolean missingTests = !summary.suggestedTests().isEmpty()
                && !suggestedTestsCovered(summary.suggestedTests(), summary.testCommands());
        boolean hasVerifier = !summary.verifierReports().isEmpty();
        CommandRiskLevel risk = highRisk ? CommandRiskLevel.HIGH : CommandRiskLevel.SAFE;
        int steps = Math.max(1, changedFiles.size());
        if (highRisk || missingTests || hasVerifier) steps = Math.max(steps, 3);
        TeamDecisionPolicy.Decision decision = new TeamDecisionPolicy().evaluate(
                !summary.goal().isBlank() ? summary.goal() : "Current task", risk, changedFiles, steps,
                !summary.diffReviews().isEmpty(), highRisk || missingTests || hasVerifier);
        ArrayList<String> reasons = new ArrayList<>(decision.reasons());
        if (highRisk && !reasons.contains("high risk diff present")) reasons.add("high risk diff present");
        if (missingTests && !reasons.contains("suggested tests are not covered")) reasons.add("suggested tests are not covered");
        if (hasVerifier && !reasons.contains("existing verifier report should be reviewed")) reasons.add("existing verifier report should be reviewed");
        ArrayList<TeamRole> roles = new ArrayList<>(decision.suggestedRoles());
        if ((highRisk || missingTests || hasVerifier) && !roles.contains(TeamRole.VERIFIER)) roles.add(TeamRole.VERIFIER);
        if (missingTests && !roles.contains(TeamRole.TESTER)) roles.add(TeamRole.TESTER);
        return renderSuggestion(decision.useTeam() || highRisk || missingTests || hasVerifier, risk, steps,
                reasons, roles, changedFiles);
    }

    private String events(Session session) {
        String id = requireActiveSessionId(session);
        storeContext(session, id);
        List<TeamEvent> values = teams.listEvents(id);
        if (values.isEmpty()) return "No team events.";
        StringBuilder out = new StringBuilder("team events\n");
        for (TeamEvent event : values) {
            out.append("- ").append(event.createdAt()).append(" ").append(event.type())
                    .append(" role=").append(event.role())
                    .append(!event.taskId().isBlank() ? " task=" + event.taskId() : "")
                    .append(" ").append(event.message()).append("\n");
        }
        return out.toString().trim();
    }

    private String whiteboard(Session session) {
        String id = requireActiveSessionId(session);
        storeContext(session, id);
        String summary = teams.whiteboard(id).readSummary();
        return "team whiteboard\npath: " + teams.whiteboard(id).relativeWhiteboardPath()
                + "\n\n" + (summary.isBlank() ? "(empty)" : summary);
    }

    private String abort(Session session, String taskId) {
        TeamTask task = teams.abortTask(taskId);
        storeContext(session, task.sessionId());
        return "team task aborted\ntaskId: " + task.id() + "\nstate: " + task.state();
    }

    private static String renderSuggestion(boolean useTeam, CommandRiskLevel risk, int steps,
                                           List<String> reasons, List<TeamRole> roles, List<String> changedFiles) {
        return "team suggestion\nuseTeam: " + useTeam + "\nriskLevel: " + risk + "\n"
                + (changedFiles != null ? "changedFiles: " + (changedFiles.isEmpty() ? "none" : String.join(", ", changedFiles)) + "\n" : "estimatedSteps: " + steps + "\n")
                + "reasons: " + (reasons.isEmpty() ? "none" : String.join(", ", reasons)) + "\n"
                + "suggestedRoles: " + (roles.isEmpty() ? "none" : String.join(", ", roles.stream().map(Enum::name).toList()));
    }

    private static String activeSessionId(Session session) {
        if (session == null || session.getMetadata() == null) return "";
        return String.valueOf(session.getMetadata().getOrDefault(SessionRuntimeKeys.TEAM_SESSION_ID_KEY, "")).trim();
    }

    private static String requiredArg(String value) {
        String[] parts = clean(value).split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) throw new IllegalArgumentException("missing argument");
        return parts[0];
    }

    private static List<String> pathLike(String text) {
        ArrayList<String> out = new ArrayList<>();
        String cleaned = clean(text).replace("{", " ").replace("}", " ").replace(",", " ");
        for (String token : cleaned.split("\\s+")) {
            String value = token.replace("\"", "").replace("'", "").trim();
            if (value.contains("/") || value.matches(".*\\.(java|md|json|ya?ml|txt)$")) {
                if (!out.contains(value)) out.add(value);
            }
        }
        return out;
    }

    private static boolean suggestedTestsCovered(List<String> suggested, List<String> executed) {
        for (String expected : suggested != null ? suggested : List.<String>of()) {
            String normalized = normalizeCommand(expected);
            boolean covered = (executed != null ? executed : List.<String>of()).stream()
                    .map(TeamSessionApplicationService::normalizeCommand)
                    .anyMatch(actual -> actual.contains(normalized) || normalized.contains(actual));
            if (!covered) return false;
        }
        return true;
    }

    private static boolean containsAny(List<String> values, String... needles) {
        for (String value : values != null ? values : List.<String>of()) {
            if (containsAnyText(clean(value).toLowerCase(Locale.ROOT), needles)) return true;
        }
        return false;
    }

    private static boolean containsAnyText(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String normalizeCommand(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replace("'", "").replace("\"", "")
                .replaceAll("\\s+", " ").trim();
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
