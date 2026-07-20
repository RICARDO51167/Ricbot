package ricbot.tool.filesystem;

import ricbot.domain.security.CommandRiskLevel;

import java.util.List;

public record DiffReview(
        List<String> changedFiles,
        int addedLines,
        int deletedLines,
        CommandRiskLevel riskLevel,
        String summary,
        List<String> suspiciousChanges,
        List<String> suggestedTests,
        String rollbackHint,
        List<String> affectedAreas,
        boolean hasSecuritySensitiveChanges,
        boolean hasConfigChanges,
        boolean hasTestDeletion
) {
    public DiffReview {
        changedFiles = changedFiles != null ? List.copyOf(changedFiles) : List.of();
        riskLevel = riskLevel != null ? riskLevel : CommandRiskLevel.MEDIUM;
        summary = summary != null ? summary : "";
        suspiciousChanges = suspiciousChanges != null ? List.copyOf(suspiciousChanges) : List.of();
        suggestedTests = suggestedTests != null ? List.copyOf(suggestedTests) : List.of();
        rollbackHint = rollbackHint != null ? rollbackHint : "";
        affectedAreas = affectedAreas != null ? List.copyOf(affectedAreas) : List.of();
    }

    static String render(String path, String before, String after, CommandRiskLevel riskLevel) {
        DiffReviewService service = new DiffReviewService();
        return service.renderMarkdown(service.review(path, before, after, riskLevel));
    }
}
