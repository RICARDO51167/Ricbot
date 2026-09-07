package ricbot.tool.api;

import ricbot.domain.security.RiskAssessment;

public record ToolRiskEvidence(Decision decision, RiskAssessment assessment, String reason, boolean irreversible) {
    public enum Decision { ALLOW, DENY, REQUIRE_APPROVAL }
    public ToolRiskEvidence { decision = decision != null ? decision : Decision.ALLOW; reason = reason != null ? reason : ""; }
    public static ToolRiskEvidence allow() { return new ToolRiskEvidence(Decision.ALLOW, null, "", false); }
    public static ToolRiskEvidence from(ToolRiskDecision old) {
        if (old == null) return allow();
        return new ToolRiskEvidence(Decision.valueOf(old.decision().name()), old.assessment(), old.reason(),
                old.assessment() != null && old.assessment().blocked());
    }
}
