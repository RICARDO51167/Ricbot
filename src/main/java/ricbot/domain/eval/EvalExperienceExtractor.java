package ricbot.domain.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperienceType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class EvalExperienceExtractor {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public List<ExperienceEntry> extract(Path artifactRunDir, boolean includeXfail, boolean includeSkipped) throws Exception {
        if (artifactRunDir == null) {
            throw new IllegalArgumentException("artifact run directory is required");
        }
        Path runDir = artifactRunDir.toAbsolutePath().normalize();
        Path summaryPath = runDir.resolve("summary.json");
        Path casesPath = runDir.resolve("cases.jsonl");
        if (!Files.exists(summaryPath)) {
            throw new IllegalArgumentException("summary.json not found: " + summaryPath);
        }
        if (!Files.exists(casesPath)) {
            throw new IllegalArgumentException("cases.jsonl not found: " + casesPath);
        }
        EvalRunSummary summary = MAPPER.readValue(summaryPath.toFile(), EvalRunSummary.class);
        return extract(summary, readCases(casesPath), runDir, includeXfail, includeSkipped);
    }

    public List<ExperienceEntry> extract(
            EvalRunSummary summary,
            List<EvalCaseResult> caseResults,
            Path artifactRunDir,
            boolean includeXfail,
            boolean includeSkipped
    ) {
        List<ExperienceEntry> out = new ArrayList<>();
        for (EvalCaseResult result : caseResults != null ? caseResults : List.<EvalCaseResult>of()) {
            ExperienceEntry entry = extract(summary, result, artifactRunDir, includeXfail, includeSkipped);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    public ExperienceEntry extract(
            EvalRunSummary summary,
            EvalCaseResult result,
            Path artifactRunDir,
            boolean includeXfail,
            boolean includeSkipped
    ) {
        if (!shouldExtract(result, includeXfail, includeSkipped)) {
            return null;
        }
        String failureKind = failureKind(result);
        ExperienceType type = typeFor(failureKind, result);
        String caseId = nonBlank(result.getId(), "unknown-case");
        String runId = runId(summary, artifactRunDir);
        String sourceRef = "eval:" + runId + ":" + caseId;
        List<String> relatedFiles = relatedFiles(result);
        List<String> suggestedTests = suggestedTests(type, result, artifactRunDir);
        return ExperienceEntry.candidate(
                type,
                title(type, failureKind, caseId),
                content(type, failureKind, result),
                whenToApply(failureKind, result),
                evidence(failureKind, result),
                "eval_case",
                sourceRef,
                relatedFiles,
                suggestedTests,
                confidence(type, failureKind, result),
                failureKind
        );
    }

    private List<EvalCaseResult> readCases(Path casesPath) throws Exception {
        List<EvalCaseResult> out = new ArrayList<>();
        for (String line : Files.readAllLines(casesPath, StandardCharsets.UTF_8)) {
            String trimmed = line != null ? line.trim() : "";
            if (!trimmed.isBlank()) {
                out.add(MAPPER.readValue(trimmed, EvalCaseResult.class));
            }
        }
        return out;
    }

    private boolean shouldExtract(EvalCaseResult result, boolean includeXfail, boolean includeSkipped) {
        if (result == null) {
            return false;
        }
        String status = lower(result.getStatus());
        if ("skipped".equals(status)) {
            return includeSkipped && !blank(result.getExpectationReason());
        }
        if ("xfail".equals(status)) {
            return includeXfail;
        }
        if ("fail".equals(status) || "failed".equals(status) || "xpass".equals(status) || "regression".equals(status)) {
            return true;
        }
        String kind = lower(result.getFailureKind());
        return "unexpected_pass".equals(kind) || "unexpected_passed".equals(kind);
    }

    private ExperienceType typeFor(String failureKind, EvalCaseResult result) {
        String haystack = lower(failureKind + " " + result.getFailureDetail() + " " + result.getExpectationReason()
                + " " + result.getWorkspaceRestoreErrors() + " " + result.getToolsUsed());
        if (haystack.contains("security_blocked") || (haystack.contains("security") && haystack.contains("blocked"))) {
            return ExperienceType.SECURITY_RULE;
        }
        if (haystack.contains("workspace_restore_error") || haystack.contains("workspace restore")) {
            return ExperienceType.TOOL_POLICY;
        }
        if (haystack.contains("mcp_timeout") || haystack.contains("timeout")) {
            return ExperienceType.TOOL_POLICY;
        }
        if (haystack.contains("tool_error")) {
            return result.getToolsUsed() != null && !result.getToolsUsed().isEmpty()
                    ? ExperienceType.TOOL_POLICY
                    : ExperienceType.FAILURE_LESSON;
        }
        if (haystack.contains("assertion_failed") || haystack.contains("unexpected_pass") || haystack.contains("skipped")) {
            return ExperienceType.TEST_POLICY;
        }
        if (haystack.contains("model_error")) {
            return ExperienceType.FAILURE_LESSON;
        }
        return ExperienceType.FAILURE_LESSON;
    }

    private String title(ExperienceType type, String failureKind, String caseId) {
        String kind = nonBlank(failureKind, "unknown");
        return switch (type) {
            case TEST_POLICY -> "Eval " + kind + ": " + caseId;
            case TOOL_POLICY -> "Eval tool policy failure: " + caseId;
            case SECURITY_RULE -> "Eval security block: " + caseId;
            default -> "Eval failure lesson: " + caseId;
        };
    }

    private String content(ExperienceType type, String failureKind, EvalCaseResult result) {
        String detail = firstNonBlank(
                first(result.getAssertionErrors()),
                result.getFailureDetail(),
                result.getExpectationReason(),
                first(result.getWorkspaceRestoreErrors()),
                first(result.getReplayErrors()),
                result.getMaxDurationExceededDetail()
        );
        String base = "Eval case `" + nonBlank(result.getId(), "unknown-case") + "` ended with failureKind=`"
                + nonBlank(failureKind, "unknown") + "`.";
        if (!detail.isBlank()) {
            base += " Key signal: " + detail;
        }
        return switch (type) {
            case TEST_POLICY -> base + " Reproduce with the replay artifact before changing golden expectations, xfail status, or assertions.";
            case TOOL_POLICY -> base + " Inspect tool trace, workspace restore logs, and MCP timeout behavior before changing tool policy.";
            case SECURITY_RULE -> base + " Keep the risk/security behavior explicit and verify the block is intentional before weakening policy.";
            default -> base + " Preserve the artifact and identify whether the fix belongs in prompt, harness, or domain behavior.";
        };
    }

    private String whenToApply(String failureKind, EvalCaseResult result) {
        return "When an eval case fails or xfails with failureKind=`" + nonBlank(failureKind, "unknown")
                + "` and caseId=`" + nonBlank(result.getId(), "unknown-case") + "`.";
    }

    private String evidence(String failureKind, EvalCaseResult result) {
        List<String> lines = new ArrayList<>();
        lines.add("failureKind: " + nonBlank(failureKind, "unknown"));
        lines.add("caseId: " + nonBlank(result.getId(), "unknown-case"));
        lines.add("status: " + nonBlank(result.getStatus(), "unknown"));
        lines.add("assertionOrStopReason: " + nonBlank(assertionOrStopReason(result), "none"));
        lines.add("toolSummary: " + toolSummary(result));
        lines.add("workspaceDiff: " + workspaceDiffSummary(result));
        if (!blank(result.getArtifactPath())) {
            lines.add("artifact: " + result.getArtifactPath());
        }
        return String.join("\n", lines);
    }

    private String assertionOrStopReason(EvalCaseResult result) {
        return firstNonBlank(
                first(result.getAssertionErrors()),
                result.getFailureDetail(),
                result.getMaxDurationExceededDetail(),
                result.getExpectationReason(),
                first(result.getReplayErrors()),
                first(result.getWorkspaceRestoreErrors()),
                first(result.getSessionRestoreErrors()),
                first(result.getSideEffectViolations())
        );
    }

    private String toolSummary(EvalCaseResult result) {
        List<String> tools = result.getToolsUsed() != null ? result.getToolsUsed() : List.of();
        if (tools.isEmpty()) {
            return "tools=none";
        }
        return "tools=" + String.join(",", distinct(tools).stream().limit(8).toList());
    }

    @SuppressWarnings("unchecked")
    private String workspaceDiffSummary(EvalCaseResult result) {
        Map<String, Object> diff = result.getWorkspaceDiff();
        if (diff == null || diff.isEmpty()) {
            return "change_count=0";
        }
        List<String> paths = new ArrayList<>();
        for (String key : List.of("added", "modified", "deleted")) {
            Object raw = diff.get(key);
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Object path = map.get("path");
                        if (path != null && !String.valueOf(path).isBlank()) {
                            paths.add(key + ":" + path);
                        }
                    }
                }
            }
        }
        return "change_count=" + diff.getOrDefault("change_count", 0)
                + (paths.isEmpty() ? "" : "; paths=" + String.join(",", paths.stream().limit(8).toList()));
    }

    @SuppressWarnings("unchecked")
    private List<String> relatedFiles(EvalCaseResult result) {
        List<String> out = new ArrayList<>();
        Map<String, Object> diff = result.getWorkspaceDiff();
        if (diff != null) {
            for (String key : List.of("added", "modified", "deleted")) {
                Object raw = diff.get(key);
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            Object path = map.get("path");
                            if (path != null && !String.valueOf(path).isBlank()) {
                                out.add(String.valueOf(path));
                            }
                        }
                    }
                }
            }
        }
        return distinct(out);
    }

    private List<String> suggestedTests(ExperienceType type, EvalCaseResult result, Path artifactRunDir) {
        List<String> out = new ArrayList<>();
        if (!blank(result.getArtifactPath())) {
            out.add("ricbot eval replay --case " + result.getArtifactPath());
        } else if (artifactRunDir != null) {
            out.add("ricbot eval replay --run " + artifactRunDir.toAbsolutePath().normalize());
        }
        out.add("./mvnw -q -Dtest='ricbot.domain.eval.*Test' test");
        if (type == ExperienceType.TOOL_POLICY) {
            out.add("./mvnw -q -Dtest='ricbot.tool.*.*Test' test");
        } else if (type == ExperienceType.SECURITY_RULE) {
            out.add("./mvnw -q -Dtest='ricbot.domain.security.*Test' test");
        }
        return distinct(out);
    }

    private double confidence(ExperienceType type, String failureKind, EvalCaseResult result) {
        String status = lower(result.getStatus());
        String kind = lower(failureKind);
        if ("skipped".equals(status)) {
            return 0.45d;
        }
        if ("xfail".equals(status)) {
            return 0.55d;
        }
        if (type == ExperienceType.SECURITY_RULE || kind.contains("workspace_restore_error")) {
            return 0.75d;
        }
        if (type == ExperienceType.TOOL_POLICY || kind.contains("unexpected_pass")) {
            return 0.70d;
        }
        return 0.65d;
    }

    private String failureKind(EvalCaseResult result) {
        String kind = result != null ? result.getFailureKind() : "";
        if (!blank(kind)) {
            return kind;
        }
        String status = lower(result != null ? result.getStatus() : "");
        if ("skipped".equals(status)) {
            return "skipped";
        }
        if ("xpass".equals(status) || "unexpected_pass".equals(lower(result != null ? result.getExpectationStatus() : ""))) {
            return "unexpected_pass";
        }
        return "unknown";
    }

    private String runId(EvalRunSummary summary, Path artifactRunDir) {
        if (summary != null && !blank(summary.getRunId())) {
            return summary.getRunId();
        }
        if (artifactRunDir != null && artifactRunDir.getFileName() != null) {
            return artifactRunDir.getFileName().toString();
        }
        return "unknown-run";
    }

    private List<String> distinct(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values != null ? values : List.<String>of()) {
            if (value != null && !value.isBlank()) {
                seen.add(value.trim());
            }
        }
        return List.copyOf(seen);
    }

    private String first(List<String> values) {
        return values != null && !values.isEmpty() && values.get(0) != null ? values.get(0) : "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values != null ? values : new String[0]) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String nonBlank(String value, String fallback) {
        return !blank(value) ? value.trim() : fallback;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String lower(String value) {
        return value != null ? value.toLowerCase(Locale.ROOT) : "";
    }
}
