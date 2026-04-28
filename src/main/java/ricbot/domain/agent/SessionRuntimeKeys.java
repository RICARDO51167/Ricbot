package ricbot.domain.agent;

public final class SessionRuntimeKeys {
    public static final String RUNTIME_CHECKPOINT_KEY = "runtime_checkpoint";
    public static final String PENDING_USER_TURN_KEY = "pending_user_turn";
    public static final String TASK_STATE_KEY = "task_state";
    public static final String TOOL_TRACE_KEY = "tool_trace";
    public static final String RUN_TRACE_KEY = "run_trace";
    public static final String CONTEXT_TRACE_KEY = "context_trace";

    private SessionRuntimeKeys() {
    }
}
