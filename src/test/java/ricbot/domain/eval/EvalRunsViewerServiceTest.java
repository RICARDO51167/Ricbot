package ricbot.domain.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalRunsViewerServiceTest {

    @Test
    void listRuns_returnsEmptyWhenEvalDirectoryMissing(@TempDir Path workspace) {
        EvalRunsViewerService service = new EvalRunsViewerService(workspace);

        assertTrue(service.listRuns().isEmpty());
    }

    @Test
    void listRuns_readsNormalRunSummary(@TempDir Path workspace) throws Exception {
        Path runDir = createRun(workspace, "run-1");
        Files.writeString(runDir.resolve("summary.json"), """
                {
                  "run_id": "run-1",
                  "started_at": "2026-05-22T01:00:00Z",
                  "total": 3,
                  "passed": 2,
                  "failed": 1,
                  "skipped": 0,
                  "expected_failed": 0,
                  "unexpected_passed": 0,
                  "duration_ms": 1234,
                  "failures_by_kind": {"assertion": 1}
                }
                """);
        Files.writeString(runDir.resolve("manifest.json"), """
                {
                  "provider_mode": "smoke",
                  "model": "gpt-4o-mini"
                }
                """);
        Files.writeString(runDir.resolve("report.md"), "# Report\n");

        List<EvalRunSummary> runs = new EvalRunsViewerService(workspace).listRuns();

        assertEquals(1, runs.size());
        EvalRunSummary run = runs.get(0);
        assertEquals("run-1", run.getRunId());
        assertEquals("2026-05-22T01:00:00Z", run.getCreatedAt());
        assertEquals("smoke", run.getProviderMode());
        assertEquals("gpt-4o-mini", run.getModel());
        assertEquals(3, run.getTotal());
        assertEquals(1, run.getFailed());
        assertEquals(Map.of("assertion", 1), run.getFailuresByKind());
        assertEquals("run-1/report.md", run.getReportPath());
    }

    @Test
    void detail_readsCasesJsonlAndReport(@TempDir Path workspace) throws Exception {
        Path runDir = createRun(workspace, "run-2");
        Path caseArtifact = runDir.resolve("cases").resolve("case-a.json");
        Files.createDirectories(caseArtifact.getParent());
        Files.writeString(caseArtifact, "{}");
        Files.writeString(runDir.resolve("summary.json"), """
                {"run_id":"run-2","started_at":"2026-05-22T02:00:00Z","total":1,"failed":1}
                """);
        Files.writeString(runDir.resolve("manifest.json"), """
                {"provider_mode":"replay","model":"gpt-4.1-mini","api_key":"do-not-leak"}
                """);
        Files.writeString(runDir.resolve("cases.jsonl"), """
                {"id":"case-a","status":"fail","failure_kind":"tool_error","duration_ms":42,"tools_used":["read_file"],"artifact_path":"%s"}
                """.formatted(caseArtifact.toAbsolutePath().normalize().toString().replace("\\", "\\\\")));
        Files.writeString(runDir.resolve("report.md"), "# Report\n<script>alert(1)</script>\n");

        EvalRunDetail detail = new EvalRunsViewerService(workspace).detail("run-2");

        assertEquals("run-2", detail.summary().getRunId());
        assertEquals(1, detail.cases().size());
        EvalCaseSummary caseSummary = detail.cases().get(0);
        assertEquals("case-a", caseSummary.id());
        assertEquals("fail", caseSummary.status());
        assertEquals("tool_error", caseSummary.failureKind());
        assertEquals(List.of("read_file"), caseSummary.tools());
        assertEquals("run-2/cases/case-a.json", caseSummary.artifactPath());
        assertTrue(detail.reportMarkdown().contains("<script>alert(1)</script>"));
        assertEquals("[REDACTED]", detail.manifest().get("api_key"));
    }

    @Test
    void listRuns_downgradesBadJsonToWarning(@TempDir Path workspace) throws Exception {
        Path runDir = createRun(workspace, "run-bad");
        Files.writeString(runDir.resolve("summary.json"), "{bad json");

        List<EvalRunSummary> runs = new EvalRunsViewerService(workspace).listRuns();

        assertEquals(1, runs.size());
        assertEquals("run-bad", runs.get(0).getRunId());
        assertTrue(runs.get(0).getWarnings().stream().anyMatch(w -> w.contains("failed to read summary.json")));
    }

    @Test
    void detail_warnsForBadCaseLineAndHidesOutsideArtifactPath(@TempDir Path workspace) throws Exception {
        Path runDir = createRun(workspace, "run-cases");
        Files.writeString(runDir.resolve("summary.json"), "{\"run_id\":\"run-cases\"}");
        Files.writeString(runDir.resolve("cases.jsonl"), """
                {"id":"case-a","status":"fail","failure_kind":"assertion","artifact_path":"/tmp/outside.json"}
                {bad json}
                """);

        EvalRunDetail detail = new EvalRunsViewerService(workspace).detail("run-cases");

        assertEquals(1, detail.cases().size());
        assertEquals("", detail.cases().get(0).artifactPath());
        assertTrue(detail.toMap().get("warnings").toString().contains("outside eval root"));
        assertTrue(detail.toMap().get("warnings").toString().contains("cases.jsonl line 2"));
    }

    @Test
    void detail_rejectsPathTraversalRunId(@TempDir Path workspace) {
        EvalRunsViewerService service = new EvalRunsViewerService(workspace);

        assertThrows(IllegalArgumentException.class, () -> service.detail("../secret"));
        assertThrows(IllegalArgumentException.class, () -> service.detail("..%2Fsecret"));
    }

    private static Path createRun(Path workspace, String runId) throws Exception {
        Path runDir = workspace.resolve(".ricbot").resolve("evals").resolve(runId);
        Files.createDirectories(runDir);
        return runDir;
    }
}
