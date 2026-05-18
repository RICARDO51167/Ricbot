package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import ricbot.domain.security.CommandRiskLevel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationServiceTest {

    @Test
    void highRiskDiffNeedsHuman() {
        VerificationResult result = new VerificationService().verify(input(
                "worker produced summary",
                List.of("src/main/java/ricbot/domain/security/ApprovalService.java [risk=HIGH]"),
                List.of("./mvnw -q -Dtest='ricbot.domain.security.*Test' test"),
                List.of("./mvnw -q -Dtest='ricbot.domain.security.*Test' test")
        ));

        assertEquals(VerificationResult.Status.NEEDS_HUMAN, result.status());
        assertEquals(CommandRiskLevel.HIGH, result.riskLevel());
        assertTrue(result.humanApprovalRequired());
        assertFalse(result.suspiciousChanges().isEmpty());
    }

    @Test
    void suggestedTestsNotExecutedRejects() {
        VerificationResult result = new VerificationService().verify(input(
                "worker produced summary",
                List.of("DiffReview risk=MEDIUM suspiciousChanges: filesystem write"),
                List.of("./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test"),
                List.of()
        ));

        assertEquals(VerificationResult.Status.REJECT, result.status());
        assertTrue(result.missingTests().toString().contains("ricbot.tool.filesystem.*Test"), result.missingTests().toString());
        assertTrue(result.requiredActions().toString().contains("Run missing suggested tests"), result.requiredActions().toString());
    }

    @Test
    void suggestedTestsExecutedAndNoRiskPasses() {
        VerificationResult result = new VerificationService().verify(input(
                "worker produced summary",
                List.of("DiffReview risk=LOW changedFiles: docs/demo.md"),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test"),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test")
        ));

        assertEquals(VerificationResult.Status.PASS, result.status());
        assertTrue(result.missingTests().isEmpty());
        assertEquals(CommandRiskLevel.LOW, result.riskLevel());
    }

    @Test
    void emptyWorkerResultRejects() {
        VerificationResult result = new VerificationService().verify(input(
                "",
                List.of("DiffReview risk=LOW changedFiles: docs/demo.md"),
                List.of(),
                List.of()
        ));

        assertEquals(VerificationResult.Status.REJECT, result.status());
        assertTrue(result.reasons().contains("worker result is empty"), result.reasons().toString());
    }

    private VerificationInput input(
            String workerSummary,
            List<String> diffReviews,
            List<String> suggestedTests,
            List<String> executedTests
    ) {
        return new VerificationInput(
                "teamtask_test",
                "Verify task",
                workerSummary,
                diffReviews,
                "TaskSummary contains verification context",
                List.of(),
                suggestedTests,
                executedTests,
                List.of(),
                "whiteboard summary"
        );
    }
}
