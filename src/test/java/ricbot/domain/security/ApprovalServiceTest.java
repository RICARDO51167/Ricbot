package ricbot.domain.security;

import org.junit.jupiter.api.Test;
import ricbot.domain.change.PendingChangeAction;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalServiceTest {

    @Test
    void createApproveRejectAndFind() {
        ApprovalService service = new ApprovalService();
        RiskAssessment assessment = RiskAssessment.of(
                CommandRiskLevel.HIGH,
                List.of("dangerous command"),
                "rm file",
                "exec",
                List.of("file")
        );

        ApprovalRequest request = service.createRequest(assessment);
        assertNotNull(request.requestId());
        assertEquals(ApprovalRequest.ApprovalStatus.PENDING, request.status());
        assertEquals(request.requestId(), service.find(request.requestId()).requestId());

        ApprovalRequest approved = service.approve(request.requestId());
        assertEquals(ApprovalRequest.ApprovalStatus.APPROVED, approved.status());

        ApprovalRequest rejected = service.reject(request.requestId());
        assertEquals(ApprovalRequest.ApprovalStatus.REJECTED, rejected.status());
        assertNull(service.find("missing"));
    }

    @Test
    void approvedPendingToolCallCanBeConsumedOnlyOnce() {
        ApprovalService service = new ApprovalService();
        ApprovalRequest request = service.createRequest(
                RiskAssessment.of(CommandRiskLevel.MEDIUM, List.of("file modification tool"), "", "write_file", List.of("a.txt")),
                "write_file",
                Map.of("path", "a.txt", "content", "hello\n"),
                "session-1"
        );

        service.approve(request.requestId());
        PendingToolCall call = service.consumeApprovedToolCall(request.requestId());

        assertEquals("write_file", call.toolName());
        assertFalse(call.consumed());
        assertEquals("hello\n", call.arguments().get("content"));
        assertTrue(service.find(request.requestId()).consumed());
        assertTrue(service.find(request.requestId()).pendingToolCall().consumed());
        assertThrows(IllegalStateException.class, () -> service.consumeApprovedToolCall(request.requestId()));
    }

    @Test
    void createRequestCanBindPendingToolCallAndSanitizesArguments() {
        ApprovalService service = new ApprovalService();
        RiskAssessment assessment = RiskAssessment.of(
                CommandRiskLevel.MEDIUM,
                List.of("file modification tool"),
                "",
                "write_file",
                List.of("a.txt")
        );
        PendingToolCall pending = PendingToolCall.create(
                "placeholder",
                "write_file",
                Map.of("path", PathLike.of("a.txt")),
                "session-1",
                assessment
        );

        ApprovalRequest request = service.createRequest(assessment, pending);

        assertEquals(request.requestId(), request.pendingToolCall().requestId());
        assertEquals("a.txt", request.pendingToolCall().arguments().get("path"));
        assertFalse(request.pendingToolCall().consumed());
    }

    @Test
    void rejectedOrMissingRequestCannotBeConsumed() {
        ApprovalService service = new ApprovalService();
        ApprovalRequest request = service.createRequest(
                RiskAssessment.of(CommandRiskLevel.HIGH, List.of("danger"), "rm file", "exec", List.of("file")),
                "exec",
                Map.of("command", "rm file"),
                "session-1"
        );

        service.reject(request.requestId());

        assertThrows(IllegalStateException.class, () -> service.consumeApprovedToolCall(request.requestId()));
        assertThrows(IllegalArgumentException.class, () -> service.consumeApprovedToolCall("missing"));
    }

    @Test
    void pendingChangeActionCanBeApprovedConsumedRejectedAndDeduped() {
        ApprovalService service = new ApprovalService();
        RiskAssessment assessment = RiskAssessment.of(
                CommandRiskLevel.HIGH,
                List.of("git commit requires approval"),
                "git commit",
                "change_commit",
                List.of("README.md")
        );
        ApprovalRequest request = service.createChangeActionRequest(
                assessment,
                PendingChangeAction.create(
                        null,
                        PendingChangeAction.ActionType.COMMIT,
                        "changeset_1",
                        List.of("git add -- README.md", "git commit -m Update"),
                        "Update README",
                        assessment
                )
        );

        service.approve(request.requestId());
        PendingChangeAction action = service.consumeApprovedChangeAction(request.requestId());

        assertEquals(PendingChangeAction.ActionType.COMMIT, action.actionType());
        assertEquals("changeset_1", action.changeSetId());
        assertEquals("Update README", action.commitMessage());
        assertTrue(service.find(request.requestId()).consumed());
        assertTrue(service.find(request.requestId()).pendingChangeAction().consumed());
        assertThrows(IllegalStateException.class, () -> service.consumeApprovedChangeAction(request.requestId()));

        ApprovalRequest rejected = service.createChangeActionRequest(
                assessment,
                PendingChangeAction.create(null, PendingChangeAction.ActionType.ROLLBACK, "changeset_2", List.of("git restore -- README.md"), "", assessment)
        );
        service.reject(rejected.requestId());

        assertThrows(IllegalStateException.class, () -> service.consumeApprovedChangeAction(rejected.requestId()));
        assertThrows(IllegalArgumentException.class, () -> service.consumeApprovedChangeAction("missing"));
    }

    private record PathLike(String value) {
        static PathLike of(String value) {
            return new PathLike(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
