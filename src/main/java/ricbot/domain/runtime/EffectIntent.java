package ricbot.domain.runtime;

import java.util.List;
import java.util.Map;

public record EffectIntent(String effectId, String runId, String activationId, String tool,
                           String argumentDigest, String idempotencyKey, List<String> resourceClaims,
                           Map<String, Object> authorizationEvidence, String reconcileStrategy) {
    public EffectIntent {
        effectId = required(effectId, "effectId"); runId = required(runId, "runId");
        activationId = required(activationId, "activationId"); tool = required(tool, "tool");
        argumentDigest = required(argumentDigest, "argumentDigest");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        resourceClaims = resourceClaims != null ? resourceClaims.stream().map(EffectIntent::normalizeResource).sorted().distinct().toList() : List.of();
        authorizationEvidence = Map.copyOf(authorizationEvidence != null ? authorizationEvidence : Map.of());
        reconcileStrategy = clean(reconcileStrategy);
    }
    private static String normalizeResource(String value) { return required(value, "resourceClaim").replace('\\', '/'); }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
    private static String required(String value, String field) {
        String clean = clean(value); if (clean.isBlank()) throw new IllegalArgumentException(field + " is required"); return clean;
    }
}
