package ricbot.integration.llm.api;

/** Stable failure taxonomy consumed by the graph routing and retry policy. */
public enum LLMFailureKind {
    CONTEXT_OVERFLOW,
    TRANSIENT,
    RATE_LIMIT,
    AUTH,
    PERMANENT
}
