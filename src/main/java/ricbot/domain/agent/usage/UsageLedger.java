package ricbot.domain.agent.usage;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable cumulative Run usage. All counters use long to avoid multi-round overflow. */
public record UsageLedger(long inputTokens, long outputTokens, long totalTokens,
                          long modelCalls, long compressionCalls, long repairCalls,
                          long toolCalls, long activeMillis, long costMicrousd,
                          boolean costKnown, Map<String, ModelUsage> models) {
    public UsageLedger {
        models = Map.copyOf(models != null ? models : Map.of());
    }

    public static UsageLedger empty() {
        return new UsageLedger(0, 0, 0, 0, 0, 0, 0, 0, 0, true, Map.of());
    }

    public UsageLedger plus(UsageDelta delta) {
        if (delta == null) return this;
        Map<String, ModelUsage> next = new LinkedHashMap<>(models);
        if (!delta.model().isBlank()) {
            next.merge(delta.model(), new ModelUsage(delta.inputTokens(), delta.outputTokens(),
                    delta.totalTokens(), delta.modelCalls() + delta.compressionCalls() + delta.repairCalls(),
                    delta.costMicrousd()), ModelUsage::plus);
        }
        return new UsageLedger(add(inputTokens, delta.inputTokens()), add(outputTokens, delta.outputTokens()),
                add(totalTokens, delta.totalTokens()), add(modelCalls, delta.modelCalls()),
                add(compressionCalls, delta.compressionCalls()), add(repairCalls, delta.repairCalls()),
                add(toolCalls, delta.toolCalls()), add(activeMillis, delta.activeMillis()),
                add(costMicrousd, delta.costMicrousd()), costKnown && delta.costKnown(), next);
    }

    public UsageLedger plus(UsageLedger other) {
        UsageLedger result = this;
        if (other == null) return result;
        result = result.plus(new UsageDelta(other.inputTokens, other.outputTokens, other.totalTokens,
                other.modelCalls, other.compressionCalls, other.repairCalls, other.toolCalls,
                other.activeMillis, other.costMicrousd, "", other.costKnown));
        Map<String, ModelUsage> merged = new LinkedHashMap<>(result.models);
        other.models.forEach((key, value) -> merged.merge(key, value, ModelUsage::plus));
        return new UsageLedger(result.inputTokens, result.outputTokens, result.totalTokens, result.modelCalls,
                result.compressionCalls, result.repairCalls, result.toolCalls, result.activeMillis,
                result.costMicrousd, result.costKnown, merged);
    }

    public static UsageLedger from(Object raw) {
        if (raw instanceof UsageLedger ledger) return ledger;
        if (raw instanceof UsageDelta delta) return empty().plus(delta);
        if (!(raw instanceof Map<?, ?> map)) return empty();
        if (map.containsKey("prompt_tokens") || map.containsKey("completion_tokens")) {
            return empty().plus(new UsageDelta(number(map, "prompt_tokens"), number(map, "completion_tokens"),
                    number(map, "total_tokens"), 1, 0, 0, 0, 0, 0, "", false));
        }
        Map<String, ModelUsage> models = new LinkedHashMap<>();
        Object modelValue = map.get("models");
        if (modelValue instanceof Map<?, ?> modelMap) modelMap.forEach((key, value) -> {
            if (value instanceof Map<?, ?> details) models.put(String.valueOf(key), new ModelUsage(
                    number(details, "inputTokens"), number(details, "outputTokens"),
                    number(details, "totalTokens"), number(details, "calls"), number(details, "costMicrousd")));
        });
        return new UsageLedger(number(map, "inputTokens"), number(map, "outputTokens"),
                number(map, "totalTokens"), number(map, "modelCalls"), number(map, "compressionCalls"),
                number(map, "repairCalls"), number(map, "toolCalls"), number(map, "activeMillis"),
                number(map, "costMicrousd"), !Boolean.FALSE.equals(map.get("costKnown")), models);
    }

    public Map<String, Integer> legacyUsage() {
        return Map.of("prompt_tokens", integer(inputTokens), "completion_tokens", integer(outputTokens),
                "total_tokens", integer(totalTokens));
    }

    private static int integer(long value) { return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value)); }
    private static long add(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }
    private static long number(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    public record ModelUsage(long inputTokens, long outputTokens, long totalTokens, long calls,
                             long costMicrousd) {
        private ModelUsage plus(ModelUsage other) {
            return new ModelUsage(add(inputTokens, other.inputTokens), add(outputTokens, other.outputTokens),
                    add(totalTokens, other.totalTokens), add(calls, other.calls),
                    add(costMicrousd, other.costMicrousd));
        }
    }
}
