package ricbot.domain.team;

import ricbot.domain.security.CommandRiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TeamDecisionPolicy {

    public Decision evaluate(
            String taskGoal,
            CommandRiskLevel riskLevel,
            List<String> changedFiles,
            int estimatedSteps,
            boolean requiresResearch,
            boolean requiresVerification
    ) {
        List<String> reasons = new ArrayList<>();
        List<String> files = changedFiles != null ? changedFiles : List.of();
        String goal = taskGoal != null ? taskGoal.toLowerCase(Locale.ROOT) : "";

        if (files.size() > 1) {
            reasons.add("multiple changed files");
        }
        if (riskLevel == CommandRiskLevel.HIGH || riskLevel == CommandRiskLevel.BLOCKED) {
            reasons.add("high risk level");
        }
        if (securitySensitive(goal, files)) {
            reasons.add("security sensitive scope");
        }
        if (estimatedSteps >= 3) {
            reasons.add("multi-step task");
        }
        if (requiresResearch) {
            reasons.add("requires research");
        }
        if (requiresVerification) {
            reasons.add("requires verifier");
        }
        if (needsTests(goal, files)) {
            reasons.add("requires tests");
        }

        boolean useTeam = !reasons.isEmpty();
        if (!useTeam) {
            reasons.add("simple low-risk single-step task");
        }
        return new Decision(useTeam, List.copyOf(reasons));
    }

    private boolean securitySensitive(String goal, List<String> files) {
        if (goal.contains("security") || goal.contains("approval") || goal.contains("risk") || goal.contains("permission")) {
            return true;
        }
        for (String file : files) {
            String lower = file != null ? file.toLowerCase(Locale.ROOT) : "";
            if (lower.contains("/security/") || lower.contains("approval") || lower.contains("risk") || lower.contains("permission")) {
                return true;
            }
        }
        return false;
    }

    private boolean needsTests(String goal, List<String> files) {
        if (goal.contains("test") || goal.contains("verify")) {
            return true;
        }
        return files.stream().anyMatch(file -> file != null && file.endsWith(".java"));
    }

    public record Decision(boolean useTeam, List<String> reasons) {
        public Decision {
            reasons = reasons != null ? List.copyOf(reasons) : List.of();
        }
    }
}
