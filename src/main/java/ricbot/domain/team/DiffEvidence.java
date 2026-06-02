package ricbot.domain.team;

import ricbot.domain.security.CommandRiskLevel;

public record DiffEvidence(
        String path,
        String changeType,
        CommandRiskLevel riskLevel,
        boolean testFile,
        boolean testDeleted,
        boolean configFile,
        boolean securitySensitive,
        boolean runtimeArtifact
) {
    public DiffEvidence {
        path = clean(path);
        changeType = clean(changeType);
        riskLevel = riskLevel != null ? riskLevel : CommandRiskLevel.LOW;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
