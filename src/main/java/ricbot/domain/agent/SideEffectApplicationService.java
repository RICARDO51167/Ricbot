package ricbot.domain.agent;

import ricbot.domain.agent.dto.SideEffectRecord;
import ricbot.domain.agent.eump.SideEffectStatus;
import ricbot.domain.agent.interfacep.SideEffectStore;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.PendingToolCall;
import ricbot.domain.security.RiskAssessment;
import ricbot.domain.security.ApprovalBinding;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Approval-gated control plane for uncertain retries. */
public final class SideEffectApplicationService {
    public static final String RETRY_ACTION = "side_effect_retry";

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
        if (record.status() != SideEffectStatus.UNKNOWN) {
            throw new IllegalStateException("only an UNKNOWN side effect can request retry");
        }
        return boundRequest(RETRY_ACTION, record, Map.of("idempotency_key", record.idempotencyKey()));
    }

    private ApprovalRequest boundRequest(String action, SideEffectRecord record, Map<String, Object> arguments) {
        ApprovalBinding binding = new ApprovalBinding(record.runId(), record.activationId(), action,
                record.idempotencyKey(), record.toolName(), record.argumentsDigest());
        if (!binding.bound()) {
            throw new IllegalStateException("side effect is not bound to a resumable Run/Activation");
        }
        PendingToolCall call = PendingToolCall.create(null, action, arguments, record.sessionKey(),
                risk(action, record));
        return approvals.createRequest(risk(action, record), call, binding);
    }

    private static RiskAssessment risk(String action, SideEffectRecord record) {
        return RiskAssessment.of(CommandRiskLevel.HIGH,
                List.of("Explicit confirmation is required for " + action,
                        "Idempotency key: " + record.idempotencyKey()),
                action, action, List.of());
    }

}
