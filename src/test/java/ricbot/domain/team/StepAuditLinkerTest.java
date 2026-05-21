package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import ricbot.domain.security.CommandRiskLevel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepAuditLinkerTest {

    private final StepAuditLinker linker = new StepAuditLinker();

    @Test
    void changeSetLinksToLatestAppliedEditOrWriteStep() {
        PendingImplementationStep edit = step("edit_1", ImplementationStepType.EDIT, ImplementationStepStatus.APPLIED);
        StepAuditRecord changeSet = record("", StepAuditEventType.STEP_CHANGESET_LINKED, "changeset_1", "");

        StepAuditRecord linked = linker.link(List.of(edit), List.of(changeSet)).get(0);

        assertEquals("edit_1", linked.stepId());
        assertEquals("MEDIUM", linked.metadata().get("linkConfidence"));
    }

    @Test
    void verifierLinksToRunVerifierStep() {
        PendingImplementationStep verifier = step("verify_1", ImplementationStepType.RUN_VERIFIER, ImplementationStepStatus.APPLIED);
        StepAuditRecord verified = record("", StepAuditEventType.STEP_VERIFIED, "", "PASS");

        StepAuditRecord linked = linker.link(List.of(verifier), List.of(verified)).get(0);

        assertEquals("verify_1", linked.stepId());
        assertEquals("MEDIUM", linked.metadata().get("linkConfidence"));
    }

    @Test
    void existingStepIdIsNotOverwritten() {
        PendingImplementationStep edit = step("edit_1", ImplementationStepType.EDIT, ImplementationStepStatus.APPLIED);
        StepAuditRecord existing = record("manual_step", StepAuditEventType.STEP_CHANGESET_LINKED, "changeset_1", "");

        StepAuditRecord linked = linker.link(List.of(edit), List.of(existing)).get(0);

        assertEquals("manual_step", linked.stepId());
    }

    private PendingImplementationStep step(String id, ImplementationStepType type, ImplementationStepStatus status) {
        return new PendingImplementationStep(id, "team_1", "task_1", TeamRole.DEVELOPER, type,
                "README.md", "", "old", "new", "", CommandRiskLevel.LOW, false, Map.of(), status, null, null);
    }

    private StepAuditRecord record(String stepId, StepAuditEventType type, String changeSetId, String verificationStatus) {
        return new StepAuditRecord(null, stepId, "task_1", "team_1", type,
                "", "", type.name(), "", "", "", changeSetId, verificationStatus, "", null, Map.of());
    }
}
