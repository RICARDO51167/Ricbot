package ricbot.application.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.session.Session;
import ricbot.domain.team.PendingImplementationStep;
import ricbot.domain.team.TeamArtifact;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamTask;
import ricbot.domain.team.TeamTaskReport;
import ricbot.domain.team.WorkerExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-side application service for durable team reports and audit timelines. */
public final class TeamReportApplicationService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final TeamEngine teams;
    private final TeamSessionApplicationService teamSessions;

    public TeamReportApplicationService(TeamEngine teams, TeamSessionApplicationService teamSessions) {
        this.teams = teams;
        this.teamSessions = teamSessions;
    }

    public String execute(Session session, String action, String rawArgs) {
        return switch (clean(action)) {
            case "worker-report" -> workerReport(session, requiredArg(rawArgs));
            case "report" -> taskReport(session, rawArgs);
            case "verifier-report" -> verifierReport(session, requiredArg(rawArgs));
            case "step-timeline" -> stepTimeline(session, requiredArg(rawArgs));
            case "task-timeline", "audit" -> taskTimeline(session, rawArgs);
            default -> throw new IllegalArgumentException("unsupported team report action: " + action);
        };
    }

    private String workerReport(Session session, String taskId) {
        teamSessions.resolveActiveSessionId(session);
        List<WorkerExecutionResult> reports = teams.workerReports(taskId);
        if (reports.isEmpty()) return "No worker report for task: " + taskId;
        StringBuilder out = new StringBuilder("team worker report\n");
        TeamTask task = teams.findTask(taskId);
        for (WorkerExecutionResult report : reports) out.append(renderWorker(report, task)).append("\n\n");
        return out.toString().trim();
    }

    private String taskReport(Session session, String rawArgs) {
        String taskId = requiredArg(rawArgs);
        TeamTask task = requireTask(taskId);
        teamSessions.storeContext(session, task.sessionId());
        TeamTaskReport report = teams.taskReport(taskId);
        if (flags(rawArgs, "--json").contains("--json")) {
            try {
                return MAPPER.writeValueAsString(report.toMap());
            } catch (Exception e) {
                throw new IllegalStateException("failed to render team task report json: " + e.getMessage());
            }
        }
        return renderTaskReport(report);
    }

    private String verifierReport(Session session, String taskId) {
        teamSessions.resolveActiveSessionId(session);
        List<Map<String, Object>> reports = teams.verificationReports(taskId);
        if (reports.isEmpty()) return "No verifier report for task: " + taskId;
        StringBuilder out = new StringBuilder("team verifier report\n");
        for (Map<String, Object> report : reports) {
            Map<?, ?> result = report.get("verificationResult") instanceof Map<?, ?> map ? map : Map.of();
            out.append("- taskId: ").append(report.getOrDefault("taskId", ""))
                    .append("\n  status: ").append(value(result, "status"))
                    .append("\n  riskLevel: ").append(value(result, "riskLevel"))
                    .append("\n  reasons: ").append(renderRawList(result.get("reasons")))
                    .append("\n  missingTests: ").append(renderRawList(result.get("missingTests")))
                    .append("\n  requiredActions: ").append(renderRawList(result.get("requiredActions")))
                    .append("\n  createdAt: ").append(report.getOrDefault("createdAt", "")).append("\n");
        }
        return out.toString().trim();
    }

    private String stepTimeline(Session session, String stepId) {
        PendingImplementationStep step = teams.findImplementationStep(stepId);
        if (step == null) throw new IllegalArgumentException("implementation step not found: " + stepId);
        teamSessions.storeContext(session, step.teamSessionId());
        return teams.renderStepTimeline(stepId);
    }

    private String taskTimeline(Session session, String rawArgs) {
        String taskId = requiredArg(rawArgs);
        TeamTask task = requireTask(taskId);
        teamSessions.storeContext(session, task.sessionId());
        List<String> flags = flags(rawArgs, "--compact", "--json");
        boolean compact = flags.contains("--compact");
        if (flags.contains("--json")) return compact ? teams.renderJsonCompactTaskAudit(taskId) : teams.renderJsonTaskAudit(taskId);
        return teams.renderTaskAudit(taskId, compact);
    }

    private TeamTask requireTask(String taskId) {
        TeamTask task = teams.findTask(taskId);
        if (task == null) {
            if (taskId.startsWith("team_") && !taskId.startsWith("teamtask_")) {
                throw new IllegalArgumentException("你传入的是 teamSessionId：" + taskId + "。\n"
                        + "/team report 需要 taskId，例如 teamtask_xxx。\n"
                        + "请使用最近输出中的 taskId；也可以用 /trace show " + taskId + " 查看相关事件。");
            }
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        return task;
    }

    private static String renderWorker(WorkerExecutionResult result, TeamTask task) {
        return "taskId: " + result.taskId() + "\nrole: " + result.role() + "\nworkspacePath: " + result.workspacePath()
                + "\nstatus: " + result.status() + "\nsummary: " + result.summary()
                + "\nfindings: " + inline(result.findings()) + "\nrisks: " + inline(result.risks())
                + "\nsuggestedTests: " + inline(result.suggestedTests()) + "\npolicy: " + inline(result.policySummary())
                + "\ndeveloperPlan: " + inline(result.developerPlan()) + "\nrequiredApprovals: " + inline(result.requiredApprovals())
                + "\nnextActions: " + inline(result.nextActions())
                + "\nchangeSetRecommendation: " + (result.changeSetRecommendation().isBlank() ? "none" : result.changeSetRecommendation())
                + "\nartifacts: " + (result.artifacts().isEmpty() ? "none" : String.join(", ", result.artifacts().stream().map(TeamArtifact::path).toList()))
                + "\nconfidence: " + String.format(java.util.Locale.ROOT, "%.2f", result.confidence())
                + "\nnextState: " + (task != null ? task.state() : "(unknown)");
    }

    private static String renderTaskReport(TeamTaskReport report) {
        return "team task report\ntaskId: " + report.taskId()
                + "\nteamSessionId: " + (report.teamSessionId().isBlank() ? "none" : report.teamSessionId())
                + "\ntitle: " + (report.title().isBlank() ? "none" : report.title())
                + "\nstatus: " + report.status() + "\nhealth: " + report.health()
                + "\nprogress: " + report.completedSteps() + "/" + report.totalSteps() + " failed=" + report.failedSteps() + " pending=" + report.pendingSteps()
                + "\nlinkedAuditRecords: " + report.linkedAuditRecords()
                + "\nlatestEvent: " + emptyAsNone(report.latestEvent())
                + "\nlatestChangeSet: " + emptyAsNone(report.latestChangeSet())
                + "\nlatestVerifier: " + emptyAsNone(report.latestVerifier()) + "\n"
                + verifierDetails(report.compactSummary())
                + "durationMillis: " + report.durationMillis()
                + "\nwarnings: " + inline(report.warnings())
                + "\nsuggestedNextActions: " + inline(report.suggestedNextActions());
    }

    private static String verifierDetails(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        append(out, "structured verifier decision", values.get("verifierReason"));
        append(out, "structuredEvidence", values.get("structuredEvidenceSource"));
        append(out, "verifierCommand", values.get("verifierCommand"));
        append(out, "verifierExitCode", values.get("exitCode"));
        append(out, "changedFilesCount", values.get("changedFilesCount"));
        return out.toString();
    }

    private static void append(StringBuilder out, String label, Object raw) {
        String value = raw != null ? String.valueOf(raw).trim() : "";
        if (!value.isBlank()) out.append(label).append(": ").append(value).append("\n");
    }

    private static List<String> flags(String rawArgs, String... allowed) {
        List<String> accepted = List.of(allowed);
        String[] parts = clean(rawArgs).split("\\s+");
        ArrayList<String> out = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String flag = parts[i].toLowerCase(java.util.Locale.ROOT);
            if (!accepted.contains(flag)) throw new IllegalArgumentException("unsupported report flag: " + parts[i]);
            if (!out.contains(flag)) out.add(flag);
        }
        return out;
    }

    private static String requiredArg(String raw) {
        String[] parts = clean(raw).split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) throw new IllegalArgumentException("missing argument");
        return parts[0];
    }

    private static String value(Map<?, ?> map, String key) {
        Object raw = map.get(key);
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String renderRawList(Object raw) {
        return raw instanceof List<?> list && !list.isEmpty()
                ? String.join("; ", list.stream().map(String::valueOf).toList()) : "none";
    }

    private static String inline(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join("; ", values);
    }

    private static String emptyAsNone(String value) {
        return clean(value).isBlank() ? "none" : value;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
