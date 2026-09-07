package ricbot.domain.runtime;

import java.time.Instant;

public record Activation(String activationId, String runId, RuntimePhase phase, long superstep,
                         String leaseOwner, Instant leaseExpiresAt, int attempt) {
    public Activation {
        if (activationId == null || activationId.isBlank()) throw new IllegalArgumentException("activationId is required");
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId is required");
        if (phase == null || phase == RuntimePhase.WAIT || phase == RuntimePhase.TERMINAL) {
            throw new IllegalArgumentException("activation phase must be executable");
        }
        leaseOwner = leaseOwner != null ? leaseOwner.trim() : "";
        if (superstep < 0 || attempt < 1) throw new IllegalArgumentException("invalid activation counters");
    }
}
