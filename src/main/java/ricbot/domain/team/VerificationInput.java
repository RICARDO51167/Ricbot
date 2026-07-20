package ricbot.domain.team;

import java.util.List;

public record VerificationInput(
        String taskId,
        String taskGoal,
        String workerSummary,
        List<String> diffReviews,
        String taskSummary,
        List<String> approvalRecords,
        List<String> suggestedTests,
        List<String> executedTests,
        List<String> verifiedExperience,
        String teamWhiteboardSummary,
        VerificationEvidence evidence
) {
    public VerificationInput {
        taskId = clean(taskId);
        taskGoal = clean(taskGoal);
        workerSummary = clean(workerSummary);
        diffReviews = copy(diffReviews);
        taskSummary = clean(taskSummary);
        approvalRecords = copy(approvalRecords);
        suggestedTests = copy(suggestedTests);
        executedTests = copy(executedTests);
        verifiedExperience = copy(verifiedExperience);
        teamWhiteboardSummary = clean(teamWhiteboardSummary);
    }

    public VerificationInput(
            String taskId,
            String taskGoal,
            String workerSummary,
            List<String> diffReviews,
            String taskSummary,
            List<String> approvalRecords,
            List<String> suggestedTests,
            List<String> executedTests,
            List<String> verifiedExperience,
            String teamWhiteboardSummary
    ) {
        this(taskId, taskGoal, workerSummary, diffReviews, taskSummary, approvalRecords, suggestedTests, executedTests,
                verifiedExperience, teamWhiteboardSummary, null);
    }

    public static VerificationInput ofTask(TeamTask task) {
        return new VerificationInput(
                task != null ? task.id() : "",
                task != null ? task.goal() : "",
                task != null ? task.summary() : "",
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                null
        );
    }

    private static List<String> copy(List<String> values) {
        return values != null ? values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList() : List.of();
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
