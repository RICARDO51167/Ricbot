package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import ricbot.domain.security.CommandRiskLevel;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationServiceTest {

    @Test
    void passingTestEvidenceCoversSuggestedTestPasses() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "worker produced summary",
                List.of(),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test"),
                evidence(
                        List.of(passingTest("./mvnw -q -Dtest='ricbot.domain.team.*Test' test")),
                        List.of(diff("src/main/java/ricbot/domain/team/VerificationService.java", CommandRiskLevel.LOW)),
                        List.of()
                )
        ));

        assertEquals(VerificationResult.Status.PASS, result.status());
        assertTrue(result.reasons().contains("structured evidence passed"), result.reasons().toString());
    }

    @Test
    void failedTestEvidenceRejects() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "worker produced summary",
                List.of(),
                List.of(),
                evidence(List.of(failedTest("./mvnw -q test", 1)), List.of(), List.of())
        ));

        assertEquals(VerificationResult.Status.REJECT, result.status());
        assertTrue(result.reason().contains("failed structured test evidence"), result.reason());
    }

    @Test
    void textSaysPassedButStructuredExitCodeFailedRejects() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "all tests passed",
                List.of(),
                List.of(),
                evidence(List.of(failedTest("./mvnw -q test", 2)), List.of(), List.of())
        ));

        assertEquals(VerificationResult.Status.REJECT, result.status());
        assertTrue(result.reasons().toString().contains("failed structured test evidence"), result.reasons().toString());
    }

    @Test
    void highRiskDiffEvidenceNeedsHuman() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "all tests passed",
                List.of(),
                List.of(),
                evidence(List.of(passingTest("./mvnw -q test")), List.of(diff("src/main/java/ricbot/domain/policy/PolicyEngine.java", CommandRiskLevel.HIGH)), List.of())
        ));

        assertEquals(VerificationResult.Status.NEEDS_HUMAN, result.status());
        assertTrue(result.reason().contains("high risk diff evidence"), result.reason());
    }

    @Test
    void securitySensitiveDiffEvidenceNeedsHuman() {
        DiffEvidence sensitive = new DiffEvidence(
                "src/main/java/ricbot/domain/security/ApprovalService.java",
                "EDIT",
                CommandRiskLevel.MEDIUM,
                false,
                false,
                false,
                true,
                false
        );

        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "all tests passed",
                List.of(),
                List.of(),
                evidence(List.of(passingTest("./mvnw -q test")), List.of(sensitive), List.of())
        ));

        assertEquals(VerificationResult.Status.NEEDS_HUMAN, result.status());
        assertTrue(result.reason().contains("security sensitive diff"), result.reason());
    }

    @Test
    void testDeletionEvidenceNeedsHuman() {
        DiffEvidence deletedTest = new DiffEvidence(
                "src/test/java/ricbot/domain/team/VerificationServiceTest.java",
                "DELETE",
                CommandRiskLevel.LOW,
                true,
                true,
                false,
                false,
                false
        );

        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "all tests passed",
                List.of(),
                List.of(),
                evidence(List.of(passingTest("./mvnw -q test")), List.of(deletedTest), List.of())
        ));

        assertEquals(VerificationResult.Status.NEEDS_HUMAN, result.status());
        assertTrue(result.reason().contains("test deletion detected"), result.reason());
    }

    @Test
    void pendingApprovalEvidenceNeedsHuman() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "all tests passed",
                List.of(),
                List.of(),
                evidence(List.of(passingTest("./mvnw -q test")), List.of(), List.of(approval("req-1", CommandRiskLevel.HIGH, "PENDING")))
        ));

        assertEquals(VerificationResult.Status.NEEDS_HUMAN, result.status());
        assertTrue(result.reason().contains("pending approval"), result.reason());
    }

    @Test
    void rejectedApprovalEvidenceRejects() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "all tests passed",
                List.of(),
                List.of(),
                evidence(List.of(passingTest("./mvnw -q test")), List.of(), List.of(approval("req-1", CommandRiskLevel.MEDIUM, "REJECTED")))
        ));

        assertEquals(VerificationResult.Status.REJECT, result.status());
        assertTrue(result.reason().contains("rejected approval"), result.reason());
    }

    @Test
    void suggestedTestWithoutExecutedEvidenceNeedsHuman() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "all tests passed",
                List.of(),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test"),
                evidence(List.of(), List.of(diff("src/main/java/ricbot/domain/team/VerificationService.java", CommandRiskLevel.LOW)), List.of())
        ));

        assertEquals(VerificationResult.Status.NEEDS_HUMAN, result.status());
        assertTrue(result.reason().contains("missing passing evidence for suggested test"), result.reason());
    }

    @Test
    void onlyRuntimeArtifactsChangedDoesNotPass() {
        DiffEvidence runtimeArtifact = new DiffEvidence(
                "target/surefire-reports/TEST-ricbot.domain.team.VerificationServiceTest.xml",
                "CREATE",
                CommandRiskLevel.LOW,
                false,
                false,
                false,
                false,
                true
        );

        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "all tests passed",
                List.of(),
                List.of(),
                evidence(List.of(passingTest("./mvnw -q test")), List.of(runtimeArtifact), List.of())
        ));

        assertEquals(VerificationResult.Status.NEEDS_HUMAN, result.status());
        assertTrue(result.reason().contains("only runtime artifacts changed"), result.reason());
    }

    @Test
    void healthyStructuredEvidencePassesEvenWhenSummaryDoesNotSayPassed() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "verification output omitted from summary",
                List.of(),
                List.of(),
                evidence(
                        List.of(passingTest("./mvnw -q test")),
                        List.of(diff("src/main/java/ricbot/domain/team/VerificationService.java", CommandRiskLevel.LOW)),
                        List.of(approval("req-1", CommandRiskLevel.HIGH, "APPROVED"))
                )
        ));

        assertEquals(VerificationResult.Status.PASS, result.status());
        assertTrue(result.reason().contains("structured evidence passed"), result.reason());
    }

    @Test
    void healthyStructuredEvidencePassesButRecordsTextEvidenceConflict() {
        VerificationResult result = new VerificationService().verify(inputWithEvidence(
                "verification failed according to text summary",
                List.of(),
                List.of(),
                evidence(
                        List.of(passingTest("./mvnw -q test")),
                        List.of(diff("src/main/java/ricbot/domain/team/VerificationService.java", CommandRiskLevel.LOW)),
                        List.of()
                )
        ));

        assertEquals(VerificationResult.Status.PASS, result.status());
        assertTrue(result.reasons().contains("text/evidence conflict"), result.reasons().toString());
    }

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

    @Test
    void noEvidenceFallsBackToExistingTextRules() {
        VerificationResult result = new VerificationService().verify(input(
                "worker produced summary",
                List.of("DiffReview risk=LOW changedFiles: docs/demo.md"),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test"),
                List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test")
        ));

        assertEquals(VerificationResult.Status.PASS, result.status());
        assertTrue(result.reasons().contains("suggested tests are covered and no high-risk blocker is present"), result.reasons().toString());
        assertTrue(result.reasons().contains("fallback to text rules"), result.reasons().toString());
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
                "whiteboard summary"
        );
    }

    private VerificationInput inputWithEvidence(
            String workerSummary,
            List<String> diffReviews,
            List<String> suggestedTests,
            VerificationEvidence evidence
    ) {
        return new VerificationInput(
                "teamtask_test",
                "Verify task",
                workerSummary,
                diffReviews,
                "TaskSummary contains verification context",
                List.of(),
                suggestedTests,
                List.of(),
                "whiteboard summary",
                evidence
        );
    }

    private VerificationEvidence evidence(
            List<ExecutedTestEvidence> tests,
            List<DiffEvidence> diffs,
            List<ApprovalEvidence> approvals
    ) {
        return new VerificationEvidence(tests, diffs, approvals);
    }

    private ExecutedTestEvidence passingTest(String command) {
        return new ExecutedTestEvidence(command, 0, true, "passed", 100L, Instant.parse("2026-06-02T00:00:00Z"));
    }

    private ExecutedTestEvidence failedTest(String command, int exitCode) {
        return new ExecutedTestEvidence(command, exitCode, false, "failed", 100L, Instant.parse("2026-06-02T00:00:00Z"));
    }

    private DiffEvidence diff(String path, CommandRiskLevel riskLevel) {
        return new DiffEvidence(path, "EDIT", riskLevel, false, false, false, false, false);
    }

    private ApprovalEvidence approval(String requestId, CommandRiskLevel riskLevel, String status) {
        return new ApprovalEvidence(requestId, riskLevel, status);
    }
}
