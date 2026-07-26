package ricbot.domain.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PersistentApprovalServiceTest {
    @TempDir Path workspace;

    @Test
    void approvalDecisionSurvivesServiceRestart() {
        RiskAssessment risk = RiskAssessment.of(CommandRiskLevel.HIGH, List.of("write"), "write", "tool", List.of("a.txt"));
        ApprovalService first = new ApprovalService(workspace);
        ApprovalRequest request = first.createRequest(risk);
        first.approve(request.requestId());

        ApprovalService restored = new ApprovalService(workspace);
        assertNotNull(restored.find(request.requestId()));
        assertEquals(ApprovalRequest.ApprovalStatus.APPROVED, restored.find(request.requestId()).status());
    }
}
