package ricbot.domain.security;

/** Durable ownership of an approval by one runtime activation and action.
 * @author rcd*/
public record ApprovalBinding(
        String runId,
        String activationId,
        String actionType,
        String idempotencyKey,
        String targetId,
        String actionDigest
) {
    public ApprovalBinding {
        runId = clean(runId);
        activationId = clean(activationId);
        actionType = clean(actionType);
        idempotencyKey = clean(idempotencyKey);
        targetId = clean(targetId);
        actionDigest = clean(actionDigest);
    }

    public boolean bound() {
        return !runId.isBlank() && !activationId.isBlank() && !actionType.isBlank() && !idempotencyKey.isBlank();
    }

    public static ApprovalBinding unbound() { return new ApprovalBinding("", "", "", "", "", ""); }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
