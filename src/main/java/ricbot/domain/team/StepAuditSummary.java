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
        String firstEventAt,
        String latestEventAt,
        long durationMillis,
        int linkedAuditEventsCount,
        List<String> unresolvedBlockedSteps,
        List<String> failedSteps,
        List<String> readySteps,
        String nextSuggestedStepId,
        StepAuditHealth auditHealth,
        List<String> warnings
) {
    public StepAuditSummary {
        taskId = clean(taskId);
        teamSessionId = clean(teamSessionId);
        linkedChangeSetIds = copy(linkedChangeSetIds);
        latestChangeSetId = clean(latestChangeSetId);
        latestVerificationStatus = clean(latestVerificationStatus);
        latestEvent = clean(latestEvent);
        firstEventAt = clean(firstEventAt);
        latestEventAt = clean(latestEventAt);
        durationMillis = Math.max(0L, durationMillis);
        linkedAuditEventsCount = Math.max(0, linkedAuditEventsCount);
        unresolvedBlockedSteps = copy(unresolvedBlockedSteps);
        failedSteps = copy(failedSteps);
        readySteps = copy(readySteps);
        nextSuggestedStepId = clean(nextSuggestedStepId);
        auditHealth = auditHealth != null ? auditHealth : StepAuditHealth.NEEDS_REVIEW;
        warnings = copy(warnings);
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
        out.put("firstEventAt", firstEventAt);
        out.put("latestEventAt", latestEventAt);
        out.put("durationMillis", durationMillis);
        out.put("linkedAuditEventsCount", linkedAuditEventsCount);
        out.put("unresolvedBlockedSteps", unresolvedBlockedSteps);
        out.put("failedSteps", failedSteps);
        out.put("readySteps", readySteps);
        out.put("nextSuggestedStepId", nextSuggestedStepId);
        out.put("auditHealth", auditHealth.name());
        out.put("warnings", warnings);
        return out;
    }

    public String renderCompact() {
        int completed = appliedCount + rejectedCount;
        int pending = Math.max(0, totalSteps - completed - failedCount);
        return "task=" + taskId
                + " teamSessionId=" + (teamSessionId.isBlank() ? "none" : teamSessionId)
                + " auditHealth=" + auditHealth
                + "\nsteps: total=" + totalSteps
                + " completed=" + completed
                + " pending=" + pending
                + " ready=" + readyCount
                + " blocked=" + blockedCount
                + " applied=" + appliedCount
                + " rejected=" + rejectedCount
                + " failed=" + failedCount
                + "\nmilestones: created=" + createdCount
                + " updated=" + updatedCount
                + " approvalsRequired=" + approvalRequiredCount
                + " toolApplied=" + toolAppliedCount
                + " linkedAuditEvents=" + linkedAuditEventsCount
                + "\nlatest: event=" + (latestEvent.isBlank() ? "none" : latestEvent)
                + " changeSet=" + (latestChangeSetId.isBlank() ? "none" : latestChangeSetId)
                + " verifier=" + (latestVerificationStatus.isBlank() ? "none" : latestVerificationStatus)
                + " nextSuggestedStepId=" + (nextSuggestedStepId.isBlank() ? "none" : nextSuggestedStepId)
                + "\ntime: firstEventAt=" + (firstEventAt.isBlank() ? "none" : firstEventAt)
                + " latestEventAt=" + (latestEventAt.isBlank() ? "none" : latestEventAt)
                + " durationMillis=" + durationMillis
                + "\nwarnings: " + (warnings.isEmpty() ? "none" : String.join("; ", warnings));
    }

    public StepAuditSummary withWarnings(List<String> nextWarnings) {
        return new StepAuditSummary(taskId, teamSessionId, totalSteps, createdCount, updatedCount, readyCount,
                blockedCount, appliedCount, rejectedCount, failedCount, approvalRequiredCount, toolAppliedCount,
                linkedChangeSetIds, latestChangeSetId, latestVerificationStatus, latestEvent, firstEventAt,
                latestEventAt, durationMillis, linkedAuditEventsCount, unresolvedBlockedSteps, failedSteps,
                readySteps, nextSuggestedStepId, auditHealth, nextWarnings);
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
