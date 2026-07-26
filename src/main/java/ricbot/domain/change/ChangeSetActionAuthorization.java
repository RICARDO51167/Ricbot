package ricbot.domain.change;

import ricbot.domain.security.ApprovalRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Opaque domain authorization captured from a claimed, matching approval request. */
public final class ChangeSetActionAuthorization {
    private final ApprovalRequest request;

    private ChangeSetActionAuthorization(ApprovalRequest request) { this.request = request; }

    public static ChangeSetActionAuthorization claimed(ApprovalRequest request) {
        if (request == null || request.status() != ApprovalRequest.ApprovalStatus.CLAIMED || request.consumed()) {
            throw new IllegalStateException("change action requires a claimed approval");
        }
        if (request.pendingChangeAction() == null) throw new IllegalStateException("approval has no change action");
        if (request.binding() == null || !request.binding().bound()) {
            throw new IllegalStateException("change action approval is not bound to a graph activation");
        }
        return new ChangeSetActionAuthorization(request);
    }

    public String requestId() { return request.requestId(); }
    public PendingChangeAction action() { return request.pendingChangeAction(); }

    public void require(PendingChangeAction.ActionType type, String changeSetId, String message) {
        PendingChangeAction action = action();
        if (action.actionType() != type || !action.changeSetId().equals(changeSetId)) {
            throw new IllegalStateException("approval is bound to a different change action");
        }
        if (type == PendingChangeAction.ActionType.COMMIT
                && !action.commitMessage().equals(message != null ? message.trim() : "")) {
            throw new IllegalStateException("approved commit message does not match requested action");
        }
        String expectedType = "CHANGE_" + type.name();
        if (!expectedType.equals(request.binding().actionType())
                || !changeSetId.equals(request.binding().targetId())) {
            throw new IllegalStateException("approval binding does not match the requested change action");
        }
        String expectedDigest = digest(type.name() + "\n" + changeSetId + "\n"
                + (message != null ? message.trim() : ""));
        if (!expectedDigest.equals(request.binding().actionDigest())) {
            throw new IllegalStateException("approval action digest does not match the requested change action");
        }
    }

    public static String digest(PendingChangeAction.ActionType type, String changeSetId, String message) {
        return digest(type.name() + "\n" + changeSetId + "\n" + (message != null ? message.trim() : ""));
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
}
