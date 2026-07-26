package ricbot.tool.api;

import ricbot.domain.security.RiskAssessment;

/** Dynamic risk evidence evaluated under a tool's declared effect policy. */
public record ToolRiskDecision(Decision decision, RiskAssessment assessment, String reason) {
    public enum Decision { ALLOW, DENY, REQUIRE_APPROVAL }
    public ToolRiskDecision {
        decision = decision != null ? decision : Decision.ALLOW;
        reason = reason != null ? reason.trim() : "";
    }
    public static ToolRiskDecision allow() { return new ToolRiskDecision(Decision.ALLOW, null, ""); }
    public static ToolRiskDecision from(RiskAssessment assessment) {
        if (assessment == null) return allow();
        if (assessment.blocked()) return new ToolRiskDecision(Decision.DENY, assessment, "blocked by risk policy");
        if (assessment.requiresApproval())
            return new ToolRiskDecision(Decision.REQUIRE_APPROVAL, assessment, "approval required by risk policy");
        return new ToolRiskDecision(Decision.ALLOW, assessment, "");
    }
}
