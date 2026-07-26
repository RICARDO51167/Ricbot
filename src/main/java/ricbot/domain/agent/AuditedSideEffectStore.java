package ricbot.domain.agent;

import ricbot.domain.trace.TraceEvent;
import ricbot.domain.trace.TraceEventType;
import ricbot.domain.trace.TraceStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Emits immutable trace evidence for every idempotency-ledger transition. */
public final class AuditedSideEffectStore implements SideEffectStore {
    private final SideEffectStore delegate;
    private final TraceStore traces;

    public AuditedSideEffectStore(SideEffectStore delegate, TraceStore traces) {
        this.delegate = delegate != null ? delegate : SideEffectStore.disabled();
        this.traces = traces;
    }

    @Override
    public Optional<SideEffectRecord> load(String idempotencyKey) {
        return delegate.load(idempotencyKey);
    }

    @Override
    public SideEffectClaim claim(SideEffectRecord reservation) {
        SideEffectClaim claim = delegate.claim(reservation);
        trace(claim.record(), claim.created() ? TraceEventType.SIDE_EFFECT_RESERVED
                : TraceEventType.SIDE_EFFECT_REUSED, claim.created() ? "side effect reserved" : "side effect reused");
        return claim;
    }

    @Override
    public SideEffectRecord transition(SideEffectRecord record, long expectedVersion,
                                       Set<SideEffectStatus> allowedSources) {
        SideEffectRecord saved = delegate.transition(record, expectedVersion, allowedSources);
        traceTransition(saved);
        return saved;
    }

    private void traceTransition(SideEffectRecord saved) {
        TraceEventType type = switch (saved.status()) {
            case RESERVED -> TraceEventType.SIDE_EFFECT_RESERVED;
            case EXECUTING -> TraceEventType.SIDE_EFFECT_RESERVED;
            case AWAITING_APPROVAL -> TraceEventType.SIDE_EFFECT_RESERVED;
            case RETRY_AUTHORIZED -> TraceEventType.SIDE_EFFECT_RETRY_AUTHORIZED;
            case SUCCEEDED -> TraceEventType.SIDE_EFFECT_SUCCEEDED;
            case FAILED -> TraceEventType.SIDE_EFFECT_FAILED;
            case UNKNOWN -> TraceEventType.SIDE_EFFECT_FAILED;
            case COMPENSATED -> TraceEventType.SIDE_EFFECT_COMPENSATED;
        };
        trace(saved, type, "side effect " + saved.status().name().toLowerCase(java.util.Locale.ROOT));
    }

    private void trace(SideEffectRecord record, TraceEventType type, String message) {
        if (traces == null || record == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idempotencyKey", record.idempotencyKey());
        payload.put("toolName", record.toolName());
        payload.put("argumentsDigest", record.argumentsDigest());
        payload.put("status", record.status().name());
        payload.put("confirmationId", record.confirmationId());
        traces.append(new TraceEvent(
                traces.traceIdForSession(record.sessionKey()), null, "", record.sessionKey(), "", "",
                record.confirmationId(), type, "side-effect", message, payload, null, null));
    }
}
