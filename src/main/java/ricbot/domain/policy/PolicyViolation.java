package ricbot.domain.policy;

import ricbot.domain.task.TaskRole;

public record PolicyViolation(TaskRole role, String toolName, String reason, PolicyDecision decision) {
}
