package ricbot.domain.runtime;

import java.time.Instant;
import java.util.Map;

public record ModelInvocation(String invocationId, String runId, String activationId, int attempt,
                              Status status, String requestDigest, long reservedTokens,
                              UnknownPolicy unknownPolicy,
                              String providerRequestId, String responseReference, String responseDigest,
                              Map<String, Object> response, boolean possibleDuplicateCharge,
                              boolean retryableFailure, long retryAfterMillis,
                              String failure, Instant updatedAt) {
    public enum Status { PREPARED, DISPATCHING, OBSERVED, FAILED, UNKNOWN }
    public enum UnknownPolicy { RECONCILE_THEN_RETRY, RECONCILE_THEN_WAIT }
    public ModelInvocation {
        if (invocationId == null || invocationId.isBlank()) throw new IllegalArgumentException("invocationId is required");
        if (attempt < 1 || reservedTokens < 0 || retryAfterMillis < 0) {
            throw new IllegalArgumentException("invalid model invocation counters");
        }
        unknownPolicy = unknownPolicy != null ? unknownPolicy : UnknownPolicy.RECONCILE_THEN_WAIT;
        providerRequestId = clean(providerRequestId); responseReference = clean(responseReference);
        responseDigest = clean(responseDigest); failure = clean(failure);
        response = Map.copyOf(response != null ? response : Map.of());
        if (status == null || updatedAt == null) throw new IllegalArgumentException("status and updatedAt are required");
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
