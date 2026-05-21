package ricbot.domain.team;

import java.util.Comparator;
import java.util.List;

public class StepAuditCompactor {

    public StepAuditSummary compact(String taskId, List<PendingImplementationStep> steps, List<StepAuditRecord> records) {
        List<PendingImplementationStep> safeSteps = steps != null ? steps : List.of();
        List<StepAuditRecord> safeRecords = records != null ? records : List.of();
        String teamSessionId = safeSteps.stream().map(PendingImplementationStep::teamSessionId).filter(value -> !value.isBlank()).findFirst()
                .orElseGet(() -> safeRecords.stream().map(StepAuditRecord::teamSessionId).filter(value -> !value.isBlank()).findFirst().orElse(""));
        StepAuditRecord latest = safeRecords.stream()
                .max(Comparator.comparing(StepAuditRecord::createdAt))
                .orElse(null);
        List<String> linkedChangeSetIds = safeRecords.stream()
                .map(StepAuditRecord::changeSetId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        String latestChangeSetId = safeRecords.stream()
                .filter(record -> !record.changeSetId().isBlank())
                .max(Comparator.comparing(StepAuditRecord::createdAt))
                .map(StepAuditRecord::changeSetId)
                .orElse("");
        String latestVerificationStatus = safeRecords.stream()
                .filter(record -> !record.verificationStatus().isBlank())
                .max(Comparator.comparing(StepAuditRecord::createdAt))
                .map(StepAuditRecord::verificationStatus)
                .orElse("");
        List<String> unresolvedBlocked = safeSteps.stream()
                .filter(step -> step.status() == ImplementationStepStatus.BLOCKED)
                .map(PendingImplementationStep::id)
                .toList();
        List<String> failedSteps = safeSteps.stream()
                .filter(step -> step.status() == ImplementationStepStatus.FAILED)
                .map(PendingImplementationStep::id)
                .toList();
        List<String> readySteps = safeSteps.stream()
                .filter(step -> step.status() == ImplementationStepStatus.READY)
                .map(PendingImplementationStep::id)
                .toList();
        String nextSuggested = safeSteps.stream()
                .filter(step -> step.status() == ImplementationStepStatus.READY || step.status() == ImplementationStepStatus.DRAFT || step.status() == ImplementationStepStatus.BLOCKED)
                .sorted(Comparator.comparingInt(PendingImplementationStep::orderIndex).thenComparing(PendingImplementationStep::createdAt))
                .map(PendingImplementationStep::id)
                .findFirst()
                .orElse("");
        int approvalRequired = (int) safeRecords.stream().filter(record -> record.eventType() == StepAuditEventType.STEP_APPROVAL_REQUIRED).count();
        int approved = (int) safeRecords.stream().filter(record -> record.eventType() == StepAuditEventType.STEP_APPROVED).count();
        StepAuditHealth health;
        if (!failedSteps.isEmpty() || safeRecords.stream().anyMatch(record -> record.eventType() == StepAuditEventType.STEP_FAILED)) {
            health = StepAuditHealth.FAILED;
        } else if (!unresolvedBlocked.isEmpty()) {
            health = StepAuditHealth.BLOCKED;
        } else if (approvalRequired > approved) {
            health = StepAuditHealth.NEEDS_REVIEW;
        } else if ("PASS".equalsIgnoreCase(latestVerificationStatus)) {
            health = StepAuditHealth.HEALTHY;
        } else {
            health = StepAuditHealth.NEEDS_REVIEW;
        }
        return new StepAuditSummary(
                taskId,
                teamSessionId,
                safeSteps.size(),
                count(safeRecords, StepAuditEventType.STEP_CREATED),
                count(safeRecords, StepAuditEventType.STEP_UPDATED),
                countStatus(safeSteps, ImplementationStepStatus.READY),
                countStatus(safeSteps, ImplementationStepStatus.BLOCKED),
                countStatus(safeSteps, ImplementationStepStatus.APPLIED),
                countStatus(safeSteps, ImplementationStepStatus.REJECTED),
                countStatus(safeSteps, ImplementationStepStatus.FAILED),
                approvalRequired,
                count(safeRecords, StepAuditEventType.STEP_TOOL_APPLIED),
                linkedChangeSetIds,
                latestChangeSetId,
                latestVerificationStatus,
                latest != null ? latest.eventType().name() : "",
                unresolvedBlocked,
                failedSteps,
                readySteps,
                nextSuggested,
                health
        );
    }

    private int count(List<StepAuditRecord> records, StepAuditEventType type) {
        return (int) records.stream().filter(record -> record.eventType() == type).count();
    }

    private int countStatus(List<PendingImplementationStep> steps, ImplementationStepStatus status) {
        return (int) steps.stream().filter(step -> step.status() == status).count();
    }
}
