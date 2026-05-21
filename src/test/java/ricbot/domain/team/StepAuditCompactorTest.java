package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import ricbot.domain.security.CommandRiskLevel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepAuditCompactorTest {

    private final StepAuditCompactor compactor = new StepAuditCompactor();

    @Test
    void failedStepProducesFailedHealth() {
        StepAuditSummary summary = compactor.compact("task_1", List.of(step("s1", ImplementationStepType.EDIT, ImplementationStepStatus.FAILED)), List.of());

        assertEquals(StepAuditHealth.FAILED, summary.auditHealth());
        assertEquals(List.of("s1"), summary.failedSteps());
    }

    @Test
    void blockedStepProducesBlockedHealth() {
        StepAuditSummary summary = compactor.compact("task_1", List.of(step("s1", ImplementationStepType.EDIT, ImplementationStepStatus.BLOCKED)), List.of());

        assertEquals(StepAuditHealth.BLOCKED, summary.auditHealth());
        assertEquals(List.of("s1"), summary.unresolvedBlockedSteps());
    }

    @Test
    void approvalRequiredWithoutApprovedNeedsReview() {
        StepAuditSummary summary = compactor.compact("task_1", List.of(step("s1", ImplementationStepType.EDIT, ImplementationStepStatus.APPROVAL_REQUIRED)),
                List.of(record("s1", StepAuditEventType.STEP_APPROVAL_REQUIRED, "APPROVAL_REQUIRED", "", "")));

        assertEquals(StepAuditHealth.NEEDS_REVIEW, summary.auditHealth());
        assertEquals(1, summary.approvalRequiredCount());
    }

    @Test
    void verifierPassWithoutBlockingIsHealthy() {
        StepAuditSummary summary = compactor.compact("task_1", List.of(step("s1", ImplementationStepType.RUN_VERIFIER, ImplementationStepStatus.APPLIED)),
                List.of(record("s1", StepAuditEventType.STEP_VERIFIED, "APPLIED", "", "PASS")));

        assertEquals(StepAuditHealth.HEALTHY, summary.auditHealth());
        assertEquals("PASS", summary.latestVerificationStatus());
    }

    private PendingImplementationStep step(String id, ImplementationStepType type, ImplementationStepStatus status) {
        return new PendingImplementationStep(id, "team_1", "task_1", TeamRole.DEVELOPER, type,
                "README.md", "", "old", "new", "", CommandRiskLevel.LOW, false, Map.of(), status, null, null);
    }

    private StepAuditRecord record(String stepId, StepAuditEventType type, String after, String changeSetId, String verificationStatus) {
        return new StepAuditRecord(null, stepId, "task_1", "team_1", type,
                "", after, type.name(), "", "", "", changeSetId, verificationStatus, "", null, Map.of());
    }
}
