package ricbot.domain.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EvalSummarySupport {
    private EvalSummarySupport() {
    }

    static EvalRunSummary enrich(EvalRunSummary summary, List<EvalCaseResult> results) {
        List<Long> durations = new ArrayList<>();
        Map<String, Integer> usageTotals = new LinkedHashMap<>();
        int modelCalls = 0;
        int toolCalls = 0;
        int workspaceChanges = 0;

        for (EvalCaseResult result : results != null ? results : List.<EvalCaseResult>of()) {
            if (result == null || "skipped".equals(result.getStatus())) {
                continue;
            }
            durations.add(result.getDurationMs());
            modelCalls += result.getModelCalls() != null ? result.getModelCalls().size() : 0;
            toolCalls += toolCallCount(result);
            workspaceChanges += workspaceChangeCount(result);
            mergeUsage(usageTotals, result);
        }

        durations.sort(Long::compareTo);
        return summary
                .setDurationP50Ms(percentile(durations, 0.50d))
                .setDurationP95Ms(percentile(durations, 0.95d))
                .setTotalModelCalls(modelCalls)
                .setTotalToolCalls(toolCalls)
                .setTotalWorkspaceChanges(workspaceChanges)
                .setTotalUsage(usageTotals);
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private static int toolCallCount(EvalCaseResult result) {
        if (result == null || result.getRunTrace() == null) {
            return result != null && result.getToolsUsed() != null ? result.getToolsUsed().size() : 0;
        }
        Object events = result.getRunTrace().get("events");
        if (!(events instanceof List<?> list)) {
            return result.getToolsUsed() != null ? result.getToolsUsed().size() : 0;
        }
        int count = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> event && "tool_call".equals(String.valueOf(event.get("type")))) {
                count++;
            }
        }
        return count;
    }

    private static int workspaceChangeCount(EvalCaseResult result) {
        if (result == null || result.getWorkspaceDiff() == null) {
            return 0;
        }
        Object raw = result.getWorkspaceDiff().get("change_count");
        return raw instanceof Number n ? n.intValue() : 0;
    }

    private static void mergeUsage(Map<String, Integer> totals, EvalCaseResult result) {
        if (result == null || result.getRunTrace() == null) {
            return;
        }
        Object usage = result.getRunTrace().get("usage");
        if (!(usage instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() instanceof Number n) {
                totals.merge(String.valueOf(entry.getKey()), n.intValue(), Integer::sum);
            }
        }
    }
}
