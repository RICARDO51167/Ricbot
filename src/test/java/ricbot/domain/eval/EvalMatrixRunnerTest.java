package ricbot.domain.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalMatrixRunnerTest {
    @Test
    void comparesModelsByQualityCostLatencyAndLongTrajectories() throws Exception {
        EvalModelTarget fast = new EvalModelTarget("fast", "test", "fast-model", 1, 2, Map.of());
        EvalModelTarget quality = new EvalModelTarget("quality", "test", "quality-model", 3, 6, Map.of());
        EvalMatrixSpec spec = new EvalMatrixSpec(List.of(fast, quality), 2, 10);

        EvalMatrixRunner.MatrixReport report = new EvalMatrixRunner().run(spec, (target, repetition) -> {
            boolean highQuality = target.id().equals("quality");
            EvalRunSummary summary = new EvalRunSummary()
                    .setTotal(2).setPassed(highQuality ? 2 : 1)
                    .setTotalModelCalls(12).setTotalToolCalls(3)
                    .setTotalUsage(Map.of("input_tokens", 1000, "output_tokens", 500))
                    .setFailuresByKind(highQuality ? Map.of() : Map.of("assertion", 1));
            EvalCaseResult longCase = new EvalCaseResult().setStatus(highQuality ? "pass" : "fail")
                    .setDurationMs(highQuality ? 200 : 100)
                    .setTurnResults(java.util.stream.IntStream.range(0, 12)
                            .mapToObj(index -> Map.<String, Object>of("turn", index)).toList());
            return new EvalMatrixRunner.EvalRunData(summary, List.of(longCase));
        });

        assertEquals("quality", report.recommendedTargetId());
        EvalMatrixRunner.CellReport fastReport = report.cells().get(0);
        assertEquals(0.5d, fastReport.passRate());
        assertEquals(2, fastReport.longTrajectoryCases());
        assertEquals(0, fastReport.longTrajectoryPassed());
        assertEquals(0.004d, fastReport.estimatedCostUsd(), 0.000001d);
        assertEquals(100, fastReport.durationP95Ms());
    }

    @Test
    void repositoryContainsLintableLongTrajectoryScenario(@TempDir Path output) throws Exception {
        List<EvalScenario> scenarios = EvalHarness.loadScenarios(Path.of("evals/long_trajectory.jsonl"), 0);
        assertEquals(12, scenarios.get(0).getTurns().size());
        assertEquals(0, new EvalScenarioLinter().lint(
                Path.of("evals/long_trajectory.jsonl"), output).getErrors());
    }
}
