package ricbot.application.team;

import ricbot.application.workspace.WorkspaceApplicationService;
import ricbot.domain.session.Session;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamExecutionService;
import ricbot.domain.team.TeamTaskReport;
import ricbot.domain.team.TeamWorkerRunner;
import ricbot.domain.team.VerificationResult;
import ricbot.domain.team.VerifierOutputSanitizer;
import ricbot.domain.team.WorkerExecutionResult;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Application boundary for one complete Team task run. */
public final class TeamRunApplicationService {
    private final Path workspace;
    private final TeamEngine teams;
    private final TeamWorkerRunner workers;
    private final TeamSessionApplicationService teamSessions;
    private final WorkspaceApplicationService workspaces;

    public TeamRunApplicationService(Path workspace, TeamEngine teams, TeamWorkerRunner workers,
                                     TeamSessionApplicationService teamSessions,
                                     WorkspaceApplicationService workspaces) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.teams = teams;
        this.workers = workers;
        this.teamSessions = teamSessions;
        this.workspaces = workspaces;
    }

    public String run(Session session, String rawArgs) {
        String args = clean(rawArgs);
        boolean useWorktree = containsFlag(args, "--worktree");
        boolean verify = containsFlag(args, "--verify");
        String taskValue = stripFlags(args, Set.of("--worktree", "--verify"));
        if (taskValue.isBlank()) throw new IllegalArgumentException("missing team run task");
        String activeTeamId = teamSessions.resolveActiveSessionId(session);
        TeamExecutionService service = new TeamExecutionService(workspace, teams, workers);
        TeamExecutionService.TeamExecutionOptions options = new TeamExecutionService.TeamExecutionOptions(useWorktree, verify);
        TeamExecutionService.TeamExecutionResult result = taskValue.startsWith("teamtask_") && !taskValue.contains(" ")
                ? service.runTask(taskValue, options) : service.runUserTask(activeTeamId, taskValue, options);
        teamSessions.storeContext(session, result.teamSessionId());
        if (!result.workspaceSessionId().isBlank()) {
            WorkspaceSession active = new WorkspaceSessionStore(workspace).load(result.workspaceSessionId());
            if (active != null) workspaces.activate(session, active);
        }
        return render(result);
    }

    private static String render(TeamExecutionService.TeamExecutionResult result) {
        return "team execution\ntaskId: " + result.taskId() + "\nteamSessionId: " + result.teamSessionId()
                + "\nworkspaceSessionId: " + (result.workspaceSessionId().isBlank() ? "none" : result.workspaceSessionId())
                + "\nworkspacePath: " + result.workspacePath()
                + "\nworkerStatus: " + (result.workerResult() != null ? result.workerResult().status() : "none")
                + "\nverifierStatus: " + (result.verificationResult() != null ? result.verificationResult().status() : "SKIPPED")
                + (result.verificationResult() != null ? "\nverifierReason: " + result.verificationResult().reason() : "") + "\n"
                + verifierDetails(result.report())
                + "reportStatus: " + (result.report() != null ? result.report().status() : "UNKNOWN")
                + "\nreportHealth: " + (result.report() != null ? result.report().health() : "UNKNOWN")
                + "\ndiffSummary: " + diffSummary(result.diff()) + "\n"
                + workerDebug(result.workerResult()) + verifierOutput(result)
                + "next: /team report " + result.taskId()
                + (result.usedWorktree() ? " | /workspace diff " + result.workspaceSessionId() + " | /change create" : "");
    }

    private static String verifierOutput(TeamExecutionService.TeamExecutionResult result) {
        if (result == null || result.verifierOutput().isBlank()) return "";
        VerificationResult.Status status = result.verificationResult() != null
                ? result.verificationResult().status() : VerificationResult.Status.NEEDS_HUMAN;
        String display = VerifierOutputSanitizer.display(result.verifierOutput(), status);
        return display.isBlank() ? "" : "verifierOutput: " + abbreviate(display, 500) + "\n";
    }

    private static String verifierDetails(TeamTaskReport report) {
        if (report == null) return "";
        Map<String, Object> values = report.compactSummary();
        StringBuilder out = new StringBuilder();
        append(out, "structuredEvidence", values.get("structuredEvidenceSource"));
        append(out, "verifierCommand", values.get("verifierCommand"));
        append(out, "changedFilesCount", values.get("changedFilesCount"));
        return out.toString();
    }

    private static String workerDebug(WorkerExecutionResult worker) {
        if (worker == null || worker.policySummary().isEmpty()) return "";
        String toolCalls = valueAfter(worker.policySummary(), "debug:modelToolCalls=");
        String changedFiles = valueAfter(worker.policySummary(), "debug:afterChangedFiles=");
        if (changedFiles.isBlank()) changedFiles = String.join(", ", worker.relatedFiles());
        List<String> warnings = worker.policySummary().stream()
                .filter(line -> line.startsWith("warning:") || line.startsWith("reason:"))
                .map(line -> abbreviate(line, 220)).toList();
        if (toolCalls.isBlank() && changedFiles.isBlank() && warnings.isEmpty()) return "";
        StringBuilder out = new StringBuilder("workerSummary:\n");
        out.append("toolCalls: ").append(toolCalls.isBlank() ? "none recorded" : toolCalls).append("\n");
        if (!changedFiles.isBlank()) out.append("changedFiles: ").append(changedFiles).append("\n");
        for (String warning : warnings) out.append(warning).append("\n");
        return out.toString();
    }

    private static String valueAfter(List<String> values, String prefix) {
        return values.stream().filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).trim()).findFirst().orElse("");
    }

    private static String diffSummary(String diff) {
        return diff == null || diff.isBlank() ? "none" : diff.lines().count() + " diff lines";
    }

    private static void append(StringBuilder out, String label, Object raw) {
        String value = raw != null ? String.valueOf(raw).trim() : "";
        if (!value.isBlank()) out.append(label).append(": ").append(value).append("\n");
    }

    private static boolean containsFlag(String args, String flag) {
        for (String part : clean(args).split("\\s+")) if (flag.equalsIgnoreCase(part)) return true;
        return false;
    }

    private static String stripFlags(String args, Set<String> flags) {
        ArrayList<String> kept = new ArrayList<>();
        for (String part : clean(args).split("\\s+")) {
            if (!flags.contains(part.toLowerCase(Locale.ROOT)) && !part.isBlank()) kept.add(part);
        }
        return String.join(" ", kept).trim();
    }

    private static String abbreviate(String value, int limit) {
        String text = clean(value);
        return text.length() <= limit ? text : text.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
