package ricbot.application.team;

import ricbot.domain.agent.TaskSummaryService;
import ricbot.domain.change.ChangeSetService;
import ricbot.domain.session.Session;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamRole;
import ricbot.domain.team.TeamTask;
import ricbot.domain.team.VerificationInput;
import ricbot.domain.team.VerificationResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Application boundary for creating tasks and recording verification decisions. */
public final class TeamTaskApplicationService {
    private final Path workspace;
    private final TeamEngine teams;
    private final TeamSessionApplicationService teamSessions;

    public TeamTaskApplicationService(Path workspace, TeamEngine teams, TeamSessionApplicationService teamSessions) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.teams = teams;
        this.teamSessions = teamSessions;
    }

    public String execute(Session session, String action, String rawArgs) {
        return switch (clean(action)) {
            case "task" -> create(session, rawArgs);
            case "verify" -> verify(session, rawArgs);
            case "auto-verify" -> autoVerify(session, requiredArg(rawArgs));
            default -> throw new IllegalArgumentException("unsupported team task action: " + action);
        };
    }

    private String create(Session session, String rawArgs) {
        String teamSessionId = teamSessions.requireActiveSessionId(session);
        String[] parts = clean(rawArgs).split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) throw new IllegalArgumentException("missing team task goal");
        TeamRole role;
        try {
            role = TeamRole.valueOf(parts[0].replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("unknown team role: " + parts[0]);
        }
        TeamTask task = teams.createTask(teamSessionId, role, parts[1].trim());
        teamSessions.storeContext(session, teamSessionId);
        return "team task created\nid: " + task.id() + "\nrole: " + task.role() + "\nstate: " + task.state()
                + "\ngoal: " + task.goal() + "\nwhiteboard: " + teams.whiteboard(teamSessionId).relativeWhiteboardPath();
    }

    private String verify(Session session, String rawArgs) {
        String[] parts = clean(rawArgs).split("\\s+", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("usage: /team verify <taskId> pass|reject|needs-human <reason>");
        }
        String reason = parts.length >= 3 && !parts[2].isBlank() ? parts[2].trim() : "manual verifier result";
        VerificationResult verification = switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "pass", "passed" -> VerificationResult.pass(reason);
            case "reject", "rejected" -> VerificationResult.reject(reason);
            case "needs-human", "needs_human", "human" -> VerificationResult.needsHuman(reason);
            default -> throw new IllegalArgumentException("verification status must be pass, reject, or needs-human");
        };
        teams.startVerifying(parts[0]);
        TeamTask task = teams.submitVerification(parts[0], verification);
        teamSessions.storeContext(session, task.sessionId());
        return "team verification recorded\ntaskId: " + task.id() + "\nstate: " + task.state()
                + "\nstatus: " + task.verificationResult().status() + "\nreason: " + task.verificationResult().reason()
                + (!task.revisionRequest().isBlank() ? "\nrevisionRequest: " + task.revisionRequest() : "");
    }

    private String autoVerify(Session session, String taskId) {
        teamSessions.resolveActiveSessionId(session);
        TeamTask existing = teams.findTask(taskId);
        if (existing == null) throw new IllegalArgumentException("team task not found: " + taskId);
        TaskSummaryService.TaskSummary summary = new TaskSummaryService().summarizeCurrentTask(session);
        VerificationInput input = new VerificationInput(
                existing.id(), existing.goal(), existing.summary(), summary.diffReviews(), renderSummary(summary),
                summary.approvalRecords(), summary.suggestedTests(), summary.testCommands(),
                teams.whiteboard(existing.sessionId()).readSummary());
        TeamTask task = teams.autoVerify(taskId, input);
        teamSessions.storeContext(session, task.sessionId());
        VerificationResult result = task.verificationResult();
        String hint = result.status() == VerificationResult.Status.PASS && new ChangeSetService(workspace).hasWorkingTreeChanges()
                ? "\nchangeSetHint: working tree has changes; run /change create" : "";
        return "team auto verification recorded\ntaskId: " + task.id() + "\nstate: " + task.state()
                + "\nstatus: " + result.status() + "\nriskLevel: " + result.riskLevel()
                + "\nreasons: " + inline(result.reasons()) + "\nmissingTests: " + inline(result.missingTests())
                + "\nrequiredActions: " + inline(result.requiredActions()) + hint;
    }

    private static String renderSummary(TaskSummaryService.TaskSummary summary) {
        return "goal=" + summary.goal() + " changedFiles=" + String.join(",", summary.changedFiles())
                + " blockers=" + String.join(",", summary.blockers())
                + " suggestedTests=" + String.join(",", summary.suggestedTests())
                + " executedTests=" + String.join(",", summary.testCommands());
    }

    private static String inline(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join("; ", values);
    }

    private static String requiredArg(String raw) {
        String[] parts = clean(raw).split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) throw new IllegalArgumentException("missing argument");
        return parts[0];
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
