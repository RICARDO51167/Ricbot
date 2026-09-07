package ricbot.domain.policy;

import java.util.List;
import java.util.Map;

public record PolicyRule(
        String id,
        PolicyRole role,
        String pattern,
        PolicyDecisionType decisionType,
        List<String> reasons
) {
    public PolicyRule {
        id = clean(id);
        role = role != null ? role : PolicyRole.LEADER;
        pattern = clean(pattern).toLowerCase(java.util.Locale.ROOT);
        decisionType = decisionType != null ? decisionType : PolicyDecisionType.DENY;
        reasons = reasons != null ? reasons.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList() : List.of();
    }

    public boolean matches(PolicyRole targetRole, String toolName) {
        String tool = toolName != null ? toolName.toLowerCase(java.util.Locale.ROOT) : "";
        return role == targetRole && matchesPattern(pattern, tool);
    }

    /** Exact name or full-string glob only; substring policy matches are intentionally forbidden. */
    public static boolean matchesPattern(String rawPattern, String rawValue) {
        String pattern = clean(rawPattern).toLowerCase(java.util.Locale.ROOT);
        String value = clean(rawValue).toLowerCase(java.util.Locale.ROOT);
        if (pattern.equals("*")) return true;
        if (!pattern.contains("*") && !pattern.contains("?")) return value.equals(pattern);
        StringBuilder regex = new StringBuilder("^");
        for (char ch : pattern.toCharArray()) {
            if (ch == '*') regex.append(".*");
            else if (ch == '?') regex.append('.');
            else regex.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
        }
        return value.matches(regex.append('$').toString());
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
