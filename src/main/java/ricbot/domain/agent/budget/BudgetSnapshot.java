package ricbot.domain.agent.budget;

import ricbot.domain.agent.usage.UsageLedger;

public record BudgetSnapshot(BudgetPolicy policy, UsageLedger usage, boolean exhausted,
                             String reason, long remainingTokens, long remainingCostMicrousd,
                             long remainingActiveMillis, long remainingToolCalls,
                             boolean finalizing) {
    public static BudgetSnapshot evaluate(BudgetPolicy policy, UsageLedger usage, boolean finalizing) {
        BudgetPolicy p = policy != null ? policy : BudgetPolicy.unlimited();
        UsageLedger u = usage != null ? usage : UsageLedger.empty();
        long tokens = remaining(p.maxTotalTokens(), u.totalTokens());
        long cost = remaining(p.maxCostMicrousd(), u.costMicrousd());
        long active = remaining(p.maxActiveSeconds() == null ? null : p.maxActiveSeconds() * 1000L,
                u.activeMillis());
        long tools = remaining(p.maxToolCalls(), u.toolCalls());
        String reason = tokens <= p.finalizationTokens() && p.maxTotalTokens() != null ? "token_budget"
                : cost == 0 && p.maxCostMicrousd() != null ? "cost_budget"
                : active == 0 && p.maxActiveSeconds() != null ? "active_time_budget"
                : tools == 0 && p.maxToolCalls() != null ? "tool_call_budget" : "";
        return new BudgetSnapshot(p, u, !reason.isBlank(), reason, tokens, cost, active, tools, finalizing);
    }
    private static long remaining(Long limit, long used) {
        return limit == null ? Long.MAX_VALUE : Math.max(0, limit - used);
    }
}
