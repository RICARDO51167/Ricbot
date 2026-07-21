package ricbot.domain.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class EvalScenarioLinter {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final Set<String> KNOWN_SIDE_EFFECT_TOKENS = Set.of(
            "none", "read_only", "readonly", "no_files", "files", "file_write", "write",
            "process", "exec", "shell", "spawn", "any", "all"
    );

    public EvalLintResult lint(Path scenariosPath, Path outputDir) throws Exception {
        if (scenariosPath == null) {
            throw new IllegalArgumentException("scenarios path is required");
        }
        Path scenarios = scenariosPath.toAbsolutePath().normalize();
        Path artifactDir = outputDir != null
                ? outputDir.toAbsolutePath().normalize()
                : Path.of("target", "eval-lint").toAbsolutePath().normalize();
        Files.createDirectories(artifactDir);

        List<Map<String, Object>> issues = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int total = 0;
        int lineNo = 0;
        for (String line : Files.readAllLines(scenarios, StandardCharsets.UTF_8)) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            EvalScenario scenario;
            try {
                scenario = MAPPER.readValue(trimmed, EvalScenario.class);
            } catch (Exception e) {
                issues.add(issue("error", lineNo, null, "invalid_json", e.getMessage()));
                continue;
            }
            total++;
            lintScenario(scenario, lineNo, ids, issues);
        }

        int errors = (int) issues.stream().filter(issue -> "error".equals(issue.get("severity"))).count();
        int warnings = (int) issues.stream().filter(issue -> "warning".equals(issue.get("severity"))).count();
        EvalLintResult result = new EvalLintResult()
                .setStatus(errors > 0 ? "fail" : "pass")
                .setScenariosPath(scenarios.toString())
                .setTotalScenarios(total)
                .setErrors(errors)
                .setWarnings(warnings)
                .setIssues(issues)
                .setArtifactDir(artifactDir.toString());
        writeJson(artifactDir.resolve("lint.json"), result);
        writeReport(artifactDir.resolve("lint-report.md"), result);
        return result;
    }

    private void lintScenario(EvalScenario scenario, int lineNo, Set<String> ids, List<Map<String, Object>> issues) {
        String id = scenario.getId();
        if (id == null || id.isBlank()) {
            issues.add(issue("error", lineNo, id, "missing_id", "scenario id is required for stable eval artifacts"));
        } else if (!ids.add(id)) {
            issues.add(issue("error", lineNo, id, "duplicate_id", "scenario id is duplicated: " + id));
        }

        lintExpectationControls(scenario, lineNo, id, issues);

        boolean hasTurns = scenario.getTurns() != null && !scenario.getTurns().isEmpty();
        boolean hasInput = scenario.getInput() != null && !scenario.getInput().isBlank();
        if (hasTurns && hasInput) {
            issues.add(issue("warning", lineNo, id, "input_ignored", "scenario has both input and turns; turns will be used"));
        }
        if (!hasTurns && !hasInput) {
            issues.add(issue("error", lineNo, id, "missing_input", "scenario requires input or turns"));
        }
        if (scenario.getTurns() != null && scenario.getTurns().isEmpty() && scenario.getInput() == null) {
            issues.add(issue("error", lineNo, id, "empty_turns", "turns is empty"));
        }
        if (Boolean.FALSE.equals(scenario.getRestoreWorkspace())) {
            issues.add(issue("warning", lineNo, id, "workspace_restore_disabled", "restore_workspace=false can let this case affect later cases"));
        }
        if (Boolean.FALSE.equals(scenario.getRestoreSession())) {
            issues.add(issue("warning", lineNo, id, "session_restore_disabled", "restore_session=false can let this case affect later cases"));
        }
        if (hasTurns) {
            for (int i = 0; i < scenario.getTurns().size(); i++) {
                lintTurn(scenario.getTurns().get(i), lineNo, id, i, issues);
            }
        }

        lintBudget("max_duration_ms", scenario.getMaxDurationMs(), lineNo, id, issues);
        lintBudget("max_file_changes", scenario.getMaxFileChanges(), lineNo, id, issues);
        lintBudget("max_model_calls", scenario.getMaxModelCalls(), lineNo, id, issues);
        lintBudget("max_tool_calls", scenario.getMaxToolCalls(), lineNo, id, issues);
        lintBudget("expected_session_message_count", scenario.getExpectedSessionMessageCount(), lineNo, id, issues);
        lintRegexes("expected_regex", scenario.getExpectedRegex(), lineNo, id, issues);
        lintJsonPaths("expected_json_required", scenario.getExpectedJsonRequired(), lineNo, id, issues);
        lintJsonPaths("expected_json_absent", scenario.getExpectedJsonAbsent(), lineNo, id, issues);
        lintJsonValuePaths(scenario.getExpectedJsonValues(), lineNo, id, issues);
        lintPaths("workspace_files", scenario.getWorkspaceFiles() != null ? scenario.getWorkspaceFiles().keySet() : List.of(), lineNo, id, issues);
        lintPaths("expected_file_contains", scenario.getExpectedFileContains() != null ? scenario.getExpectedFileContains().keySet() : List.of(), lineNo, id, issues);
        lintPaths("expected_file_not_contains", scenario.getExpectedFileNotContains() != null ? scenario.getExpectedFileNotContains().keySet() : List.of(), lineNo, id, issues);
        lintSideEffectPolicy(scenario.getAllowedSideEffects(), lineNo, id, issues);
        if (!hasAnyExpectation(scenario)) {
            issues.add(issue("error", lineNo, id, "missing_assertions", "scenario has no assertions, budgets, tool expectations, or side-effect policy"));
        }
    }

    private void lintExpectationControls(EvalScenario scenario, int lineNo, String id, List<Map<String, Object>> issues) {
        boolean skip = Boolean.TRUE.equals(scenario.getSkip());
        boolean xfail = Boolean.TRUE.equals(scenario.getXfail());
        if (skip && xfail) {
            issues.add(issue("error", lineNo, id, "skip_and_xfail", "scenario cannot be both skip and xfail"));
        }
        if (skip && (scenario.getSkipReason() == null || scenario.getSkipReason().isBlank())) {
            issues.add(issue("warning", lineNo, id, "missing_skip_reason", "skip scenarios should include skip_reason"));
        }
        if (xfail && (scenario.getXfailReason() == null || scenario.getXfailReason().isBlank())) {
            issues.add(issue("warning", lineNo, id, "missing_xfail_reason", "xfail scenarios should include xfail_reason"));
        }
        if (!xfail && scenario.getExpectedFailureKind() != null && !scenario.getExpectedFailureKind().isBlank()) {
            issues.add(issue("warning", lineNo, id, "unused_expected_failure_kind", "expected_failure_kind only applies when xfail=true"));
        }
    }

    private void lintTurn(EvalTurn turn, int lineNo, String id, int index, List<Map<String, Object>> issues) {
        if (turn == null || turn.getInput() == null || turn.getInput().isBlank()) {
            issues.add(issue("error", lineNo, id, "missing_turn_input", "turn " + index + " requires input"));
            return;
        }
        lintRegexes("turns[" + index + "].expected_regex", turn.getExpectedRegex(), lineNo, id, issues);
        lintJsonPaths("turns[" + index + "].expected_json_required", turn.getExpectedJsonRequired(), lineNo, id, issues);
        lintJsonPaths("turns[" + index + "].expected_json_absent", turn.getExpectedJsonAbsent(), lineNo, id, issues);
        lintJsonValuePaths(turn.getExpectedJsonValues(), lineNo, id, issues);
        if (!hasAnyTurnExpectation(turn)) {
            issues.add(issue("warning", lineNo, id, "turn_without_assertions", "turn " + index + " has no turn-level assertions"));
        }
    }

    private boolean hasAnyExpectation(EvalScenario scenario) {
        return nonEmpty(scenario.getExpectedContains())
                || nonEmpty(scenario.getExpectedNotContains())
                || nonEmpty(scenario.getExpectedRegex())
                || nonEmpty(scenario.getExpectedJsonRequired())
                || nonEmpty(scenario.getExpectedJsonAbsent())
                || nonEmptyMap(scenario.getExpectedJsonValues())
                || nonEmpty(scenario.getExpectedTools())
                || nonEmpty(scenario.getForbiddenTools())
                || nonEmptyMap(scenario.getExpectedFileContains())
                || nonEmptyMap(scenario.getExpectedFileNotContains())
                || scenario.getExpectedSessionMessageCount() != null
                || nonEmpty(scenario.getExpectedSessionContains())
                || nonEmpty(scenario.getExpectedSessionNotContains())
                || nonEmptyMap(scenario.getExpectedSessionRoleCounts())
                || nonEmptyMap(scenario.getExpectedMemoryCounts())
                || nonEmptyMap(scenario.getExpectedMemoryFileContains())
                || nonEmptyMap(scenario.getExpectedMemoryFileNotContains())
                || scenario.getMaxDurationMs() != null
                || scenario.getMaxFileChanges() != null
                || scenario.getMaxModelCalls() != null
                || scenario.getMaxToolCalls() != null
                || (scenario.getAllowedSideEffects() != null && !scenario.getAllowedSideEffects().isBlank())
                || (scenario.getExpectedStopReason() != null && !scenario.getExpectedStopReason().isBlank())
                || (scenario.getTurns() != null && scenario.getTurns().stream().anyMatch(this::hasAnyTurnExpectation));
    }

    private boolean hasAnyTurnExpectation(EvalTurn turn) {
        return turn != null && (nonEmpty(turn.getExpectedContains())
                || nonEmpty(turn.getExpectedNotContains())
                || nonEmpty(turn.getExpectedRegex())
                || nonEmpty(turn.getExpectedJsonRequired())
                || nonEmpty(turn.getExpectedJsonAbsent())
                || nonEmptyMap(turn.getExpectedJsonValues())
                || nonEmpty(turn.getExpectedTools())
                || nonEmpty(turn.getForbiddenTools())
                || (turn.getExpectedStopReason() != null && !turn.getExpectedStopReason().isBlank()));
    }

    private void lintBudget(String field, Integer value, int lineNo, String id, List<Map<String, Object>> issues) {
        if (value != null && value < 0) {
            issues.add(issue("error", lineNo, id, "invalid_budget", field + " must be >= 0"));
        }
    }

    private void lintRegexes(String field, List<String> regexes, int lineNo, String id, List<Map<String, Object>> issues) {
        for (String regex : regexes != null ? regexes : List.<String>of()) {
            if (regex == null || regex.isBlank()) {
                issues.add(issue("error", lineNo, id, "blank_regex", field + " contains a blank regex"));
                continue;
            }
            try {
                Pattern.compile(regex);
            } catch (Exception e) {
                issues.add(issue("error", lineNo, id, "invalid_regex", field + " contains invalid regex '" + regex + "': " + e.getMessage()));
            }
        }
    }

    private void lintJsonPaths(String field, List<String> paths, int lineNo, String id, List<Map<String, Object>> issues) {
        for (String path : paths != null ? paths : List.<String>of()) {
            lintJsonPath(field, path, lineNo, id, issues);
        }
    }

    private void lintJsonValuePaths(Map<String, Object> values, int lineNo, String id, List<Map<String, Object>> issues) {
        if (values == null) {
            return;
        }
        for (String path : values.keySet()) {
            lintJsonPath("expected_json_values", path, lineNo, id, issues);
        }
    }

    private void lintJsonPath(String field, String path, int lineNo, String id, List<Map<String, Object>> issues) {
        if (path == null || path.isBlank()) {
            issues.add(issue("error", lineNo, id, "blank_json_path", field + " contains a blank path"));
            return;
        }
        for (String part : path.split("\\.")) {
            if (part.isBlank() || part.contains("[]")) {
                issues.add(issue("error", lineNo, id, "invalid_json_path", field + " contains invalid path: " + path));
                return;
            }
            int bracket = part.indexOf('[');
            if (bracket >= 0) {
                if (!part.endsWith("]")) {
                    issues.add(issue("error", lineNo, id, "invalid_json_path", field + " contains invalid path: " + path));
                    return;
                }
                String index = part.substring(bracket + 1, part.length() - 1);
                try {
                    if (Integer.parseInt(index) < 0) {
                        throw new NumberFormatException("negative index");
                    }
                } catch (NumberFormatException e) {
                    issues.add(issue("error", lineNo, id, "invalid_json_path", field + " contains invalid array index: " + path));
                    return;
                }
            }
        }
    }

    private void lintPaths(String field, Iterable<String> paths, int lineNo, String id, List<Map<String, Object>> issues) {
        for (String value : paths) {
            if (value == null || value.isBlank()) {
                issues.add(issue("error", lineNo, id, "blank_path", field + " contains a blank path"));
                continue;
            }
            Path path = Path.of(value);
            if (path.isAbsolute()) {
                issues.add(issue("error", lineNo, id, "absolute_path", field + " path must be workspace-relative: " + value));
                continue;
            }
            Path normalized = Path.of(".").resolve(path).normalize();
            if (normalized.startsWith("..")) {
                issues.add(issue("error", lineNo, id, "path_escape", field + " path escapes workspace: " + value));
            }
        }
    }

    private void lintSideEffectPolicy(String policy, int lineNo, String id, List<Map<String, Object>> issues) {
        if (policy == null || policy.isBlank()) {
            return;
        }
        String normalized = policy.toLowerCase(Locale.ROOT);
        boolean known = KNOWN_SIDE_EFFECT_TOKENS.stream().anyMatch(normalized::contains);
        if (!known) {
            issues.add(issue("warning", lineNo, id, "unknown_side_effect_policy", "allowed_side_effects has no known token: " + policy));
        }
    }

    private boolean nonEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private boolean nonEmptyMap(Map<?, ?> values) {
        return values != null && !values.isEmpty();
    }

    private Map<String, Object> issue(String severity, int lineNo, String id, String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("severity", severity);
        out.put("line", lineNo);
        out.put("id", id);
        out.put("code", code);
        out.put("message", message);
        return out;
    }

    private void writeReport(Path path, EvalLintResult result) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("# Ricbot Eval Lint\n\n");
        out.append("- status: ").append(result.getStatus()).append('\n');
        out.append("- scenarios: ").append(result.getScenariosPath()).append('\n');
        out.append("- total_scenarios: ").append(result.getTotalScenarios()).append('\n');
        out.append("- errors: ").append(result.getErrors()).append('\n');
        out.append("- warnings: ").append(result.getWarnings()).append("\n\n");
        if (result.getIssues().isEmpty()) {
            out.append("No lint issues found.\n");
        } else {
            out.append("## Issues\n\n");
            for (Map<String, Object> issue : result.getIssues()) {
                out.append("- [").append(issue.get("severity")).append("] line ")
                        .append(issue.get("line")).append(" ");
                if (issue.get("id") != null) {
                    out.append("`").append(issue.get("id")).append("` ");
                }
                out.append(issue.get("code")).append(": ").append(issue.get("message")).append('\n');
            }
        }
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }

    private void writeJson(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }
}
