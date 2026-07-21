package ricbot.domain.agent;

public final class SessionRuntimeKeys {
    public static final String RUNTIME_CHECKPOINT_KEY = "runtime_checkpoint";
    public static final String LAST_RESTORED_CHECKPOINT_ID_KEY = "last_restored_checkpoint_id";
    public static final String RECOVERY_DECISIONS_KEY = "recovery_decisions";
    public static final String PENDING_USER_TURN_KEY = "pending_user_turn";
    public static final String TASK_STATE_KEY = "task_state";
    public static final String TOOL_TRACE_KEY = "tool_trace";
    public static final String TEAM_SESSION_ID_KEY = "team_session_id";
    public static final String TEAM_CONTEXT_KEY = "team_context";
    public static final String DEVELOPER_TASK_ID_KEY = "developer_task_id";
    public static final String CHANGESET_ID_KEY = "changeset_id";
    public static final String CHANGESET_SUMMARY_KEY = "changeset_summary";
    public static final String CHANGESET_STATUS_KEY = "changeset_status";
    public static final String CHANGESET_COMMIT_HASH_KEY = "changeset_commit_hash";
    public static final String CHANGESET_ROLLBACK_STATUS_KEY = "changeset_rollback_status";
    public static final String ACTIVE_WORKSPACE_SESSION_ID_KEY = "active_workspace_session_id";
    public static final String WORKSPACE_SUMMARY_KEY = "workspace_summary";
    public static final String WORKSPACE_SOURCE_KEY = "workspace_source";
    public static final String TRACE_ID_KEY = "trace_id";
    public static final String TRACE_SUMMARY_KEY = "trace_summary";
    public static final String RUN_TRACE_KEY = "run_trace";
    public static final String CONTEXT_TRACE_KEY = "context_trace";

    private SessionRuntimeKeys() {
    }
}
