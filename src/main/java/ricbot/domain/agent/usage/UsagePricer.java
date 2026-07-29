package ricbot.domain.agent.usage;

import ricbot.domain.config.ModelCard;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Converts declarative USD/token prices to integer micro-USD, always rounding upward. */
public final class UsagePricer {
    private static final BigDecimal MICRO_USD = BigDecimal.valueOf(1_000_000L);

    private UsagePricer() { }

    public static UsageDelta price(UsageDelta usage, ModelCard.Pricing pricing) {
        if (usage == null) return null;
        if (pricing == null || !pricing.known()) return copy(usage, 0, false);
        BigDecimal unit = BigDecimal.valueOf(pricing.unitTokens());
        BigDecimal input = BigDecimal.valueOf(usage.inputTokens()).multiply(pricing.inputUsd())
                .multiply(MICRO_USD).divide(unit, 12, RoundingMode.UP);
        BigDecimal output = BigDecimal.valueOf(usage.outputTokens()).multiply(pricing.outputUsd())
                .multiply(MICRO_USD).divide(unit, 12, RoundingMode.UP);
        long cost;
        try { cost = input.add(output).setScale(0, RoundingMode.UP).longValueExact(); }
        catch (ArithmeticException overflow) { cost = Long.MAX_VALUE; }
        return copy(usage, cost, true);
    }

    private static UsageDelta copy(UsageDelta usage, long cost, boolean known) {
        return new UsageDelta(usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                usage.modelCalls(), usage.compressionCalls(), usage.repairCalls(), usage.toolCalls(),
                usage.activeMillis(), cost, usage.model(), known);
    }
}
