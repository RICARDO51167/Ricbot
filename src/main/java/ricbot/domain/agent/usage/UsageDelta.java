package ricbot.domain.agent.usage;

import java.util.LinkedHashMap;
import java.util.Map;

/** One durable, additive usage observation. */
public record UsageDelta(long inputTokens, long outputTokens, long totalTokens,
                         long modelCalls, long compressionCalls, long repairCalls,
                         long toolCalls, long activeMillis, long costMicrousd,
                         String model, boolean costKnown) {
    public UsageDelta {
        inputTokens = nonNegative(inputTokens);
        outputTokens = nonNegative(outputTokens);
        totalTokens = nonNegative(totalTokens > 0 ? totalTokens : inputTokens + outputTokens);
        modelCalls = nonNegative(modelCalls);
        compressionCalls = nonNegative(compressionCalls);
        repairCalls = nonNegative(repairCalls);
        toolCalls = nonNegative(toolCalls);
        activeMillis = nonNegative(activeMillis);
        costMicrousd = nonNegative(costMicrousd);
        model = model != null ? model.trim() : "";
    }

    public static UsageDelta model(String model, Map<String, Integer> usage, long activeMillis) {
        Map<String, Integer> safe = usage != null ? usage : Map.of();
        long input = number(safe, "prompt_tokens", "input_tokens");
        long output = number(safe, "completion_tokens", "output_tokens");
        long total = number(safe, "total_tokens");
        return new UsageDelta(input, output, total, 1, 0, 0, 0, activeMillis, 0, model, false);
    }

    public static UsageDelta compression(String model, Map<String, Integer> usage, long activeMillis) {
        UsageDelta value = model(model, usage, activeMillis);
        return new UsageDelta(value.inputTokens, value.outputTokens, value.totalTokens,
                0, 1, 0, 0, activeMillis, value.costMicrousd, model, value.costKnown);
    }

    public static UsageDelta tool(long activeMillis) {
        return new UsageDelta(0, 0, 0, 0, 0, 0, 1, activeMillis, 0, "", true);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("inputTokens", inputTokens);
        value.put("outputTokens", outputTokens);
        value.put("totalTokens", totalTokens);
        value.put("modelCalls", modelCalls);
        value.put("compressionCalls", compressionCalls);
        value.put("repairCalls", repairCalls);
        value.put("toolCalls", toolCalls);
        value.put("activeMillis", activeMillis);
        value.put("costMicrousd", costMicrousd);
        value.put("model", model);
        value.put("costKnown", costKnown);
        return Map.copyOf(value);
    }

    private static long number(Map<String, Integer> usage, String... keys) {
        for (String key : keys) {
            Number value = usage.get(key);
            if (value != null) return nonNegative(value.longValue());
        }
        return 0;
    }

    private static long nonNegative(long value) { return Math.max(0, value); }
}
