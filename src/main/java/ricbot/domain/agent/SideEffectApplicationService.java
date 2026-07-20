package ricbot.domain.agent;

import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.PendingToolCall;
import ricbot.domain.security.RiskAssessment;
import ricbot.tool.api.ToolRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Approval-gated control plane for uncertain retries and compensating actions. */
public final class SideEffectApplicationService {
    public static final String RETRY_ACTION = "side_effect_retry";
    public static final String COMPENSATE_ACTION = "side_effect_compensate";

    private final SideEffectCoordinator coordinator;
    private final ApprovalService approvals;

    public SideEffectApplicationService(SideEffectStore store, ApprovalService approvals) {
        this.coordinator = new SideEffectCoordinator(store);
        this.approvals = java.util.Objects.requireNonNull(approvals, "approvals");
    }

    public SideEffectRecord status(String idempotencyKey) {
        return coordinator.load(idempotencyKey).orElseThrow(() ->
                new IllegalArgumentException("side effect does not exist"));
    }

    public ApprovalRequest requestRetry(String idempotencyKey) {
        SideEffectRecord record = status(idempotencyKey);
        if (record.status() != SideEffectStatus.RESERVED) {
            throw new IllegalStateException("only an uncertain reserved side effect can request retry");
        }
        return approvals.createRequest(risk(RETRY_ACTION, record), RETRY_ACTION,
                Map.of("idempotency_key", record.idempotencyKey()), record.sessionKey());
    }

    public SideEffectRecord applyApprovedRetry(String approvalId) {
        PendingToolCall call = requireApprovedAction(approvalId, RETRY_ACTION);
        String key = requiredArgument(call, "idempotency_key");
        approvals.consumeApprovedToolCall(approvalId);
        return coordinator.authorizeRetry(key, approvalId);
    }

    public ApprovalRequest requestCompensation(String idempotencyKey, Map<String, Object> originalArguments) {
        SideEffectRecord record = status(idempotencyKey);
        if (record.status() != SideEffectStatus.SUCCEEDED) {
            throw new IllegalStateException("only a successful side effect can request compensation");
        }
        if (!record.argumentsDigest().equals(ToolInvocationRecord.argumentsDigest(originalArguments))) {
            throw new IllegalArgumentException("compensation arguments do not match the original effect");
        }
        return approvals.createRequest(risk(COMPENSATE_ACTION, record), COMPENSATE_ACTION, Map.of(
                "idempotency_key", record.idempotencyKey(),
                "original_arguments", originalArguments != null ? originalArguments : Map.of()
        ), record.sessionKey());
    }

    public SideEffectRecord applyApprovedCompensation(ToolRegistry tools, String approvalId) {
        PendingToolCall call = requireApprovedAction(approvalId, COMPENSATE_ACTION);
        String key = requiredArgument(call, "idempotency_key");
        Map<String, Object> arguments = objectMap(call.arguments().get("original_arguments"));
        approvals.consumeApprovedToolCall(approvalId);
        return coordinator.compensate(tools, key, arguments, approvalId);
    }

    private PendingToolCall requireApprovedAction(String approvalId, String expectedTool) {
        ApprovalRequest request = approvals.find(approvalId);
        if (request == null) throw new IllegalArgumentException("approval does not exist");
        if (request.isExpired(Instant.now())) throw new IllegalStateException("approval has expired");
        if (request.status() != ApprovalRequest.ApprovalStatus.APPROVED) {
            throw new IllegalStateException("approval is not approved");
        }
        if (request.consumed()) throw new IllegalStateException("approval was already consumed");
        PendingToolCall call = request.pendingToolCall();
        if (call == null || !expectedTool.equals(call.toolName())) {
            throw new IllegalArgumentException("approval is not for " + expectedTool);
        }
        return call;
    }

    private static RiskAssessment risk(String action, SideEffectRecord record) {
        return RiskAssessment.of(CommandRiskLevel.HIGH,
                List.of("Explicit confirmation is required for " + action,
                        "Idempotency key: " + record.idempotencyKey()),
                action, action, List.of());
    }

    private static String requiredArgument(PendingToolCall call, String name) {
        Object value = call.arguments().get(name);
        String clean = value != null ? String.valueOf(value).trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("approval is missing " + name);
        return clean;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
