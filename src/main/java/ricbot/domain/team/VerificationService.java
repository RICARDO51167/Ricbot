package ricbot.domain.team;

import ricbot.domain.security.CommandRiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class VerificationService {

    public VerificationResult verify(VerificationInput input) {
        VerificationInput safe = input != null ? input : new VerificationInput("", "", "", List.of(), "", List.of(), List.of(), List.of(), List.of(), "");
        List<String> reasons = new ArrayList<>();
        List<String> suspiciousChanges = suspiciousChanges(safe);
        List<String> missingTests = missingTests(safe.suggestedTests(), safe.executedTests());
        List<String> requiredActions = new ArrayList<>();
        List<String> experienceActions = new ArrayList<>();
        boolean pendingApproval = containsAny(safe.approvalRecords(), "pending", "blocked", "risklevel=blocked", "risklevel=high", "需要审批", "approval_");
        boolean unresolvedBlocker = hasUnresolvedBlocker(safe.taskSummary())
                || hasUnresolvedBlocker(safe.teamWhiteboardSummary());
        boolean highRisk = highRiskDiff(safe.diffReviews())
                || containsAny(suspiciousChanges, "security", "approval", "provider", "agentloop", "toolregistry", "config", ".github/workflows", "ci workflow");
        boolean deletedTest = containsAny(safe.diffReviews(), "delete", "deleted") && containsAny(safe.diffReviews(), "test");

        if (safe.workerSummary().isBlank()) {
            reasons.add("worker result is empty");
            requiredActions.add("Submit a worker result summary before verification.");
        }
        if (safe.taskSummary().isBlank()) {
            reasons.add("task summary is missing verification context");
            requiredActions.add("Refresh /summary or provide TaskSummary evidence before verification.");
        }
        if (!suspiciousChanges.isEmpty() && safe.executedTests().isEmpty()) {
            reasons.add("suspicious changes require executed tests");
            requiredActions.add("Run targeted tests for suspicious changes.");
        }
        if (!missingTests.isEmpty()) {
            reasons.add("suggested tests were not executed");
            requiredActions.add("Run missing suggested tests: " + String.join("; ", missingTests));
        }
        if (pendingApproval) {
            reasons.add("pending approval or blocked risk found");
            requiredActions.add("Resolve approval records before accepting the task.");
        }
        if (unresolvedBlocker) {
            reasons.add("unresolved blocker found");
            requiredActions.add("Resolve blockers or mark them as accepted by a human.");
        }
        if (highRisk) {
            reasons.add("high risk or security-sensitive diff requires human gate");
            requiredActions.add("Request human review for high-risk changes.");
        }
        if (deletedTest) {
            reasons.add("test deletion requires human gate");
            requiredActions.add("Confirm test deletion is intentional and covered by replacement tests.");
        }
        if (!safe.verifiedExperience().isEmpty() && !missingTests.isEmpty()) {
            experienceActions.add("Candidate experience: missing verified test policy coverage for " + String.join("; ", missingTests));
        }
        if (!reasons.isEmpty()) {
            experienceActions.add("Do not auto-write experience; use /experience extract after summary review if this lesson is reusable.");
        }

        VerificationResult.Status status;
        CommandRiskLevel riskLevel;
        boolean humanApprovalRequired = false;
        if (highRisk || deletedTest || pendingApproval) {
            status = VerificationResult.Status.NEEDS_HUMAN;
            riskLevel = CommandRiskLevel.HIGH;
            humanApprovalRequired = true;
        } else if (safe.workerSummary().isBlank() || safe.taskSummary().isBlank()
                || (!suspiciousChanges.isEmpty() && safe.executedTests().isEmpty())
                || !missingTests.isEmpty()
                || unresolvedBlocker) {
            status = VerificationResult.Status.REJECT;
            riskLevel = !suspiciousChanges.isEmpty() ? CommandRiskLevel.MEDIUM : CommandRiskLevel.LOW;
        } else {
            status = VerificationResult.Status.PASS;
            riskLevel = CommandRiskLevel.LOW;
            reasons.add("suggested tests are covered and no high-risk blocker is present");
        }

        String primaryReason = reasons.isEmpty() ? "verification completed" : reasons.get(0);
        String summary = switch (status) {
            case PASS -> "Verifier accepted the worker result.";
            case REJECT -> "Verifier rejected the worker result and requested revision.";
            case NEEDS_HUMAN -> "Verifier requires human approval before continuing.";
        };
        double confidence = status == VerificationResult.Status.PASS ? 0.76d : status == VerificationResult.Status.REJECT ? 0.72d : 0.66d;
        return new VerificationResult(
                status,
                primaryReason,
                summary,
                safe.suggestedTests(),
                riskLevel,
                reasons,
                missingTests,
                suspiciousChanges,
                requiredActions,
                experienceActions,
                humanApprovalRequired,
                confidence,
                null
        );
    }

    private List<String> suspiciousChanges(VerificationInput input) {
        List<String> out = new ArrayList<>();
        for (String diff : input.diffReviews()) {
            String lower = diff.toLowerCase(Locale.ROOT);
            if (contains(lower, "security", "approval", "provider", "agentloop", "toolregistry", "config", ".github/workflows", "ci", "risk=high", "risklevel: high")) {
                out.add(diff);
            }
            if (contains(lower, "deleted test", "delete test", "test deletion") || (lower.contains("test") && contains(lower, "deleted", "removed"))) {
                out.add(diff);
            }
        }
        return out.stream().distinct().toList();
    }

    private List<String> missingTests(List<String> suggestedTests, List<String> executedTests) {
        List<String> missing = new ArrayList<>();
        for (String suggested : suggestedTests != null ? suggestedTests : List.<String>of()) {
            if (suggested == null || suggested.isBlank()) {
                continue;
            }
            if (!covered(suggested, executedTests)) {
                missing.add(suggested.trim());
            }
        }
        return missing.stream().distinct().toList();
    }

    private boolean covered(String suggested, List<String> executedTests) {
        String normalizedSuggested = normalize(suggested);
        for (String executed : executedTests != null ? executedTests : List.<String>of()) {
            String normalizedExecuted = normalize(executed);
            if (normalizedExecuted.equals(normalizedSuggested)
                    || normalizedExecuted.contains(normalizedSuggested)
                    || normalizedSuggested.contains(normalizedExecuted)) {
                return true;
            }
            String testPattern = extractTestPattern(normalizedSuggested);
            if (!testPattern.isBlank() && normalizedExecuted.contains(testPattern)) {
                return true;
            }
        }
        return false;
    }

    private String extractTestPattern(String command) {
        int index = command.indexOf("-dtest=");
        if (index < 0) {
            return "";
        }
        String tail = command.substring(index + "-dtest=".length());
        int space = tail.indexOf(' ');
        return space >= 0 ? tail.substring(0, space) : tail;
    }

    private boolean highRiskDiff(List<String> values) {
        return containsAny(values, "risk=high", "risklevel: high", "high risk", "blocked");
    }

    private boolean hasUnresolvedBlocker(String value) {
        String lower = value != null ? value.toLowerCase(Locale.ROOT) : "";
        if (lower.isBlank()) {
            return false;
        }
        if (contains(lower, "unresolved blocker", "blocked reason", "blocked:", "阻塞")) {
            return true;
        }
        int index = lower.indexOf("blockers=");
        if (index < 0) {
            return false;
        }
        String tail = lower.substring(index + "blockers=".length());
        int end = tail.length();
        for (String marker : List.of(" suggestedtests=", " executedtests=", " changedfiles=", " approvalrecords=")) {
            int markerIndex = tail.indexOf(marker);
            if (markerIndex >= 0 && markerIndex < end) {
                end = markerIndex;
            }
        }
        String blockers = tail.substring(0, end).trim();
        return !blockers.isBlank() && !"none".equals(blockers) && !"[]".equals(blockers);
    }

    private boolean containsAny(List<String> values, String... needles) {
        for (String value : values != null ? values : List.<String>of()) {
            String lower = value != null ? value.toLowerCase(Locale.ROOT) : "";
            if (contains(lower, needles)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value != null
                ? value.toLowerCase(Locale.ROOT)
                .replace("'", "")
                .replace("\"", "")
                .replaceAll("\\s+", " ")
                .trim()
                : "";
    }
}
