package ricbot.domain.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable outcome of recovering one pending tool call. */
public record ToolRecoveryResolution(
        String toolCallId,
        ToolRecoveryAction action,
        Map<String, Object> resultMessage,
        String reason
) {
    public ToolRecoveryResolution {
        toolCallId = toolCallId != null ? toolCallId.trim() : "";
        action = action != null ? action : ToolRecoveryAction.UNTRACKED;
        resultMessage = resultMessage != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(resultMessage))
                : Map.of();
        reason = reason != null ? reason.trim() : "";
    }

    public boolean hasResult() {
        return !resultMessage.isEmpty();
    }
}
