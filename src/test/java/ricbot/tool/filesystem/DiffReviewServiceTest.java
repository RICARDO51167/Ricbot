package ricbot.tool.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.security.CommandRiskLevel;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffReviewServiceTest {

    @Test
    void reviewsOrdinaryJavaFileModification(@TempDir Path workspace) {
        DiffReviewService service = new DiffReviewService(workspace);

        DiffReview review = service.reviewEditFile(
                workspace.resolve("src/main/java/ricbot/tool/filesystem/Foo.java").toString(),
                "class Foo {\n    void oldName() {}\n}\n",
                "class Foo {\n    void newName() {}\n}\n",
                CommandRiskLevel.MEDIUM
        );

        assertEquals("src/main/java/ricbot/tool/filesystem/Foo.java", review.changedFiles().get(0));
        assertEquals(1, review.addedLines());
        assertEquals(1, review.deletedLines());
        assertEquals(CommandRiskLevel.MEDIUM, review.riskLevel());
        assertTrue(review.affectedAreas().contains("tool.filesystem"));
        assertFalse(review.hasSecuritySensitiveChanges());
    }

    @Test
    void flagsSecurityPackageModification(@TempDir Path workspace) {
        DiffReviewService service = new DiffReviewService(workspace);

        DiffReview review = service.reviewEditFile(
                workspace.resolve("src/main/java/ricbot/domain/security/ApprovalService.java").toString(),
                "if (requiresApproval) {\n    checkApproval(requestId);\n}\n",
                "return requestId;\n",
                CommandRiskLevel.MEDIUM
        );

        assertTrue(review.hasSecuritySensitiveChanges());
        assertEquals(CommandRiskLevel.HIGH, review.riskLevel());
        assertTrue(review.suspiciousChanges().contains("security-sensitive code changed"));
        assertTrue(review.suggestedTests().contains("./mvnw -q -Dtest='ricbot.domain.security.*Test' test"));
    }

    @Test
    void flagsConfigFileModification() {
        DiffReviewService service = new DiffReviewService();

        DiffReview review = service.reviewWriteFile(
                "pom.xml",
                "<project>\n</project>\n",
                "<project>\n  <build />\n</project>\n",
                true,
                CommandRiskLevel.MEDIUM
        );

        assertTrue(review.hasConfigChanges());
        assertEquals(CommandRiskLevel.HIGH, review.riskLevel());
        assertTrue(review.suspiciousChanges().contains("build or runtime configuration changed"));
        assertTrue(review.suggestedTests().contains("./mvnw -q test"));
    }

    @Test
    void flagsTestDeletionAndAssertionReduction() {
        DiffReviewService service = new DiffReviewService();

        DiffReview reducedAssertions = service.reviewEditFile(
                "src/test/java/ricbot/tool/filesystem/FileToolSupportTest.java",
                "assertTrue(result);\nassertEquals(\"ok\", value);\n",
                "assertTrue(result);\n",
                CommandRiskLevel.MEDIUM
        );
        DiffReview deletedTest = service.reviewEditFile(
                "src/test/java/ricbot/tool/filesystem/FileToolSupportTest.java",
                "assertTrue(result);\n",
                "",
                CommandRiskLevel.MEDIUM
        );

        assertTrue(reducedAssertions.hasTestDeletion());
        assertTrue(reducedAssertions.suspiciousChanges().contains("test assertions reduced"));
        assertTrue(deletedTest.suspiciousChanges().contains("test file content removed"));
        assertTrue(deletedTest.hasTestDeletion());
        assertTrue(reducedAssertions.suggestedTests()
                .contains("./mvnw -q -Dtest='ricbot.tool.filesystem.FileToolSupportTest' test"));
    }

    @Test
    void newFileRollbackHintUsesRemove(@TempDir Path workspace) {
        DiffReviewService service = new DiffReviewService(workspace);

        DiffReview review = service.reviewWriteFile(
                workspace.resolve("reports/new-file.txt").toString(),
                "",
                "hello\n",
                false,
                CommandRiskLevel.MEDIUM
        );

        assertEquals("rm reports/new-file.txt", review.rollbackHint());
        assertTrue(review.summary().startsWith("Created reports/new-file.txt"));
    }

    @Test
    void recommendsTestsByPath() {
        DiffReviewService service = new DiffReviewService();

        DiffReview agent = service.reviewEditFile(
                "src/main/java/ricbot/domain/agent/AgentLoop.java",
                "old\n",
                "new\n",
                CommandRiskLevel.MEDIUM
        );
        DiffReview process = service.reviewEditFile(
                "src/main/java/ricbot/tool/process/ExecTool.java",
                "old\n",
                "new\n",
                CommandRiskLevel.MEDIUM
        );
        DiffReview config = service.reviewEditFile(
                "config/ricbot.config.json",
                "{}\n",
                "{\"approvalEnabled\":true}\n",
                CommandRiskLevel.MEDIUM
        );

        assertTrue(agent.suggestedTests().contains("./mvnw -q -Dtest='ricbot.domain.agent.*Test' test"));
        assertTrue(process.suggestedTests().contains("./mvnw -q -Dtest='ricbot.tool.process.*Test' test"));
        assertTrue(config.suggestedTests().contains("./mvnw -q test"));
    }
}
