package ricbot.domain.team;

import java.util.ArrayList;
import java.util.List;

public record StepGateResult(
        boolean allowed,
        boolean blocked,
        List<String> reasons,
        List<String> requiredActions,
        String nextSuggestedCommand,
        List<String> blockedBy
) {
    public StepGateResult {
        reasons = copy(reasons);
        requiredActions = copy(requiredActions);
        nextSuggestedCommand = nextSuggestedCommand != null ? nextSuggestedCommand.trim() : "";
        blockedBy = copy(blockedBy);
    }

    public static StepGateResult allow() {
        return new StepGateResult(true, false, List.of(), List.of(), "", List.of());
    }

    public static StepGateResult blocked(List<String> reasons, List<String> requiredActions, String nextSuggestedCommand, List<String> blockedBy) {
        return new StepGateResult(false, true, reasons, requiredActions, nextSuggestedCommand, blockedBy);
    }

    private static List<String> copy(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values != null ? values : List.<String>of()) {
            String cleaned = value != null ? value.trim() : "";
            if (!cleaned.isBlank() && !out.contains(cleaned)) {
                out.add(cleaned);
            }
        }
        return List.copyOf(out);
    }
}
