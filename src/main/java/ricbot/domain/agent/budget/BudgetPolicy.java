package ricbot.domain.agent.budget;

/** Null limits mean accounting-only. Active time deliberately excludes paused/waiting time. */
public record BudgetPolicy(Long maxTotalTokens, Long maxCostMicrousd, Long maxActiveSeconds,
                           Long maxToolCalls, long finalizationTokens, String parentRunId) {
    public BudgetPolicy {
        positive(maxTotalTokens, "maxTotalTokens");
        positive(maxCostMicrousd, "maxCostMicrousd");
        positive(maxActiveSeconds, "maxActiveSeconds");
        positive(maxToolCalls, "maxToolCalls");
        if (finalizationTokens < 0) throw new IllegalArgumentException("finalizationTokens must not be negative");
        if (maxTotalTokens != null && maxTotalTokens <= finalizationTokens) {
            throw new IllegalArgumentException("maxTotalTokens must exceed finalizationTokens");
        }
        parentRunId = parentRunId != null ? parentRunId.trim() : "";
    }

    public static BudgetPolicy unlimited() { return new BudgetPolicy(null, null, null, null, 1024, ""); }
    public boolean limited() {
        return maxTotalTokens != null || maxCostMicrousd != null || maxActiveSeconds != null || maxToolCalls != null;
    }
    private static void positive(Long value, String name) {
        if (value != null && value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
