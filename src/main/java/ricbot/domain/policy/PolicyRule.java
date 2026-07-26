package ricbot.domain.policy;

import ricbot.domain.task.TaskRole;

import java.util.List;
import java.util.Map;

public record PolicyRule(
        String id,
        TaskRole role,
        String pattern,
        PolicyDecisionType decisionType,
        List<String> reasons
) {
    public PolicyRule {
        id = clean(id);
        role = role != null ? role : TaskRole.LEADER;
        pattern = clean(pattern).toLowerCase(java.util.Locale.ROOT);
        decisionType = decisionType != null ? decisionType : PolicyDecisionType.DENY;
        reasons = reasons != null ? reasons.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList() : List.of();
    }

    public boolean matches(TaskRole targetRole, String toolName) {
        String tool = toolName != null ? toolName.toLowerCase(java.util.Locale.ROOT) : "";
        return role == targetRole && (pattern.equals("*") || pattern.equals(tool) || tool.contains(pattern));
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "id", id,
                "role", role.name(),
                "pattern", pattern,
                "decisionType", decisionType.name(),
                "reasons", reasons
        );
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
