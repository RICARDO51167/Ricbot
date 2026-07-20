package ricbot.domain.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates repeatable provider/model runs including cost, latency, and long-trajectory coverage. */
public final class EvalMatrixRunner {
    public MatrixReport run(EvalMatrixSpec spec, EvalRunExecutor executor) throws Exception {
        if (spec == null || executor == null) throw new IllegalArgumentException("spec and executor are required");
        List<CellReport> cells = new ArrayList<>();
        for (EvalModelTarget target : spec.targets()) {
            List<EvalRunData> runs = new ArrayList<>();
            for (int repetition = 0; repetition < spec.repetitions(); repetition++) {
                runs.add(executor.run(target, repetition));
            }
            cells.add(aggregate(target, runs, spec.longTrajectoryTurnThreshold()));
        }
        CellReport recommended = cells.stream().sorted(
                Comparator.comparingDouble(CellReport::passRate).reversed()
                        .thenComparingDouble(CellReport::estimatedCostUsd)
                        .thenComparingLong(CellReport::durationP95Ms)
        ).findFirst().orElseThrow();
        return new MatrixReport(List.copyOf(cells), recommended.target().id());
    }

    private CellReport aggregate(EvalModelTarget target, List<EvalRunData> runs, int longThreshold) {
        int total = 0, passed = 0, modelCalls = 0, toolCalls = 0, longCases = 0, longPassed = 0;
        long inputTokens = 0, outputTokens = 0;
        List<Long> durations = new ArrayList<>();
        Map<String, Integer> failures = new LinkedHashMap<>();
        for (EvalRunData data : runs) {
            EvalRunSummary summary = data.summary();
            total += summary.getTotal();
            passed += summary.getPassed();
            modelCalls += summary.getTotalModelCalls();
            toolCalls += summary.getTotalToolCalls();
            inputTokens += token(summary.getTotalUsage(), "input_tokens", "prompt_tokens");
            outputTokens += token(summary.getTotalUsage(), "output_tokens", "completion_tokens");
            summary.getFailuresByKind().forEach((kind, count) -> failures.merge(kind, count, Integer::sum));
            for (EvalCaseResult result : data.cases()) {
                durations.add(result.getDurationMs());
                if (result.getTurnResults().size() >= longThreshold) {
                    longCases++;
                    if ("pass".equals(result.getStatus())) longPassed++;
                }
            }
        }
        durations.sort(Long::compareTo);
        double cost = (inputTokens * target.inputUsdPerMillionTokens()
                + outputTokens * target.outputUsdPerMillionTokens()) / 1_000_000d;
        return new CellReport(
                target, runs.size(), total, passed, total == 0 ? 0d : (double) passed / total,
                percentile(durations, 0.50), percentile(durations, 0.95), modelCalls, toolCalls,
                inputTokens, outputTokens, cost, longCases, longPassed, Map.copyOf(failures)
        );
    }

    private static long token(Map<String, Integer> usage, String primary, String alternate) {
        if (usage == null) return 0;
        return Math.max(0, usage.getOrDefault(primary, usage.getOrDefault(alternate, 0)));
    }
    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    @FunctionalInterface
    public interface EvalRunExecutor {
        EvalRunData run(EvalModelTarget target, int repetition) throws Exception;
    }
    public record EvalRunData(EvalRunSummary summary, List<EvalCaseResult> cases) {
        public EvalRunData { cases = cases != null ? List.copyOf(cases) : List.of(); }
    }
    public record CellReport(
            EvalModelTarget target, int repetitions, int totalCases, int passedCases, double passRate,
            long durationP50Ms, long durationP95Ms, int modelCalls, int toolCalls,
            long inputTokens, long outputTokens, double estimatedCostUsd,
            int longTrajectoryCases, int longTrajectoryPassed, Map<String, Integer> failuresByKind
    ) { }
    public record MatrixReport(List<CellReport> cells, String recommendedTargetId) { }
}
