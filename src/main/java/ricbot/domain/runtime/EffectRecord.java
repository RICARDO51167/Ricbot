package ricbot.domain.runtime;

import java.time.Instant;
import java.util.Map;

public record EffectRecord(EffectIntent intent, Status status, int attempt, long committedSuperstep,
                           Map<String, Object> executionEvidence, String resultReference,
                           String failure, Instant updatedAt) {
    public enum Status { PREPARED, DISPATCHING, SUCCEEDED, FAILED, UNKNOWN }
    public EffectRecord {
        if (intent == null || status == null || updatedAt == null) throw new IllegalArgumentException("intent, status and updatedAt are required");
        if (attempt < 1 || committedSuperstep < -1) throw new IllegalArgumentException("invalid effect counters");
        executionEvidence = Map.copyOf(executionEvidence != null ? executionEvidence : Map.of());
        resultReference = resultReference != null ? resultReference.trim() : "";
        failure = failure != null ? failure.trim() : "";
    }
}
