package ricbot.domain.agent;

import ricbot.domain.memory.MemoryEntry;
import ricbot.domain.memory.MemoryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ContextSelectionService {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}_]+");
    private final MemoryStore memoryStore;
    private final ToolTraceSummarizer toolTraceSummarizer;

    ContextSelectionService(MemoryStore memoryStore, ToolTraceSummarizer toolTraceSummarizer) {
        this.memoryStore = memoryStore;
        this.toolTraceSummarizer = toolTraceSummarizer;
    }

    SelectionResult select(
            SessionPreparedInputs preparedInputs,
            List<Map<String, Object>> sessionMessages,
            String currentMessage,
            int historyWindowMessages
    ) {
        TaskState taskState = preparedInputs.taskState();
        List<Map<String, Object>> history = selectHistory(sessionMessages, currentMessage, taskState, historyWindowMessages);
        PromptContextBundle bundle = new PromptContextBundle();

        if (preparedInputs.archivedSummary() != null && !preparedInputs.archivedSummary().isBlank()) {
            bundle.addItem("memory_recall", preparedInputs.archivedSummary());
        }
        if (taskState != null) {
            bundle.addItem("task_state", "goal: " + blankSafe(taskState.goal()));
            if (!taskState.currentStep().isBlank()) {
                bundle.addItem("task_state", "current_step: " + taskState.currentStep());
            }
            if (!taskState.status().isBlank()) {
                bundle.addItem("task_state", "status: " + taskState.status());
            }
            if (!taskState.blockedReason().isBlank()) {
                bundle.addItem("task_state", "blocked_reason: " + taskState.blockedReason());
            }
            if (!taskState.nextAction().isBlank()) {
                bundle.addItem("task_state", "next_action: " + taskState.nextAction());
            }
        }

        List<MemoryEntry> recall = memoryStore.recallMemories(currentMessage, taskState != null ? taskState.goal() : "", 8);
        for (MemoryEntry entry : recall) {
            if (entry.isUserProfile()) {
                bundle.addItem("user_profile", entry.renderLine().substring(2));
            } else {
                bundle.addItem("memory_recall", entry.renderLine().substring(2));
            }
        }

        for (String archived : memoryStore.recallArchivedHistory(currentMessage, 3)) {
            bundle.addItem("recent_history", archived);
        }

        for (String trace : toolTraceSummarizer.renderRecent(preparedInputs.toolTrace(), 4)) {
            bundle.addItem("tool_trace", trace);
        }

        return new SelectionResult(history, bundle);
    }

    private List<Map<String, Object>> selectHistory(
            List<Map<String, Object>> sessionMessages,
            String currentMessage,
            TaskState taskState,
            int historyWindowMessages
    ) {
        List<Map<String, Object>> history = sessionMessages != null ? sessionMessages : List.of();
        if (history.isEmpty() || historyWindowMessages <= 0) {
            return List.of();
        }

        int suffixTarget = Math.min(Math.max(6, historyWindowMessages / 2), history.size());
        int suffixStart = findLegalSuffixStart(history, suffixTarget);
        List<Map<String, Object>> suffix = new ArrayList<>(history.subList(suffixStart, history.size()));
        if (suffix.size() >= historyWindowMessages || suffixStart == 0) {
            return suffix;
        }

        List<Map<String, Object>> older = history.subList(0, suffixStart);
        Set<String> queryTokens = tokenize(currentMessage + " " + (taskState != null ? taskState.goal() : ""));
        List<ScoredMessage> scored = new ArrayList<>();
        for (int i = 0; i < older.size(); i++) {
            Map<String, Object> msg = older.get(i);
            if (msg == null) {
                continue;
            }
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            double score = overlapScore(queryTokens, tokenize(content));
            score += recencyBonus(i, older.size());
            score += roleWeight(role);
            if (score <= 0.25d) {
                continue;
            }
            scored.add(new ScoredMessage(i, score, msg));
        }

        int remaining = Math.max(0, historyWindowMessages - suffix.size());
        List<Map<String, Object>> selectedOlder = scored.stream()
                .sorted(Comparator.comparingDouble(ScoredMessage::score).reversed())
                .limit(remaining)
                .sorted(Comparator.comparingInt(ScoredMessage::index))
                .map(ScoredMessage::message)
                .collect(Collectors.toCollection(ArrayList::new));
        selectedOlder.addAll(suffix);
        return selectedOlder;
    }

    private int findLegalSuffixStart(List<Map<String, Object>> messages, int keepRecent) {
        int baseCut = Math.max(0, messages.size() - keepRecent);
        List<Map<String, Object>> suffix = new ArrayList<>(messages.subList(baseCut, messages.size()));
        int legalStart = 0;
        Set<String> declared = new HashSet<>();
        for (int i = 0; i < suffix.size(); i++) {
            Map<String, Object> msg = suffix.get(i);
            if (msg == null) {
                continue;
            }
            String role = String.valueOf(msg.get("role"));
            if ("assistant".equals(role)) {
                Object toolCallsObj = msg.get("tool_calls");
                if (toolCallsObj instanceof List<?> toolCalls) {
                    for (Object tcObj : toolCalls) {
                        if (tcObj instanceof Map<?, ?> tc && tc.get("id") != null) {
                            declared.add(String.valueOf(tc.get("id")));
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                Object tid = msg.get("tool_call_id");
                if (tid != null && !declared.contains(String.valueOf(tid))) {
                    legalStart = i + 1;
                    declared.clear();
                }
            }
        }
        return baseCut + legalStart;
    }

    private Set<String> tokenize(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String token : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (token.length() >= 2) {
                out.add(token);
            }
        }
        return out;
    }

    private double overlapScore(Set<String> queryTokens, Set<String> contentTokens) {
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
            return 0d;
        }
        long hits = contentTokens.stream().filter(queryTokens::contains).count();
        return (double) hits / Math.max(1d, queryTokens.size());
    }

    private double recencyBonus(int index, int total) {
        if (total <= 0) {
            return 0d;
        }
        return 0.5d * ((double) (index + 1) / (double) total);
    }

    private double roleWeight(String role) {
        if ("user".equals(role)) {
            return 0.6d;
        }
        if ("assistant".equals(role)) {
            return 0.3d;
        }
        if ("tool".equals(role)) {
            return 0.1d;
        }
        return 0d;
    }

    private String blankSafe(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    record SessionPreparedInputs(String archivedSummary, TaskState taskState, List<Map<String, Object>> toolTrace) {
    }

    record SelectionResult(List<Map<String, Object>> history, PromptContextBundle bundle) {
    }

    private record ScoredMessage(int index, double score, Map<String, Object> message) {
    }
}
