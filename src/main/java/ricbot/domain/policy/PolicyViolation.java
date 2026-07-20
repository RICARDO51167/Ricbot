package ricbot.domain.policy;

import ricbot.domain.team.TeamRole;

public record PolicyViolation(TeamRole role, String toolName, String reason, PolicyDecision decision) {
}
