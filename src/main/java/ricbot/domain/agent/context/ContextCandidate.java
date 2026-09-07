package ricbot.domain.agent.context;

/** A selectable input layer with explicit cost, priority and recovery semantics. */
public record ContextCandidate(
        String id, String source, String content, int priority, long tokenCost,
        ContextReference reference, boolean selected, String reason
) {
    public ContextCandidate {
        id = clean(id); source = clean(source); content = content != null ? content : "";
        tokenCost = Math.max(0, tokenCost); reason = clean(reason);
    }
    public boolean recoverable() { return reference != null && !reference.uri().isBlank(); }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
