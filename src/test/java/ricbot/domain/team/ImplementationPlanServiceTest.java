package ricbot.domain.team;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImplementationPlanServiceTest {

    @Test
    void developerPlanTargetFilesCreateReadSteps() {
        List<PendingImplementationStep> steps = new ImplementationPlanService().createSteps(result(
                List.of("src/App.java", "README.md"),
                List.of(),
                "Update files"
        ));

        assertTrue(steps.stream().anyMatch(step -> step.type() == ImplementationStepType.READ && step.targetPath().equals("src/App.java")), steps.toString());
        assertTrue(steps.stream().anyMatch(step -> step.type() == ImplementationStepType.READ && step.targetPath().equals("README.md")), steps.toString());
    }

    @Test
    void suggestedTestsCreateExecTestSteps() {
        List<PendingImplementationStep> steps = new ImplementationPlanService().createSteps(result(
                List.of("src/App.java"),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test"),
                "Update files"
        ));

        assertTrue(steps.stream().anyMatch(step -> step.type() == ImplementationStepType.EXEC_TEST
                && step.command().contains("ricbot.domain.team.*Test")), steps.toString());
    }

    @Test
    void defaultsCreateChangeSetAndRunVerifierSteps() {
        List<PendingImplementationStep> steps = new ImplementationPlanService().createSteps(result(List.of("README.md"), List.of(), "Update files"));

        assertTrue(steps.stream().anyMatch(step -> step.type() == ImplementationStepType.CREATE_CHANGESET), steps.toString());
        assertTrue(steps.stream().anyMatch(step -> step.type() == ImplementationStepType.RUN_VERIFIER), steps.toString());
    }

    @Test
    void missingOldNewCreatesDraftEditOnly() {
        List<PendingImplementationStep> steps = new ImplementationPlanService().createSteps(result(List.of("README.md"), List.of(), "Update files"));

        PendingImplementationStep edit = steps.stream().filter(step -> step.type() == ImplementationStepType.EDIT).findFirst().orElseThrow();
        assertEquals(ImplementationStepStatus.DRAFT, edit.status());
        assertTrue(edit.oldText().isBlank());
        assertTrue(edit.newText().isBlank());
    }

    @Test
    void replaceGoalCreatesReadyEditStep() {
        List<PendingImplementationStep> steps = new ImplementationPlanService().createSteps(result(List.of("README.md"), List.of(), "Replace old with new"));

        PendingImplementationStep edit = steps.stream().filter(step -> step.type() == ImplementationStepType.EDIT).findFirst().orElseThrow();
        assertEquals(ImplementationStepStatus.READY, edit.status());
        assertEquals("old", edit.oldText());
        assertEquals("new", edit.newText());
        assertFalse(edit.policyDecision().containsKey("decisionType"));
    }

    @Test
    void createsOrderAndDependencies() {
        List<PendingImplementationStep> steps = new ImplementationPlanService().createSteps(result(
                List.of("README.md"),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test"),
                "Replace old with new"
        ));

        PendingImplementationStep read = steps.stream().filter(step -> step.type() == ImplementationStepType.READ).findFirst().orElseThrow();
        PendingImplementationStep edit = steps.stream().filter(step -> step.type() == ImplementationStepType.EDIT).findFirst().orElseThrow();
        PendingImplementationStep test = steps.stream().filter(step -> step.type() == ImplementationStepType.EXEC_TEST).findFirst().orElseThrow();
        PendingImplementationStep changeSet = steps.stream().filter(step -> step.type() == ImplementationStepType.CREATE_CHANGESET).findFirst().orElseThrow();
        PendingImplementationStep verifier = steps.stream().filter(step -> step.type() == ImplementationStepType.RUN_VERIFIER).findFirst().orElseThrow();

        assertTrue(read.orderIndex() < edit.orderIndex());
        assertTrue(edit.orderIndex() < test.orderIndex());
        assertTrue(test.orderIndex() < changeSet.orderIndex());
        assertTrue(changeSet.orderIndex() < verifier.orderIndex());
        assertTrue(edit.dependsOnStepIds().contains(read.id()), edit.toString());
        assertTrue(test.dependsOnStepIds().contains(edit.id()), test.toString());
        assertTrue(changeSet.dependsOnStepIds().contains(edit.id()), changeSet.toString());
        assertTrue(changeSet.dependsOnStepIds().contains(test.id()), changeSet.toString());
        assertTrue(verifier.dependsOnStepIds().contains(changeSet.id()), verifier.toString());
        assertTrue(read.unblocksStepIds().contains(edit.id()), read.toString());
    }

    private WorkerExecutionResult result(List<String> files, List<String> tests, String goal) {
        return new WorkerExecutionResult(
                "task_dev",
                "team_dev",
                TeamRole.DEVELOPER,
                goal,
                "/tmp/workspace",
                "",
                files,
                List.of(),
                "Developer Plan created",
                List.of(),
                List.of(),
                tests,
                List.of(),
                List.of(),
                List.of("goal=" + goal, "targetFiles=" + String.join(", ", files)),
                List.of("edit_file requires approval"),
                List.of("/change create"),
                "Run /change create",
                0.6d,
                "PLANNED",
                null
        );
    }
}
