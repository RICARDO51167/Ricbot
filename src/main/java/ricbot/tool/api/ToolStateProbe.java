package ricbot.tool.api;

/** Read-only observation of an uncertain external operation. */
public record ToolStateProbe(Outcome outcome, Object observedResult, String evidence) {
    public enum Outcome { EXECUTED, NOT_EXECUTED, INCONCLUSIVE }
    public ToolStateProbe {
        outcome = outcome != null ? outcome : Outcome.INCONCLUSIVE;
        evidence = evidence != null ? evidence : "";
    }
    public static ToolStateProbe inconclusive(String evidence) {
        return new ToolStateProbe(Outcome.INCONCLUSIVE, null, evidence);
    }
}
