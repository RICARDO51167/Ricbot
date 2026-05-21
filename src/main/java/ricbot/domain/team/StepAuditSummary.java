package ricbot.domain.team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record StepAuditSummary(
        String taskId,
        String teamSessionId,
        int totalSteps,
        int createdCount,
        int updatedCount,
        int readyCount,
        int blockedCount,
        int appliedCount,
        int rejectedCount,
        int failedCount,
        int approvalRequiredCount,
        int toolAppliedCount,
        List<String> linkedChangeSetIds,
        String latestChangeSetId,
        String latestVerificationStatus,
        String latestEvent,
        List<String> unresolvedBlockedSteps,
        List<String> failedSteps,
        List<String> readySteps,
        String nextSuggestedStepId,
        StepAuditHealth auditHealth
) {
    public StepAuditSummary {
        taskId = clean(taskId);
        teamSessionId = clean(teamSessionId);
        linkedChangeSetIds = copy(linkedChangeSetIds);
        latestChangeSetId = clean(latestChangeSetId);
        latestVerificationStatus = clean(latestVerificationStatus);
        latestEvent = clean(latestEvent);
        unresolvedBlockedSteps = copy(unresolvedBlockedSteps);
        failedSteps = copy(failedSteps);
        readySteps = copy(readySteps);
        nextSuggestedStepId = clean(nextSuggestedStepId);
        auditHealth = auditHealth != null ? auditHealth : StepAuditHealth.NEEDS_REVIEW;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("teamSessionId", teamSessionId);
        out.put("totalSteps", totalSteps);
        out.put("createdCount", createdCount);
        out.put("updatedCount", updatedCount);
        out.put("readyCount", readyCount);
        out.put("blockedCount", blockedCount);
        out.put("appliedCount", appliedCount);
        out.put("rejectedCount", rejectedCount);
        out.put("failedCount", failedCount);
        out.put("approvalRequiredCount", approvalRequiredCount);
        out.put("toolAppliedCount", toolAppliedCount);
        out.put("linkedChangeSetIds", linkedChangeSetIds);
        out.put("latestChangeSetId", latestChangeSetId);
        out.put("latestVerificationStatus", latestVerificationStatus);
        out.put("latestEvent", latestEvent);
        out.put("unresolvedBlockedSteps", unresolvedBlockedSteps);
        out.put("failedSteps", failedSteps);
        out.put("readySteps", readySteps);
        out.put("nextSuggestedStepId", nextSuggestedStepId);
        out.put("auditHealth", auditHealth.name());
        return out;
    }

    public String renderCompact() {
        return "task=" + taskId
                + " auditHealth=" + auditHealth
                + " totalSteps=" + totalSteps
                + " ready=" + readyCount
                + " blocked=" + blockedCount
                + " applied=" + appliedCount
                + " failed=" + failedCount
                + " approvalsRequired=" + approvalRequiredCount
                + " toolApplied=" + toolAppliedCount
                + " latestEvent=" + (latestEvent.isBlank() ? "none" : latestEvent)
                + " latestChangeSetId=" + (latestChangeSetId.isBlank() ? "none" : latestChangeSetId)
                + " latestVerificationStatus=" + (latestVerificationStatus.isBlank() ? "none" : latestVerificationStatus)
                + " nextSuggestedStepId=" + (nextSuggestedStepId.isBlank() ? "none" : nextSuggestedStepId);
    }

    private static List<String> copy(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values != null ? values : List.<String>of()) {
            String cleaned = clean(value);
            if (!cleaned.isBlank() && !out.contains(cleaned)) {
                out.add(cleaned);
            }
        }
        return List.copyOf(out);
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
