package ricbot.domain.agent.graph.dto;

import java.time.Duration;
import java.util.Objects;

public record GraphRetryPolicy(int maxAttempts, Duration backoff, boolean sideEffectSafe) {
    public GraphRetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        backoff = Objects.requireNonNullElse(backoff, Duration.ZERO);
        if (backoff.isNegative()) throw new IllegalArgumentException("backoff must not be negative");
    }

    public static GraphRetryPolicy none() { return new GraphRetryPolicy(1, Duration.ZERO, true); }
    public static GraphRetryPolicy readOnly(int maxAttempts, Duration backoff) {
        return new GraphRetryPolicy(maxAttempts, backoff, true);
    }
    public static GraphRetryPolicy sideEffecting() { return new GraphRetryPolicy(1, Duration.ZERO, false); }

    public Duration backoffForAttempt(int completedAttempt) {
        if (completedAttempt < 1 || backoff.isZero()) return Duration.ZERO;
        long multiplier = completedAttempt == 1 ? 1L : 4L;
        try { return backoff.multipliedBy(multiplier); }
        catch (ArithmeticException ignored) { return Duration.ofDays(1); }
    }
}
