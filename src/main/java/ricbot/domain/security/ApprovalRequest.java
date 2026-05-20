package ricbot.domain.security;

import ricbot.domain.change.PendingChangeAction;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record ApprovalRequest(
        String requestId,
        RiskAssessment riskAssessment,
        String createdAt,
        ApprovalStatus status,
        PendingToolCall pendingToolCall,
        PendingChangeAction pendingChangeAction,
        boolean consumed
) {
    public enum ApprovalStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    public ApprovalRequest withStatus(ApprovalStatus nextStatus) {
        return new ApprovalRequest(requestId, riskAssessment, createdAt, nextStatus, pendingToolCall, pendingChangeAction, consumed);
    }

    public ApprovalRequest withPendingToolCall(PendingToolCall nextPendingToolCall) {
        return new ApprovalRequest(requestId, riskAssessment, createdAt, status, nextPendingToolCall, pendingChangeAction, consumed);
    }

    public ApprovalRequest withPendingChangeAction(PendingChangeAction nextPendingChangeAction) {
        return new ApprovalRequest(requestId, riskAssessment, createdAt, status, pendingToolCall, nextPendingChangeAction, consumed);
    }

    public ApprovalRequest markConsumed() {
        PendingToolCall consumedCall = pendingToolCall != null ? pendingToolCall.markConsumed() : null;
        PendingChangeAction consumedAction = pendingChangeAction != null ? pendingChangeAction.markConsumed() : null;
        return new ApprovalRequest(requestId, riskAssessment, createdAt, status, consumedCall, consumedAction, true);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("riskAssessment", riskAssessment != null ? riskAssessment.toMap() : Map.of());
        out.put("createdAt", createdAt);
        out.put("status", status.name());
        out.put("pendingToolCall", pendingToolCall != null ? pendingToolCall.toMap() : null);
        out.put("pendingChangeAction", pendingChangeAction != null ? pendingChangeAction.toMap() : null);
        out.put("consumed", consumed);
        return out;
    }

    public static ApprovalRequest create(String requestId, RiskAssessment riskAssessment) {
        return new ApprovalRequest(requestId, riskAssessment, Instant.now().toString(), ApprovalStatus.PENDING, null, null, false);
    }
}
