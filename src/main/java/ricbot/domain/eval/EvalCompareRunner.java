package ricbot.domain.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EvalCompareRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    public EvalComparisonResult compare(Path baselineRunDir, Path candidateRunDir, Path outputDir) throws Exception {
        if (baselineRunDir == null || candidateRunDir == null) {
            throw new IllegalArgumentException("--baseline and --candidate are required");
        }
        Path baseline = baselineRunDir.toAbsolutePath().normalize();
        Path candidate = candidateRunDir.toAbsolutePath().normalize();
        Map<String, EvalCaseResult> baselineCases = loadCases(baseline);
        Map<String, EvalCaseResult> candidateCases = loadCases(candidate);
        EvalRunSummary baselineSummary = loadSummary(baseline);
        EvalRunSummary candidateSummary = loadSummary(candidate);

        List<Map<String, Object>> changes = new ArrayList<>();
        int regressions = 0;
        int improvements = 0;
        int missingCases = 0;
        int newCases = 0;

        for (Map.Entry<String, EvalCaseResult> entry : baselineCases.entrySet()) {
            String id = entry.getKey();
            EvalCaseResult base = entry.getValue();
            EvalCaseResult cand = candidateCases.get(id);
            if (cand == null) {
                missingCases++;
                regressions++;
                changes.add(change("missing_case", id, base, null, true));
                continue;
            }
            boolean basePass = "pass".equals(base.getStatus());
            boolean candPass = "pass".equals(cand.getStatus());
            if (basePass && !candPass) {
                regressions++;
                changes.add(change("regression", id, base, cand, true));
            } else if (!basePass && candPass) {
                improvements++;
                changes.add(change("improvement", id, base, cand, false));
            } else if (!basePass && !candPass && !sameFailureKind(base, cand)) {
                changes.add(change("failure_kind_changed", id, base, cand, false));
            }
        }

        for (Map.Entry<String, EvalCaseResult> entry : candidateCases.entrySet()) {
            if (!baselineCases.containsKey(entry.getKey())) {
                newCases++;
                EvalCaseResult cand = entry.getValue();
                boolean regression = !"pass".equals(cand.getStatus());
                if (regression) {
                    regressions++;
                }
                changes.add(change(regression ? "new_failing_case" : "new_case", entry.getKey(), null, cand, regression));
            }
        }

        String runId = "compare-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path artifactDir = outputDir != null
                ? outputDir.toAbsolutePath().normalize()
                : candidate.resolve("comparison-" + runId);
        Files.createDirectories(artifactDir);

        EvalComparisonResult result = new EvalComparisonResult()
                .setStatus(regressions > 0 ? "fail" : "pass")
                .setBaselineRunDir(baseline.toString())
                .setCandidateRunDir(candidate.toString())
                .setBaselineTotal(baselineCases.size())
                .setCandidateTotal(candidateCases.size())
                .setRegressions(regressions)
                .setImprovements(improvements)
                .setMissingCases(missingCases)
                .setNewCases(newCases)
                .setSummaryDelta(summaryDelta(baselineSummary, candidateSummary))
                .setChanges(changes)
                .setArtifactDir(artifactDir.toString());

        writeJson(artifactDir.resolve("comparison.json"), result);
        writeReport(artifactDir.resolve("comparison-report.md"), result);
        return result;
    }

    private Map<String, EvalCaseResult> loadCases(Path runDir) throws Exception {
        Path casesJsonl = runDir.resolve("cases.jsonl");
        if (!Files.exists(casesJsonl)) {
            throw new IllegalArgumentException("cases.jsonl not found: " + casesJsonl);
        }
        Map<String, EvalCaseResult> cases = new LinkedHashMap<>();
        for (String line : Files.readAllLines(casesJsonl, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            EvalCaseResult result = MAPPER.readValue(trimmed, EvalCaseResult.class);
            if (result.getId() != null && !result.getId().isBlank()) {
                cases.put(result.getId(), result);
            }
        }
        return cases;
    }

    private EvalRunSummary loadSummary(Path runDir) {
        Path summary = runDir.resolve("summary.json");
        if (!Files.exists(summary)) {
            return null;
        }
        try {
            return MAPPER.readValue(summary.toFile(), EvalRunSummary.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> change(String type, String id, EvalCaseResult baseline, EvalCaseResult candidate, boolean regression) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("id", id);
        out.put("regression", regression);
        if (baseline != null) {
            out.put("baseline", caseSummary(baseline));
        }
        if (candidate != null) {
            out.put("candidate", caseSummary(candidate));
        }
        return out;
    }

    private Map<String, Object> caseSummary(EvalCaseResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", result.getStatus());
        out.put("failure_kind", result.getFailureKind());
        out.put("failure_detail", result.getFailureDetail());
        out.put("duration_ms", result.getDurationMs());
        out.put("model_calls", result.getModelCalls() != null ? result.getModelCalls().size() : 0);
        out.put("tools_used", result.getToolsUsed());
        out.put("workspace_changes", workspaceChanges(result));
        out.put("artifact_path", result.getArtifactPath());
        return out;
    }

    private Map<String, Object> summaryDelta(EvalRunSummary baseline, EvalRunSummary candidate) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (baseline == null || candidate == null) {
            out.put("available", false);
            return out;
        }
        out.put("available", true);
        out.put("passed_delta", candidate.getPassed() - baseline.getPassed());
        out.put("failed_delta", candidate.getFailed() - baseline.getFailed());
        out.put("duration_p50_ms_delta", candidate.getDurationP50Ms() - baseline.getDurationP50Ms());
        out.put("duration_p95_ms_delta", candidate.getDurationP95Ms() - baseline.getDurationP95Ms());
        out.put("model_calls_delta", candidate.getTotalModelCalls() - baseline.getTotalModelCalls());
        out.put("tool_calls_delta", candidate.getTotalToolCalls() - baseline.getTotalToolCalls());
        out.put("workspace_changes_delta", candidate.getTotalWorkspaceChanges() - baseline.getTotalWorkspaceChanges());
        return out;
    }

    private boolean sameFailureKind(EvalCaseResult baseline, EvalCaseResult candidate) {
        return java.util.Objects.equals(baseline.getFailureKind(), candidate.getFailureKind());
    }

    private int workspaceChanges(EvalCaseResult result) {
        if (result == null || result.getWorkspaceDiff() == null) {
            return 0;
        }
        Object raw = result.getWorkspaceDiff().get("change_count");
        return raw instanceof Number n ? n.intValue() : 0;
    }

    private void writeReport(Path path, EvalComparisonResult result) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("# Ricbot Eval Comparison\n\n");
        out.append("- status: ").append(result.getStatus()).append('\n');
        out.append("- baseline: ").append(result.getBaselineRunDir()).append('\n');
        out.append("- candidate: ").append(result.getCandidateRunDir()).append('\n');
        out.append("- baseline_total: ").append(result.getBaselineTotal()).append('\n');
        out.append("- candidate_total: ").append(result.getCandidateTotal()).append('\n');
        out.append("- regressions: ").append(result.getRegressions()).append('\n');
        out.append("- improvements: ").append(result.getImprovements()).append('\n');
        out.append("- missing_cases: ").append(result.getMissingCases()).append('\n');
        out.append("- new_cases: ").append(result.getNewCases()).append('\n');
        out.append("- summary_delta: ").append(result.getSummaryDelta()).append("\n\n");

        if (result.getChanges().isEmpty()) {
            out.append("No status changes detected.\n");
        } else {
            out.append("## Changes\n\n");
            for (Map<String, Object> change : result.getChanges()) {
                out.append("### ").append(change.get("id")).append("\n\n");
                out.append("- type: ").append(change.get("type")).append('\n');
                out.append("- regression: ").append(change.get("regression")).append('\n');
                appendCase(out, "baseline", change.get("baseline"));
                appendCase(out, "candidate", change.get("candidate"));
                out.append('\n');
            }
        }
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }

    private void appendCase(StringBuilder out, String label, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> value = copyMap(map);
        out.append("- ").append(label).append("_status: ").append(value.get("status")).append('\n');
        if (value.get("failure_kind") != null) {
            out.append("- ").append(label).append("_failure_kind: ").append(value.get("failure_kind")).append('\n');
        }
        if (value.get("failure_detail") != null) {
            out.append("- ").append(label).append("_failure_detail: ").append(value.get("failure_detail")).append('\n');
        }
        if (value.get("artifact_path") != null) {
            out.append("- ").append(label).append("_artifact: ").append(value.get("artifact_path")).append('\n');
        }
    }

    private Map<String, Object> copyMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static void writeJson(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }
}
