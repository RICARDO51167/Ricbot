package ricbot.domain.agent.context;

/** Recoverable pointer used when full candidate content cannot stay in the model input. */
public record ContextReference(String uri, String summary, String recoveryTool, String ownerRunId) {
    public ContextReference {
        uri = clean(uri); summary = clean(summary); recoveryTool = clean(recoveryTool); ownerRunId = clean(ownerRunId);
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
