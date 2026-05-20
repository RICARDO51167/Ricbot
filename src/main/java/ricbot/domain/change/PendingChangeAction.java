package ricbot.domain.change;

import ricbot.domain.security.RiskAssessment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PendingChangeAction(
        String requestId,
        ActionType actionType,
        String changeSetId,
        List<String> commands,
        String commitMessage,
        RiskAssessment riskAssessment,
        String createdAt,
        boolean consumed
) {
    public enum ActionType {
        COMMIT,
        ROLLBACK
    }

    public PendingChangeAction {
        requestId = clean(requestId);
        actionType = actionType != null ? actionType : ActionType.COMMIT;
        changeSetId = clean(changeSetId);
        commands = commands != null ? commands.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList() : List.of();
        commitMessage = clean(commitMessage);
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : Instant.now().toString();
    }

    public static PendingChangeAction create(
            String requestId,
            ActionType actionType,
            String changeSetId,
            List<String> commands,
            String commitMessage,
            RiskAssessment riskAssessment
    ) {
        return new PendingChangeAction(requestId, actionType, changeSetId, commands, commitMessage, riskAssessment, null, false);
    }

    public PendingChangeAction withRequestId(String nextRequestId) {
        return new PendingChangeAction(nextRequestId, actionType, changeSetId, commands, commitMessage, riskAssessment, createdAt, consumed);
    }

    public PendingChangeAction markConsumed() {
        return new PendingChangeAction(requestId, actionType, changeSetId, commands, commitMessage, riskAssessment, createdAt, true);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("actionType", actionType.name());
        out.put("changeSetId", changeSetId);
        out.put("commands", commands);
        out.put("commitMessage", commitMessage);
        out.put("riskAssessment", riskAssessment != null ? riskAssessment.toMap() : Map.of());
        out.put("createdAt", createdAt);
        out.put("consumed", consumed);
        return out;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
