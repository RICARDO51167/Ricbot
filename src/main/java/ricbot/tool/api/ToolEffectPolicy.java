package ricbot.tool.api;

import java.time.Duration;
import java.util.Objects;

/** Complete, fail-closed execution contract for one tool. */
public record ToolEffectPolicy(
        Effect effect,
        Concurrency concurrency,
        Duration timeout,
        Approval approval,
        Retry retry,
        boolean downstreamIdempotencyKey,
        boolean stateProbe,
        boolean compensation
) {
    public enum Effect { UNDECLARED, READ_ONLY, IDEMPOTENT, AT_MOST_ONCE, COMPENSATABLE }
    public enum Concurrency { SHARED, SERIAL_PER_RUN, EXCLUSIVE_WORKSPACE }
    public enum Approval { NEVER, RISK_BASED, ALWAYS }
    public enum Retry { NONE, READ_ONLY_3, IDEMPOTENT_3, HUMAN_AUTHORIZED }

    public ToolEffectPolicy {
        effect = Objects.requireNonNullElse(effect, Effect.UNDECLARED);
        concurrency = Objects.requireNonNullElse(concurrency, Concurrency.EXCLUSIVE_WORKSPACE);
        timeout = Objects.requireNonNullElse(timeout, Duration.ofMinutes(5));
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        approval = Objects.requireNonNullElse(approval, Approval.ALWAYS);
        retry = Objects.requireNonNullElse(retry, Retry.NONE);
        if (effect == Effect.COMPENSATABLE && !compensation) {
            throw new IllegalArgumentException("compensatable effect requires compensation support");
        }
    }

    public boolean declared() { return effect != Effect.UNDECLARED; }
    public boolean readOnly() { return effect == Effect.READ_ONLY; }
    public boolean concurrentSafe() { return readOnly() && concurrency == Concurrency.SHARED; }

    public static ToolEffectPolicy undeclared() {
        return new ToolEffectPolicy(Effect.UNDECLARED, Concurrency.EXCLUSIVE_WORKSPACE,
                Duration.ofMinutes(5), Approval.ALWAYS, Retry.NONE, false, false, false);
    }
    public static ToolEffectPolicy readOnly(Duration timeout) {
        return new ToolEffectPolicy(Effect.READ_ONLY, Concurrency.SHARED, timeout, Approval.NEVER,
                Retry.READ_ONLY_3, false, false, false);
    }
    public static ToolEffectPolicy idempotent(Duration timeout, Approval approval) {
        return new ToolEffectPolicy(Effect.IDEMPOTENT, Concurrency.SERIAL_PER_RUN, timeout, approval,
                Retry.IDEMPOTENT_3, true, true, false);
    }
    public static ToolEffectPolicy atMostOnce(Duration timeout, Concurrency concurrency, Approval approval) {
        return new ToolEffectPolicy(Effect.AT_MOST_ONCE, concurrency, timeout, approval,
                Retry.HUMAN_AUTHORIZED, false, false, false);
    }
    public static ToolEffectPolicy compensatable(Duration timeout, Approval approval) {
        return new ToolEffectPolicy(Effect.COMPENSATABLE, Concurrency.SERIAL_PER_RUN, timeout, approval,
                Retry.HUMAN_AUTHORIZED, true, true, true);
    }
}
