package ricbot.domain.team;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TeamTaskReportService {
    private final StepAuditService stepAuditService;

    public TeamTaskReportService(StepAuditService stepAuditService) {
        this.stepAuditService = stepAuditService;
    }

    public TeamTaskReport buildReport(String teamSessionId, String taskId, String title) {
        StepAuditSummary summary = stepAuditService.summarizeTask(taskId);
        return buildReport(teamSessionId, summary, title);
    }

    TeamTaskReport buildReport(String teamSessionId, StepAuditSummary summary, String title) {
        StepAuditSummary safe = summary != null ? summary : emptySummary("");
        String resolvedSessionId = !clean(teamSessionId).isBlank() ? clean(teamSessionId) : safe.teamSessionId();
        int completed = safe.appliedCount();
        int failed = safe.failedCount();
        int pending = Math.max(0, safe.totalSteps() - completed - failed);
        TeamTaskStatus status = status(safe, completed, failed, pending);
        TeamTaskHealth health = health(safe, completed, failed);
        List<String> actions = suggestedNextActions(safe, completed, failed, pending);
        return new TeamTaskReport(
                resolvedSessionId,
                safe.taskId(),
                title,
                status,
                health,
                safe.totalSteps(),
                completed,
                failed,
                pending,
                safe.linkedAuditEventsCount(),
                safe.latestEvent(),
                safe.latestChangeSetId(),
                safe.latestVerificationStatus(),
                safe.durationMillis(),
                safe.warnings(),
                actions,
                Map.of(
                        "auditHealth", safe.auditHealth().name(),
                        "createdCount", safe.createdCount(),
                        "updatedCount", safe.updatedCount(),
                        "approvalRequiredCount", safe.approvalRequiredCount(),
                        "toolAppliedCount", safe.toolAppliedCount(),
                        "nextSuggestedStepId", safe.nextSuggestedStepId()
                )
        );
    }

    private TeamTaskStatus status(StepAuditSummary summary, int completed, int failed, int pending) {
        if (summary.totalSteps() == 0) {
            if (verifierFailed(summary.latestVerificationStatus())) {
                return TeamTaskStatus.FAILED;
            }
            if ("PASS".equalsIgnoreCase(clean(summary.latestVerificationStatus()))) {
                return TeamTaskStatus.COMPLETED;
            }
            if (!summary.latestEvent().isBlank() || summary.linkedAuditEventsCount() > 0) {
                return TeamTaskStatus.RUNNING;
            }
            return TeamTaskStatus.NOT_STARTED;
        }
        if (failed > 0) {
            return TeamTaskStatus.FAILED;
        }
        if (hasBlockingWarning(summary.warnings())) {
            return TeamTaskStatus.BLOCKED;
        }
        if (completed == summary.totalSteps()) {
            return TeamTaskStatus.COMPLETED;
        }
        if (pending > 0) {
            return TeamTaskStatus.RUNNING;
        }
        return TeamTaskStatus.UNKNOWN;
    }

    private TeamTaskHealth health(StepAuditSummary summary, int completed, int failed) {
        if (failed > 0 || verifierFailed(summary.latestVerificationStatus())) {
            return TeamTaskHealth.CRITICAL;
        }
        if ("PASS".equalsIgnoreCase(clean(summary.latestVerificationStatus()))) {
            return TeamTaskHealth.HEALTHY;
        }
        if (summary.totalSteps() == 0) {
            return TeamTaskHealth.UNKNOWN;
        }
        if (!summary.warnings().isEmpty()) {
            return TeamTaskHealth.WARNING;
        }
        if (completed == summary.totalSteps()) {
            return TeamTaskHealth.HEALTHY;
        }
        return TeamTaskHealth.WARNING;
    }

    private List<String> suggestedNextActions(StepAuditSummary summary, int completed, int failed, int pending) {
        List<String> actions = new ArrayList<>();
        if (summary.totalSteps() == 0 || containsWarning(summary.warnings(), "no implementation steps found")) {
            if (!summary.latestVerificationStatus().isBlank()) {
                actions.add("Review /team audit " + summary.taskId() + " --compact and continue with /change create if workspace diff exists.");
                return List.copyOf(actions);
            }
            actions.add("Run /team plan-steps " + summary.taskId() + " to generate implementation steps.");
            return List.copyOf(actions);
        }
        if (failed > 0) {
            actions.add("Inspect failed implementation steps and latest verifier output.");
        }
        if (verifierFailed(summary.latestVerificationStatus())) {
            actions.add("Review the latest ChangeSet or rerun the worker before verification.");
        }
        if (pending > 0) {
            String next = !summary.nextSuggestedStepId().isBlank()
                    ? "Run /team apply-step " + summary.nextSuggestedStepId() + " or /team next-step " + summary.taskId() + "."
                    : "Run /team next-step " + summary.taskId() + " and continue pending steps.";
            actions.add(next);
        }
        if (completed == summary.totalSteps() && "PASS".equalsIgnoreCase(summary.latestVerificationStatus())) {
            actions.add("Generate /summary or prepare a commit message.");
        }
        if (!summary.warnings().isEmpty()) {
            actions.add("Resolve report warnings before treating the task as complete.");
        }
        if (actions.isEmpty()) {
            actions.add("Review /team audit " + summary.taskId() + " --compact for the next step.");
        }
        return List.copyOf(actions);
    }

    private boolean hasBlockingWarning(List<String> warnings) {
        return containsWarning(warnings, "blocked") || containsWarning(warnings, "approval") || containsWarning(warnings, "denied");
    }

    private boolean containsWarning(List<String> warnings, String needle) {
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);
        return warnings != null && warnings.stream()
                .map(value -> value != null ? value.toLowerCase(Locale.ROOT) : "")
                .anyMatch(value -> value.contains(lowerNeedle));
    }

    private boolean verifierFailed(String latestVerifier) {
        String value = clean(latestVerifier).toLowerCase(Locale.ROOT);
        return value.contains("failed") || value.contains("error") || value.contains("reject");
    }

    private StepAuditSummary emptySummary(String taskId) {
        return new StepAuditSummary(taskId, "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(), "", "", "", "", "", 0, 0, List.of(), List.of(), List.of(), "",
                StepAuditHealth.NEEDS_REVIEW, List.of("no implementation steps found"));
    }

    private String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
