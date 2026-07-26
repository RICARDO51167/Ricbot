package ricbot.domain.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.runtime.RuntimeDigest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Non-destructive, tool-pair-aware context compaction policy. */
public final class ContextCompactor {
    public static final double TRIGGER_RATIO = 0.80d;
    public static final double TARGET_RATIO = 0.60d;
    public static final int RECENT_MESSAGES = 8;
    public static final String MESSAGE_ID = "_ricbot_message_id";
    public static final String MESSAGE_MARKS = "_ricbot_marks";

    @FunctionalInterface
    public interface SummaryGenerator {
        StructuredContextSummary summarize(List<Map<String, Object>> messages, String prompt) throws Exception;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public ContextCompactionResult compact(List<Map<String, Object>> input, int availableInputTokens,
                                           String model, SummaryGenerator generator) {
        List<Map<String, Object>> messages = normalize(input);
        int currentTokens = estimate(messages);
        if (availableInputTokens <= 0 || currentTokens < availableInputTokens * TRIGGER_RATIO) {
            return unchanged(messages, model, currentTokens);
        }
        Set<Integer> preserved = preservedIndexes(messages);
        List<Integer> candidates = new ArrayList<>();
        int activeTokens = currentTokens;
        int target = (int) Math.floor(availableInputTokens * TARGET_RATIO);
        for (int index = 0; index < messages.size() && activeTokens > target; index++) {
            if (preserved.contains(index)) continue;
            candidates.add(index);
            activeTokens -= estimate(messages.get(index));
        }
        if (candidates.isEmpty()) return unchanged(messages, model, currentTokens);

        List<Map<String, Object>> source = candidates.stream().map(messages::get).toList();
        String prompt = prompt(source);
        StructuredContextSummary summary = null;
        boolean degraded = false;
        if (generator != null) {
            for (int attempt = 0; attempt < 2 && summary == null; attempt++) {
                try { summary = generator.summarize(source, prompt); }
                catch (Exception ignored) { /* The second failure selects the deterministic fallback. */ }
            }
        }
        if (summary == null) {
            degraded = true;
            summary = conservativeSummary(source);
        }

        Set<Integer> compressed = Set.copyOf(candidates);
        List<Map<String, Object>> marked = new ArrayList<>();
        List<Map<String, Object>> active = new ArrayList<>();
        List<String> sourceIds = new ArrayList<>();
        int firstCompressed = candidates.get(0);
        Map<String, Object> summaryMessage = summaryMessage(summary, source, model, degraded);
        for (int index = 0; index < messages.size(); index++) {
            if (index == firstCompressed) {
                marked.add(summaryMessage);
                active.add(summaryMessage);
            }
            Map<String, Object> message = new LinkedHashMap<>(messages.get(index));
            if (compressed.contains(index)) {
                message.put(MESSAGE_MARKS, List.of(MessageMark.COMPRESSED.name()));
                sourceIds.add(String.valueOf(message.get(MESSAGE_ID)));
            } else {
                message.put(MESSAGE_MARKS, List.of(preserved.contains(index)
                        ? MessageMark.PRESERVED.name() : MessageMark.ACTIVE.name()));
                active.add(message);
            }
            marked.add(message);
        }
        int resultTokens = estimate(active);
        return new ContextCompactionResult(true, degraded, marked, active, sourceIds, clean(model),
                currentTokens, resultTokens, RuntimeDigest.sha256(prompt), RuntimeDigest.sha256(summary));
    }

    public static boolean compressed(Map<String, Object> message) {
        Object marks = message != null ? message.get(MESSAGE_MARKS) : null;
        return marks instanceof List<?> list && list.stream().map(String::valueOf)
                .anyMatch(MessageMark.COMPRESSED.name()::equals);
    }

    private static List<Map<String, Object>> normalize(List<Map<String, Object>> input) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> original : input != null ? input : List.<Map<String, Object>>of()) {
            Map<String, Object> message = new LinkedHashMap<>(original != null ? original : Map.of());
            message.putIfAbsent(MESSAGE_ID, "msg-" + UUID.randomUUID());
            message.putIfAbsent(MESSAGE_MARKS, List.of(MessageMark.ACTIVE.name()));
            normalized.add(message);
        }
        return normalized;
    }

    private static Set<Integer> preservedIndexes(List<Map<String, Object>> messages) {
        Set<Integer> preserved = new LinkedHashSet<>();
        for (int index = Math.max(0, messages.size() - RECENT_MESSAGES); index < messages.size(); index++) {
            preserved.add(index);
        }
        boolean changed;
        do {
            changed = false;
            for (int index = 0; index < messages.size(); index++) {
                Set<String> calls = toolCallIds(messages.get(index));
                String resultFor = toolResultId(messages.get(index));
                if (preserved.contains(index)) {
                    if (!calls.isEmpty()) {
                        for (int other = 0; other < messages.size(); other++) {
                            if (calls.contains(toolResultId(messages.get(other)))) changed |= preserved.add(other);
                        }
                    }
                    if (!resultFor.isBlank()) {
                        for (int other = 0; other < messages.size(); other++) {
                            if (toolCallIds(messages.get(other)).contains(resultFor)) changed |= preserved.add(other);
                        }
                    }
                }
            }
        } while (changed);
        return preserved;
    }

    private static Set<String> toolCallIds(Map<String, Object> message) {
        Set<String> ids = new LinkedHashSet<>();
        Object calls = message.get("tool_calls");
        if (calls instanceof List<?> list) for (Object call : list) {
            if (call instanceof Map<?, ?> map && map.get("id") != null) ids.add(String.valueOf(map.get("id")));
        }
        return ids;
    }

    private static String toolResultId(Map<String, Object> message) {
        return message.get("tool_call_id") != null ? String.valueOf(message.get("tool_call_id")) : "";
    }

    private static Map<String, Object> summaryMessage(StructuredContextSummary summary,
                                                      List<Map<String, Object>> source, String model,
                                                      boolean degraded) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceMessageIds", source.stream().map(message -> message.get(MESSAGE_ID)).toList());
        metadata.put("model", clean(model));
        metadata.put("sourceTokens", estimate(source));
        metadata.put("resultTokens", estimateText(String.valueOf(summary)));
        metadata.put("promptDigest", RuntimeDigest.sha256(prompt(source)));
        metadata.put("resultDigest", RuntimeDigest.sha256(summary));
        metadata.put("degraded", degraded);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "system");
        message.put("content", asJson(summary));
        message.put("context_compaction", metadata);
        message.put(MESSAGE_ID, "compact-" + UUID.randomUUID());
        message.put(MESSAGE_MARKS, List.of(MessageMark.PRESERVED.name()));
        return message;
    }

    private static StructuredContextSummary conservativeSummary(List<Map<String, Object>> source) {
        String first = source.isEmpty() ? "" : bounded(String.valueOf(source.get(0).getOrDefault("content", "")));
        String last = source.isEmpty() ? "" : bounded(String.valueOf(source.get(source.size() - 1).getOrDefault("content", "")));
        return new StructuredContextSummary(first, last, List.of(), List.of("Continue from preserved recent context"),
                source.stream().filter(message -> "tool".equals(message.get("role")))
                        .map(message -> bounded(String.valueOf(message.getOrDefault("content", "")))).toList());
    }

    private static String prompt(List<Map<String, Object>> source) {
        return "Summarize into taskOverview/currentState/importantDiscoveries/nextSteps/contextToPreserve: "
                + asJson(source);
    }

    private static ContextCompactionResult unchanged(List<Map<String, Object>> messages, String model, int tokens) {
        List<Map<String, Object>> active = messages.stream().filter(message -> !compressed(message)).toList();
        return new ContextCompactionResult(false, false, messages, active, List.of(), clean(model), tokens,
                estimate(active), "", "");
    }

    private static int estimate(List<Map<String, Object>> messages) {
        return messages.stream().mapToInt(ContextCompactor::estimate).sum();
    }
    private static int estimate(Map<String, Object> message) { return estimateText(asJson(message)) + 6; }
    private static int estimateText(String value) { return Math.max(1, (value != null ? value.length() : 0) / 4); }
    private static String bounded(String value) { return value.length() <= 800 ? value : value.substring(0, 800); }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
    private static String asJson(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { return String.valueOf(value); }
    }
}
