package ricbot.domain.agent;

public enum SideEffectStatus {
    RESERVED,
    EXECUTING,
    AWAITING_APPROVAL,
    RETRY_AUTHORIZED,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    COMPENSATED
}
