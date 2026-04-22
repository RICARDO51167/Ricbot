package ricbot.domain.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PromptContextBundle {

    static final List<String> ORDER = List.of(
            "recent_history",
            "task_state",
            "user_profile",
            "memory_recall",
            "tool_trace"
    );

    private final Map<String, List<String>> sections = new LinkedHashMap<>();

    PromptContextBundle() {
        for (String key : ORDER) {
            sections.put(key, new ArrayList<>());
        }
    }

    void addItem(String section, String item) {
        if (section == null || item == null || item.isBlank()) {
            return;
        }
        sections.computeIfAbsent(section, ignored -> new ArrayList<>()).add(item.trim());
    }

    List<String> section(String section) {
        return sections.getOrDefault(section, List.of());
    }

    boolean isEmpty() {
        return sections.values().stream().allMatch(List::isEmpty);
    }

    String render() {
        StringBuilder sb = new StringBuilder();
        for (String key : ORDER) {
            List<String> items = sections.get(key);
            if (items == null || items.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("## ").append(key).append("\n");
            for (String item : items) {
                sb.append("- ").append(item).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
