package ricbot.domain.security;

import ricbot.domain.change.PendingChangeAction;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record ApprovalRequest(
        String requestId,
        RiskAssessment riskAssessment,
        String createdAt,
        String expiresAt,
        ApprovalStatus status,
        PendingToolCall pendingToolCall,
        PendingChangeAction pendingChangeAction,
        boolean consumed,
        ApprovalBinding binding
) {
    public enum ApprovalStatus {
        PENDING,
        APPROVED,
        CLAIMED,
        CONSUMED,
        REJECTED
    }

    public ApprovalRequest {
        binding = binding != null ? binding : ApprovalBinding.unbound();
    }

    public ApprovalRequest withStatus(ApprovalStatus nextStatus) {
        return new ApprovalRequest(requestId, riskAssessment, createdAt, expiresAt, nextStatus, pendingToolCall, pendingChangeAction, consumed, binding);
    }

    public ApprovalRequest withPendingToolCall(PendingToolCall nextPendingToolCall) {
        return new ApprovalRequest(requestId, riskAssessment, createdAt, expiresAt, status, nextPendingToolCall, pendingChangeAction, consumed, binding);
    }

    public ApprovalRequest withPendingChangeAction(PendingChangeAction nextPendingChangeAction) {
        return new ApprovalRequest(requestId, riskAssessment, createdAt, expiresAt, status, pendingToolCall, nextPendingChangeAction, consumed, binding);
    }

    public ApprovalRequest withBinding(ApprovalBinding nextBinding) {
        return new ApprovalRequest(requestId, riskAssessment, createdAt, expiresAt, status, pendingToolCall,
                pendingChangeAction, consumed, nextBinding);
    }

    public ApprovalRequest markConsumed() {
        PendingToolCall consumedCall = pendingToolCall != null ? pendingToolCall.markConsumed() : null;
        PendingChangeAction consumedAction = pendingChangeAction != null ? pendingChangeAction.markConsumed() : null;
        return new ApprovalRequest(requestId, riskAssessment, createdAt, expiresAt, ApprovalStatus.CONSUMED,
                consumedCall, consumedAction, true, binding);
    }

    public boolean isExpired(Instant now) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return false;
        }
        Instant deadline = Instant.parse(expiresAt);
        Instant current = now != null ? now : Instant.now();
        return !current.isBefore(deadline);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("riskAssessment", riskAssessment != null ? riskAssessment.toMap() : Map.of());
        out.put("createdAt", createdAt);
        out.put("expiresAt", expiresAt);
        out.put("status", status.name());
        out.put("pendingToolCall", pendingToolCall != null ? pendingToolCall.toMap() : null);
        out.put("pendingChangeAction", pendingChangeAction != null ? pendingChangeAction.toMap() : null);
        out.put("consumed", consumed);
        out.put("binding", binding);
        return out;
    }

    public static ApprovalRequest create(String requestId, RiskAssessment riskAssessment) {
        Instant now = Instant.now();
        return create(requestId, riskAssessment, now, now.plus(java.time.Duration.ofMinutes(30)));
    }

    public static ApprovalRequest create(String requestId, RiskAssessment riskAssessment, Instant createdAt, Instant expiresAt) {
        Instant created = createdAt != null ? createdAt : Instant.now();
        Instant expires = expiresAt != null ? expiresAt : created.plus(java.time.Duration.ofMinutes(30));
        return new ApprovalRequest(requestId, riskAssessment, created.toString(), expires.toString(), ApprovalStatus.PENDING, null, null, false,
                ApprovalBinding.unbound());
    }
}
