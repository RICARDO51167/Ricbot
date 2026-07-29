package ricbot.domain.agent.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ContextQualityReport(
        int totalTokens,
        double budgetUsageRate,
        double avgRelevanceScore,
        double duplicateRatio,
        double staleContextRatio,
        double toolResultNoiseRatio,
        boolean missingTaskState,
        boolean compressionApplied,
        List<String> suggestions
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalTokens", totalTokens);
        out.put("budgetUsageRate", round(budgetUsageRate));
        out.put("avgRelevanceScore", round(avgRelevanceScore));
        out.put("duplicateRatio", round(duplicateRatio));
        out.put("staleContextRatio", round(staleContextRatio));
        out.put("toolResultNoiseRatio", round(toolResultNoiseRatio));
        out.put("missingTaskState", missingTaskState);
        out.put("compressionApplied", compressionApplied);
        out.put("suggestions", suggestions != null ? suggestions : List.of());
        return out;
    }

    public static ContextQualityReport fromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        return new ContextQualityReport(
                intValue(map.get("totalTokens")),
                doubleValue(map.get("budgetUsageRate")),
                doubleValue(map.get("avgRelevanceScore")),
                doubleValue(map.get("duplicateRatio")),
                doubleValue(map.get("staleContextRatio")),
                doubleValue(map.get("toolResultNoiseRatio")),
                boolValue(map.get("missingTaskState")),
                boolValue(map.get("compressionApplied")),
                stringList(map.get("suggestions"))
        );
    }

    public String renderStatusBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("context_usage\n");
        sb.append("tokens_estimate: ").append(totalTokens).append("\n");
        sb.append("budget_usage_rate: ").append(round(budgetUsageRate)).append("\n");
        sb.append("avg_relevance_score: ").append(round(avgRelevanceScore)).append("\n");
        sb.append("duplicate_ratio: ").append(round(duplicateRatio)).append("\n");
        sb.append("stale_context_ratio: ").append(round(staleContextRatio)).append("\n");
        sb.append("tool_result_noise_ratio: ").append(round(toolResultNoiseRatio)).append("\n");
        sb.append("compression_applied: ").append(compressionApplied).append("\n");
        if (missingTaskState) {
            sb.append("missing_task_state: true\n");
        }
        if (suggestions != null && !suggestions.isEmpty()) {
            sb.append("suggestions: ").append(String.join("; ", suggestions)).append("\n");
        }
        return sb.toString().trim();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private static int intValue(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(String.valueOf(raw));
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static double doubleValue(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw != null) {
            try {
                return Double.parseDouble(String.valueOf(raw));
            } catch (Exception ignored) {
            }
        }
        return 0d;
    }

    private static boolean boolValue(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw != null && Boolean.parseBoolean(String.valueOf(raw));
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }
}
