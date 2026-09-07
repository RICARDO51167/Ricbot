package ricbot.domain.runtime;

/** Durable aggregate used for diagnostics; admission decisions remain inside the Store transaction. */
public record BudgetUsage(long tokens, long costMicrousd, long toolCalls, long activeMillis) { }
