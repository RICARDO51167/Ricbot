package ricbot.domain.agent.context;

import java.util.Map;

/** Token accounting for the exact request shape sent to a provider. */
public record TokenEstimate(
        Mode mode,
        long inputTokens,
        long outputReserveTokens,
        long totalTokens,
        long contextWindowTokens,
        Map<String, Long> partitions
) {
    public enum Mode { EXACT, ESTIMATED }

    public TokenEstimate {
        mode = mode != null ? mode : Mode.ESTIMATED;
        inputTokens = Math.max(0, inputTokens);
        outputReserveTokens = Math.max(0, outputReserveTokens);
        totalTokens = Math.max(inputTokens, totalTokens);
        contextWindowTokens = Math.max(1, contextWindowTokens);
        partitions = Map.copyOf(partitions != null ? partitions : Map.of());
    }

    public double utilization() {
        return Math.min(1d, totalTokens / (double) contextWindowTokens);
    }

    public boolean fits() { return totalTokens <= contextWindowTokens; }
}
