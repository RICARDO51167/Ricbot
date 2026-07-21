package ricbot.domain.team;

import ricbot.domain.security.CommandRiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class VerificationService {

    public VerificationResult verify(VerificationInput input) {
        VerificationInput safe = input != null ? input : new VerificationInput("", "", "", List.of(), "", List.of(), List.of(), List.of(), "");
        if (safe.evidence() != null && !safe.evidence().isEmpty()) {
            return verifyStructured(safe);
        }
        List<String> reasons = new ArrayList<>();
        List<String> suspiciousChanges = suspiciousChanges(safe);
        List<String> missingTests = missingTests(safe.suggestedTests(), safe.executedTests());
        List<String> requiredActions = new ArrayList<>();
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
        reasons.add("fallback to text rules");

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
                humanApprovalRequired,
                confidence,
                null
        );
    }

    private VerificationResult verifyStructured(VerificationInput input) {
        VerificationEvidence evidence = input.evidence();
        List<String> reasons = new ArrayList<>();
        List<String> missingTests = new ArrayList<>();
        List<String> suspiciousChanges = new ArrayList<>();
        List<String> requiredActions = new ArrayList<>();

        for (ApprovalEvidence approval : evidence.approvals()) {
            if (ApprovalEvidence.REJECTED.equalsIgnoreCase(approval.status())) {
                reasons.add("rejected approval");
                requiredActions.add("Resolve rejected approval before accepting the task.");
                return structuredResult(VerificationResult.Status.REJECT, input, reasons, missingTests, suspiciousChanges,
                        requiredActions, true, risk(approval.riskLevel()), 0.72d);
            }
        }
        for (ApprovalEvidence approval : evidence.approvals()) {
            if (ApprovalEvidence.PENDING.equalsIgnoreCase(approval.status())) {
                reasons.add("pending approval");
                requiredActions.add("Resolve pending approval before accepting the task.");
                return structuredResult(VerificationResult.Status.NEEDS_HUMAN, input, reasons, missingTests, suspiciousChanges,
                        requiredActions, true, risk(approval.riskLevel()), 0.66d);
            }
        }
        for (ApprovalEvidence approval : evidence.approvals()) {
            if (isHighRisk(approval.riskLevel()) && !ApprovalEvidence.APPROVED.equalsIgnoreCase(approval.status())) {
                reasons.add("pending approval");
                requiredActions.add("High-risk approval must be approved before accepting the task.");
                return structuredResult(VerificationResult.Status.NEEDS_HUMAN, input, reasons, missingTests, suspiciousChanges,
                        requiredActions, true, risk(approval.riskLevel()), 0.66d);
            }
        }

        for (ExecutedTestEvidence test : evidence.executedTests()) {
            if ((test.exitCode() != null && test.exitCode() != 0) || !test.passed()) {
                reasons.add("failed structured test evidence");
                if (textSaysPassed(input)) {
                    reasons.add("text/evidence conflict");
                }
                requiredActions.add("Inspect failed structured test evidence and rerun verifier.");
                if (!test.command().isBlank()) {
                    missingTests.add(test.command());
                }
                return structuredResult(VerificationResult.Status.REJECT, input, reasons, missingTests, suspiciousChanges,
                        requiredActions, false, CommandRiskLevel.MEDIUM, 0.74d);
            }
        }

        for (String suggested : input.suggestedTests()) {
            if (!coveredByPassingEvidence(suggested, evidence.executedTests())) {
                reasons.add("missing passing evidence for suggested test");
                missingTests.add(suggested);
            }
        }
        if (!missingTests.isEmpty()) {
            requiredActions.add("Run missing suggested tests: " + String.join("; ", missingTests));
            return structuredResult(VerificationResult.Status.NEEDS_HUMAN, input, reasons, missingTests, suspiciousChanges,
                    requiredActions, true, CommandRiskLevel.MEDIUM, 0.66d);
        }

        for (DiffEvidence diff : evidence.changedFiles()) {
            if (isHighRisk(diff.riskLevel())) {
                reasons.add("high risk diff evidence");
                suspiciousChanges.add(diff.path());
                requiredActions.add("Request human review for high-risk changes.");
                return structuredResult(VerificationResult.Status.NEEDS_HUMAN, input, reasons, missingTests, suspiciousChanges,
                        requiredActions, true, CommandRiskLevel.HIGH, 0.66d);
            }
            if (diff.securitySensitive()) {
                reasons.add("security sensitive diff");
                suspiciousChanges.add(diff.path());
                requiredActions.add("Request human review for security-sensitive changes.");
                return structuredResult(VerificationResult.Status.NEEDS_HUMAN, input, reasons, missingTests, suspiciousChanges,
                        requiredActions, true, CommandRiskLevel.HIGH, 0.66d);
            }
            if (diff.testDeleted()) {
                reasons.add("test deletion detected");
                suspiciousChanges.add(diff.path());
                requiredActions.add("Confirm test deletion is intentional and covered by replacement tests.");
                return structuredResult(VerificationResult.Status.NEEDS_HUMAN, input, reasons, missingTests, suspiciousChanges,
                        requiredActions, true, CommandRiskLevel.HIGH, 0.66d);
            }
        }
        if (!evidence.changedFiles().isEmpty() && evidence.changedFiles().stream().allMatch(DiffEvidence::runtimeArtifact)) {
            reasons.add("only runtime artifacts changed");
            requiredActions.add("Provide non-runtime user change evidence before accepting the task.");
            return structuredResult(VerificationResult.Status.NEEDS_HUMAN, input, reasons, missingTests, suspiciousChanges,
                    requiredActions, true, CommandRiskLevel.LOW, 0.62d);
        }

        reasons.add("structured evidence passed");
        if (textSaysFailed(input)) {
            reasons.add("text/evidence conflict");
        }
        return structuredResult(VerificationResult.Status.PASS, input, reasons, missingTests, suspiciousChanges,
                requiredActions, false, CommandRiskLevel.LOW, 0.82d);
    }

    private VerificationResult structuredResult(
            VerificationResult.Status status,
            VerificationInput input,
            List<String> reasons,
            List<String> missingTests,
            List<String> suspiciousChanges,
            List<String> requiredActions,
            boolean humanApprovalRequired,
            CommandRiskLevel riskLevel,
            double confidence
    ) {
        String primaryReason = reasons.isEmpty() ? "structured evidence passed" : reasons.get(0);
        String summary = switch (status) {
            case PASS -> "Verifier accepted the worker result from structured evidence.";
            case REJECT -> "Verifier rejected the worker result from structured evidence.";
            case NEEDS_HUMAN -> "Verifier requires human approval from structured evidence.";
        };
        return new VerificationResult(
                status,
                primaryReason,
                summary,
                input.suggestedTests(),
                riskLevel,
                reasons,
                missingTests,
                suspiciousChanges,
                requiredActions,
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

    private boolean coveredByPassingEvidence(String suggested, List<ExecutedTestEvidence> executedTests) {
        String normalizedSuggested = normalize(suggested);
        if (normalizedSuggested.isBlank()) {
            return true;
        }
        for (ExecutedTestEvidence executed : executedTests != null ? executedTests : List.<ExecutedTestEvidence>of()) {
            if (!executed.passed() || (executed.exitCode() != null && executed.exitCode() != 0)) {
                continue;
            }
            String normalizedExecuted = normalize(executed.command());
            if (!normalizedExecuted.isBlank()
                    && (normalizedExecuted.contains(normalizedSuggested) || normalizedSuggested.contains(normalizedExecuted))) {
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

    private boolean isHighRisk(CommandRiskLevel riskLevel) {
        return riskLevel == CommandRiskLevel.HIGH || riskLevel == CommandRiskLevel.BLOCKED;
    }

    private CommandRiskLevel risk(CommandRiskLevel riskLevel) {
        return riskLevel != null ? riskLevel : CommandRiskLevel.LOW;
    }

    private boolean textSaysPassed(VerificationInput input) {
        return textContains(input, "passed", "pass", "success", "succeeded", "通过", "成功");
    }

    private boolean textSaysFailed(VerificationInput input) {
        return textContains(input, "failed", "failure", "reject", "rejected", "失败", "未通过");
    }

    private boolean textContains(VerificationInput input, String... needles) {
        String text = String.join("\n",
                input.workerSummary(),
                input.taskSummary(),
                input.teamWhiteboardSummary(),
                String.join("\n", input.diffReviews()),
                String.join("\n", input.approvalRecords())
        ).toLowerCase(Locale.ROOT);
        return contains(text, needles);
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
