package ricbot.domain.agent;

/** Stable event names persisted by the durable run journal. */
public enum RunEventType {
    RUN_STARTED,
    RUN_FORKED,
    NODE_STARTED,
    NODE_TRANSITIONED,
    MODEL_REQUESTED,
    MODEL_RESPONSE_RECEIVED,
    MODEL_FAILED,
    TOOL_CALL_STARTED,
    TOOL_CALL_COMPLETED,
    TOOL_CALL_FAILED,
    TOOL_BATCH_COMPLETED,
    TOOL_RETRY_STARTED,
    TOOL_RETRY_COMPLETED,
    TOOL_RETRY_FAILED,
    RUN_PAUSED,
    RUN_FINISHED
}
