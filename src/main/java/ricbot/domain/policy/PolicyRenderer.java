package ricbot.domain.policy;

import ricbot.domain.task.TaskRole;

public class PolicyRenderer {
    public String renderPolicy(RoleToolPolicy policy) {
        RoleToolPolicy safe = policy != null ? policy : RoleToolPolicy.defaultPolicy();
        StringBuilder sb = new StringBuilder("role tool policy\n");
        sb.append("source: ").append(safe.source()).append("\n");
        for (TaskRole role : TaskRole.values()) {
            sb.append(renderRole(safe, role)).append("\n");
        }
        if (!safe.extraRules().isEmpty()) {
            sb.append("extraRules:\n");
            for (PolicyRule rule : safe.extraRules()) {
                sb.append("- ").append(rule.id()).append(" role=").append(rule.role())
                        .append(" pattern=").append(rule.pattern())
                        .append(" decision=").append(rule.decisionType())
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    public String renderRole(RoleToolPolicy policy, TaskRole role) {
        RoleToolPolicy safe = policy != null ? policy : RoleToolPolicy.defaultPolicy();
        TaskRole safeRole = role != null ? role : TaskRole.LEADER;
        RoleToolPolicy.RolePolicy rp = safe.forRole(safeRole);
        return safeRole + "\n"
                + "  allow: " + renderList(rp.allow()) + "\n"
                + "  approval: " + renderList(rp.approval()) + "\n"
                + "  deny: " + renderList(rp.deny());
    }

    public String renderDecision(PolicyDecision decision) {
        if (decision == null) {
            return "policy decision: none";
        }
        return "policy decision\n"
                + "decision: " + decision.decisionType() + "\n"
                + "role: " + decision.role() + "\n"
                + "toolName: " + decision.toolName() + "\n"
                + "riskLevel: " + decision.riskLevel() + "\n"
                + "requiresApproval: " + decision.requiresApproval() + "\n"
                + "denied: " + decision.denied() + "\n"
                + "matchedRules: " + renderList(decision.matchedRules()) + "\n"
                + "reasons: " + renderList(decision.reasons()) + "\n"
                + "suggestedAction: " + decision.suggestedAction();
    }

    private String renderList(java.util.List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }
}
