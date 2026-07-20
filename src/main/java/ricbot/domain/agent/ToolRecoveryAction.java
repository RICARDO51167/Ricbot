package ricbot.domain.agent;

/** Decision produced for one pending tool call during run recovery. */
public enum ToolRecoveryAction {
    REUSE_COMPLETED,
    RETRY_READ_ONLY,
    REQUIRE_CONFIRMATION,
    UNTRACKED
}
