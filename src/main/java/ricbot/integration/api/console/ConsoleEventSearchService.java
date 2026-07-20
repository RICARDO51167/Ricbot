package ricbot.integration.api.console;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ConsoleEventSearchService {
    private final ConsoleEventStore store;

    public ConsoleEventSearchService(ConsoleEventStore store) {
        this.store = store;
    }

    public Map<String, Object> search(ConsoleEventSearchQuery query) {
        int limit = query != null && query.limit() > 0 ? Math.min(query.limit(), 500) : 100;
        List<ConsoleEvent> all = store != null ? store.listAll(10_000) : List.of();
        Set<String> categories = csv(query != null ? query.category() : "");
        Set<String> statuses = csv(query != null ? query.status() : "");
        String sessionId = clean(query != null ? query.sessionId() : "");
        String runId = clean(query != null ? query.runId() : "");
        String keyword = clean(query != null ? query.keyword() : "").toLowerCase(Locale.ROOT);
        String after = clean(query != null ? query.after() : "");
        Instant since = parseInstant(query != null ? query.since() : "");
        Instant until = parseInstant(query != null ? query.until() : "");

        java.util.ArrayList<ConsoleEvent> matched = collect(all, categories, statuses, sessionId, runId, keyword, after, since, until, limit);
        if (!after.isBlank() && matched.isEmpty() && all.stream().noneMatch(event -> after.equals(event.id()))) {
            matched = collect(all, categories, statuses, sessionId, runId, keyword, "", since, until, limit);
        }

        String nextCursor = matched.isEmpty() ? after : matched.get(matched.size() - 1).id();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", matched.stream().map(ConsoleEvent::toMap).toList());
        out.put("nextCursor", nextCursor);
        return out;
    }

    private static java.util.ArrayList<ConsoleEvent> collect(
            List<ConsoleEvent> all,
            Set<String> categories,
            Set<String> statuses,
            String sessionId,
            String runId,
            String keyword,
            String after,
            Instant since,
            Instant until,
            int limit
    ) {
        java.util.ArrayList<ConsoleEvent> matched = new java.util.ArrayList<>();
        boolean pastCursor = clean(after).isBlank();
        for (ConsoleEvent event : all) {
            if (!pastCursor) {
                if (after.equals(event.id())) {
                    pastCursor = true;
                }
                continue;
            }
            if (!sessionId.isBlank() && !sessionId.equals(event.sessionId())) {
                continue;
            }
            if (!runId.isBlank() && !runId.equals(event.runId())) {
                continue;
            }
            if (!categories.isEmpty() && !categories.contains(event.category().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!statuses.isEmpty() && !statuses.contains(event.status().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Instant time = parseInstant(event.time());
            if (since != null && time != null && time.isBefore(since)) {
                continue;
            }
            if (until != null && time != null && time.isAfter(until)) {
                continue;
            }
            if (!keyword.isBlank() && !searchText(event).contains(keyword)) {
                continue;
            }
            matched.add(event);
            if (matched.size() >= limit) {
                break;
            }
        }
        return matched;
    }

    private static Set<String> csv(String raw) {
        String text = clean(raw).toLowerCase(Locale.ROOT);
        if (text.isBlank() || "all".equals(text)) {
            return Set.of();
        }
        return java.util.Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank() && !"all".equals(value))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static String searchText(ConsoleEvent event) {
        return (event.id() + " "
                + event.sessionId() + " "
                + event.runId() + " "
                + event.type() + " "
                + event.name() + " "
                + event.category() + " "
                + event.status() + " "
                + event.title() + " "
                + event.summary() + " "
                + event.payload()).toLowerCase(Locale.ROOT);
    }

    private static Instant parseInstant(String value) {
        String text = clean(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
