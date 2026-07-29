package ricbot.domain.agent.eump;

public enum SideEffectStatus {
    RESERVED,
    EXECUTING,
    AWAITING_APPROVAL,
    RETRY_AUTHORIZED,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
