package ricbot.domain.policy;

import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.team.TeamRole;

import java.util.List;
import java.util.Map;

public record PolicyDecision(
        PolicyDecisionType decisionType,
        TeamRole role,
        String toolName,
        List<String> reasons,
        CommandRiskLevel riskLevel,
        boolean requiresApproval,
        boolean denied,
        List<String> matchedRules,
        String suggestedAction
) {
    public PolicyDecision {
        decisionType = decisionType != null ? decisionType : PolicyDecisionType.DENY;
        role = role != null ? role : TeamRole.LEADER;
        toolName = toolName != null ? toolName.trim() : "";
        reasons = reasons != null ? reasons.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList() : List.of();
        riskLevel = riskLevel != null ? riskLevel : CommandRiskLevel.SAFE;
        requiresApproval = decisionType == PolicyDecisionType.REQUIRE_APPROVAL || requiresApproval;
        denied = decisionType == PolicyDecisionType.DENY || denied;
        matchedRules = matchedRules != null ? matchedRules.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList() : List.of();
        suggestedAction = suggestedAction != null ? suggestedAction.trim() : "";
    }

    public Map<String, Object> toMap() {
        return new java.util.LinkedHashMap<>(Map.of(
                "decisionType", decisionType.name(),
                "role", role.name(),
                "toolName", toolName,
                "reasons", reasons,
                "riskLevel", riskLevel.name(),
                "requiresApproval", requiresApproval,
                "denied", denied,
                "matchedRules", matchedRules,
                "suggestedAction", suggestedAction
        ));
    }
}
