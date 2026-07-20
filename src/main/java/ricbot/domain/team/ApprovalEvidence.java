package ricbot.domain.team;

import ricbot.domain.security.CommandRiskLevel;

public record ApprovalEvidence(
        String requestId,
        CommandRiskLevel riskLevel,
        String status
) {
    public static final String APPROVED = "APPROVED";
    public static final String PENDING = "PENDING";
    public static final String REJECTED = "REJECTED";
    public static final String NOT_REQUIRED = "NOT_REQUIRED";

    public ApprovalEvidence {
        requestId = clean(requestId);
        riskLevel = riskLevel != null ? riskLevel : CommandRiskLevel.LOW;
        status = clean(status).toUpperCase(java.util.Locale.ROOT);
        if (status.isBlank()) {
            status = NOT_REQUIRED;
        }
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
