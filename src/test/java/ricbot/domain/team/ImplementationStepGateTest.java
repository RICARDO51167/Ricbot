package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import ricbot.domain.security.CommandRiskLevel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImplementationStepGateTest {

    private final ImplementationStepGate gate = new ImplementationStepGate();

    @Test
    void runVerifierWithoutChangeSetIsBlocked() {
        List<PendingImplementationStep> steps = plannedSteps();
        PendingImplementationStep verifier = steps.stream().filter(step -> step.type() == ImplementationStepType.RUN_VERIFIER).findFirst().orElseThrow();

        StepGateResult result = gate.canApply(verifier, steps, ImplementationStepGate.GateContext.empty());

        assertTrue(result.blocked(), result.toString());
        assertTrue(result.reasons().toString().contains("CREATE_CHANGESET") || result.reasons().toString().contains("ChangeSet"), result.toString());
    }

    @Test
    void editBeforeReadIsBlocked() {
        List<PendingImplementationStep> steps = plannedSteps();
        PendingImplementationStep edit = steps.stream().filter(step -> step.type() == ImplementationStepType.EDIT).findFirst().orElseThrow();

        StepGateResult result = gate.canApply(edit, steps, ImplementationStepGate.GateContext.empty());

        assertTrue(result.blocked(), result.toString());
        assertTrue(result.requiredActions().stream().anyMatch(action -> action.startsWith("/team apply-step")), result.toString());
    }

    @Test
    void createChangeSetWithoutWorkOrDiffIsBlocked() {
        PendingImplementationStep step = step(ImplementationStepType.CREATE_CHANGESET, "", ImplementationStepStatus.READY);

        StepGateResult result = gate.canApply(step, List.of(step), ImplementationStepGate.GateContext.empty());

        assertTrue(result.blocked(), result.toString());
        assertTrue(result.reasons().toString().contains("workspace diff"), result.toString());
    }

    @Test
    void rejectedDependencyBlocksFollower() {
        List<PendingImplementationStep> steps = plannedSteps();
        PendingImplementationStep read = steps.stream().filter(step -> step.type() == ImplementationStepType.READ).findFirst().orElseThrow()
                .withStatus(ImplementationStepStatus.REJECTED);
        PendingImplementationStep edit = steps.stream().filter(step -> step.type() == ImplementationStepType.EDIT).findFirst().orElseThrow();
        List<PendingImplementationStep> updated = steps.stream().map(step -> step.id().equals(read.id()) ? read : step).toList();

        StepGateResult result = gate.canApply(edit, updated, ImplementationStepGate.GateContext.empty());

        assertTrue(result.blocked(), result.toString());
        assertTrue(result.reasons().toString().contains("REJECTED"), result.toString());
    }

    @Test
    void readStepIsAllowed() {
        PendingImplementationStep read = step(ImplementationStepType.READ, "README.md", ImplementationStepStatus.READY);

        StepGateResult result = gate.canApply(read, List.of(read), ImplementationStepGate.GateContext.empty());

        assertFalse(result.blocked(), result.toString());
        assertTrue(result.allowed(), result.toString());
    }

    @Test
    void validatesRequiredFieldsByStepType() {
        assertTrue(gate.validateFields(step(ImplementationStepType.READ, "", ImplementationStepStatus.READY)).contains("READ requires targetPath"));
        assertTrue(gate.validateFields(step(ImplementationStepType.EDIT, "", ImplementationStepStatus.READY)).toString().contains("oldText"));
        assertTrue(gate.validateFields(step(ImplementationStepType.WRITE, "README.md", ImplementationStepStatus.READY)).contains("WRITE requires newText"));
        assertTrue(gate.validateFields(step(ImplementationStepType.EXEC_TEST, "", ImplementationStepStatus.READY)).contains("EXEC_TEST requires command"));
        assertTrue(gate.validateFields(step(ImplementationStepType.CREATE_CHANGESET, "", ImplementationStepStatus.READY)).isEmpty());
        assertTrue(gate.validateFields(step(ImplementationStepType.RUN_VERIFIER, "", ImplementationStepStatus.READY)).isEmpty());
    }

    private List<PendingImplementationStep> plannedSteps() {
        WorkerExecutionResult result = new WorkerExecutionResult(
                "task_dev",
                "team_dev",
                TeamRole.DEVELOPER,
                "Replace old with new",
                "/tmp/workspace",
                "",
                List.of("README.md"),
                List.of(),
                List.of(),
                "Developer Plan created",
                List.of(),
                List.of(),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test"),
                List.of(),
                List.of(),
                List.of("goal=Replace old with new", "targetFiles=README.md"),
                List.of("edit_file requires approval"),
                List.of("/change create"),
                "Run /change create",
                0.6d,
                "PLANNED",
                null
        );
        return new ImplementationPlanService().createSteps(result);
    }

    private PendingImplementationStep step(ImplementationStepType type, String targetPath, ImplementationStepStatus status) {
        return new PendingImplementationStep(null, "team_dev", "task_dev", TeamRole.DEVELOPER, type,
                targetPath, "", "", "", "", CommandRiskLevel.LOW, false, Map.of(), status, null, null);
    }
}
