package ricbot.domain.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class EvalReportWriter {
    private EvalReportWriter() {
    }

    static void write(Path artifactDir, EvalRunSummary summary, List<EvalCaseResult> results, boolean replay) throws Exception {
        if (artifactDir == null || summary == null) {
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append("# Ricbot Eval Report\n\n");
        out.append("- mode: ").append(replay ? "replay" : "run").append('\n');
        out.append("- run_id: ").append(summary.getRunId()).append('\n');
        out.append("- total: ").append(summary.getTotal()).append('\n');
        out.append("- passed: ").append(summary.getPassed()).append('\n');
        out.append("- failed: ").append(summary.getFailed()).append('\n');
        out.append("- skipped: ").append(summary.getSkipped()).append('\n');
        out.append("- expected_failed: ").append(summary.getExpectedFailed()).append('\n');
        out.append("- unexpected_passed: ").append(summary.getUnexpectedPassed()).append('\n');
        out.append("- duration_ms: ").append(summary.getDurationMs()).append('\n');
        out.append("- duration_p50_ms: ").append(summary.getDurationP50Ms()).append('\n');
        out.append("- duration_p95_ms: ").append(summary.getDurationP95Ms()).append('\n');
        out.append("- total_model_calls: ").append(summary.getTotalModelCalls()).append('\n');
        out.append("- total_tool_calls: ").append(summary.getTotalToolCalls()).append('\n');
        out.append("- total_workspace_changes: ").append(summary.getTotalWorkspaceChanges()).append('\n');
        if (summary.getFailuresByKind() != null && !summary.getFailuresByKind().isEmpty()) {
            out.append("- failures_by_kind: ").append(summary.getFailuresByKind()).append('\n');
        }
        out.append('\n');

        List<EvalCaseResult> failed = (results != null ? results : List.<EvalCaseResult>of()).stream()
                .filter(result -> !"pass".equals(result.getStatus())
                        && !"skipped".equals(result.getStatus())
                        && !"xfail".equals(result.getStatus()))
                .toList();
        if (failed.isEmpty()) {
            out.append("All cases passed.\n");
        } else {
            out.append("## Failures\n\n");
            for (EvalCaseResult result : failed) {
                out.append("### ").append(nullToEmpty(result.getId())).append("\n\n");
                out.append("- kind: ").append(nullToEmpty(result.getFailureKind())).append('\n');
                out.append("- detail: ").append(nullToEmpty(result.getFailureDetail())).append('\n');
                out.append("- expectation_status: ").append(nullToEmpty(result.getExpectationStatus())).append('\n');
                out.append("- expectation_reason: ").append(nullToEmpty(result.getExpectationReason())).append('\n');
                out.append("- duration_ms: ").append(result.getDurationMs()).append('\n');
                out.append("- tools_used: ").append(result.getToolsUsed()).append('\n');
                out.append("- artifact: ").append(nullToEmpty(result.getArtifactPath())).append('\n');
                out.append("- replay: `ricbot eval replay --case ").append(nullToEmpty(result.getArtifactPath())).append("`\n");
                appendList(out, "assertion_errors", result.getAssertionErrors());
                appendList(out, "side_effect_violations", result.getSideEffectViolations());
                appendList(out, "fixture_errors", result.getFixtureErrors());
                appendList(out, "workspace_restore_errors", result.getWorkspaceRestoreErrors());
                appendList(out, "session_restore_errors", result.getSessionRestoreErrors());
                appendList(out, "replay_errors", result.getReplayErrors());
                appendWorkspaceSummary(out, result.getWorkspaceDiff());
                out.append('\n');
            }
        }

        Files.createDirectories(artifactDir);
        Files.writeString(artifactDir.resolve("report.md"), out.toString(), StandardCharsets.UTF_8);
    }

    private static void appendList(StringBuilder out, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        out.append("- ").append(label).append(":\n");
        for (String value : values) {
            out.append("  - ").append(nullToEmpty(value)).append('\n');
        }
    }

    private static void appendWorkspaceSummary(StringBuilder out, Map<String, Object> diff) {
        if (diff == null || diff.isEmpty()) {
            return;
        }
        Object changeCount = diff.get("change_count");
        if (changeCount != null) {
            out.append("- workspace_change_count: ").append(changeCount).append('\n');
        }
    }

    private static String nullToEmpty(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
