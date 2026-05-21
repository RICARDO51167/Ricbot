package ricbot.domain.team;

import java.util.Comparator;
import java.util.List;

public class StepAuditLinker {

    public List<StepAuditRecord> link(List<PendingImplementationStep> steps, List<StepAuditRecord> records) {
        List<PendingImplementationStep> safeSteps = steps != null ? steps : List.of();
        return (records != null ? records : List.<StepAuditRecord>of()).stream()
                .map(record -> linkRecord(safeSteps, record))
                .toList();
    }

    public StepAuditRecord linkRecord(List<PendingImplementationStep> steps, StepAuditRecord record) {
        if (record == null || !record.stepId().isBlank()) {
            return record;
        }
        List<PendingImplementationStep> safeSteps = steps != null ? steps : List.of();
        String targetPath = metadataValue(record, "targetPath");
        if (!targetPath.isBlank()) {
            PendingImplementationStep targetMatch = safeSteps.stream()
                    .filter(step -> (step.type() == ImplementationStepType.EDIT || step.type() == ImplementationStepType.WRITE)
                            && targetPath.equals(step.targetPath()))
                    .findFirst()
                    .orElse(null);
            if (targetMatch != null) {
                return record.withStepLink(targetMatch.id(), "HIGH");
            }
        }
        if (record.eventType() == StepAuditEventType.STEP_CHANGESET_LINKED || !record.changeSetId().isBlank()) {
            PendingImplementationStep edit = latestApplied(safeSteps, ImplementationStepType.EDIT, ImplementationStepType.WRITE);
            if (edit != null) {
                return record.withStepLink(edit.id(), "MEDIUM");
            }
        }
        if (record.eventType() == StepAuditEventType.STEP_VERIFIED || !record.verificationStatus().isBlank()) {
            PendingImplementationStep verifier = latestApplied(safeSteps, ImplementationStepType.RUN_VERIFIER, ImplementationStepType.CREATE_CHANGESET);
            if (verifier != null) {
                return record.withStepLink(verifier.id(), "MEDIUM");
            }
            PendingImplementationStep plannedVerifier = safeSteps.stream()
                    .filter(step -> step.type() == ImplementationStepType.RUN_VERIFIER || step.type() == ImplementationStepType.CREATE_CHANGESET)
                    .max(Comparator.comparingInt(PendingImplementationStep::orderIndex))
                    .orElse(null);
            if (plannedVerifier != null) {
                return record.withStepLink(plannedVerifier.id(), "LOW");
            }
        }
        return record;
    }

    private PendingImplementationStep latestApplied(List<PendingImplementationStep> steps, ImplementationStepType first, ImplementationStepType second) {
        return steps.stream()
                .filter(step -> step.status() == ImplementationStepStatus.APPLIED && (step.type() == first || step.type() == second))
                .max(Comparator.comparing(PendingImplementationStep::updatedAt))
                .orElse(null);
    }

    private String metadataValue(StepAuditRecord record, String key) {
        Object raw = record.metadata().get(key);
        return raw != null ? String.valueOf(raw).trim() : "";
    }
}
