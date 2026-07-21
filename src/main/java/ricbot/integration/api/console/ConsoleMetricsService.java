package ricbot.integration.api.console;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ConsoleMetricsService {
    private final List<ConsoleEvent> events;

    public ConsoleMetricsService(List<ConsoleEvent> events) {
        this.events = events != null ? List.copyOf(events) : List.of();
    }

    public Map<String, Object> summary(ConsoleMetricsQuery query) {
        String sessionId = clean(query != null ? query.sessionId() : "");
        Instant since = parseInstant(query != null ? query.since() : "");
        Instant until = parseInstant(query != null ? query.until() : "");
        List<ConsoleEvent> filtered = events.stream()
                .filter(event -> sessionId.isBlank() || sessionId.equals(event.sessionId()))
                .filter(event -> within(event, since, until))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope(sessionId, query != null ? query.since() : "", query != null ? query.until() : ""));
        out.put("runs", runs(filtered));
        out.put("events", eventCounts(filtered));
        out.put("tools", tools(filtered));
        out.put("approvals", approvals(filtered));
        out.put("changesets", changesets(filtered));
        out.put("recentErrors", recentErrors(filtered));
        out.put("activeSessions", activeSessions(filtered));
        return out;
    }

    private Map<String, Object> scope(String sessionId, String since, String until) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("since", clean(since));
        out.put("until", clean(until));
        return out;
    }

    private Map<String, Object> runs(List<ConsoleEvent> events) {
        Map<String, RunStats> runs = new LinkedHashMap<>();
        for (ConsoleEvent event : events) {
            if (event.runId().isBlank()) {
                continue;
            }
            runs.computeIfAbsent(event.runId(), RunStats::new).accept(event);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", runs.size());
        out.put("queued", countRuns(runs, "queued"));
        out.put("running", countRuns(runs, "running"));
        out.put("finished", countRuns(runs, "finished"));
        out.put("failed", countRuns(runs, "failed"));
        out.put("cancelled", countRuns(runs, "cancelled"));
        out.put("successRate", runs.isEmpty() ? 0.0 : round2((double) countRuns(runs, "finished") / runs.size()));
        out.put("avgDurationMs", averageDuration(runs));
        return out;
    }

    private Map<String, Object> eventCounts(List<ConsoleEvent> events) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", events.size());
        for (String category : List.of("run", "tool", "approval", "changeset", "error", "system")) {
            out.put(category, events.stream().filter(event -> category.equals(event.category())).count());
        }
        return out;
    }

    private Map<String, Object> tools(List<ConsoleEvent> events) {
        Map<String, ToolStats> byName = new LinkedHashMap<>();
        int total = 0;
        int errors = 0;
        for (ConsoleEvent event : events) {
            if (!"tool".equals(event.category())) {
                continue;
            }
            total++;
            boolean error = isError(event);
            if (error) {
                errors++;
            }
            String name = firstNonBlank(event.payload().get("toolName"), event.payload().get("tool_name"), event.payload().get("name"), event.name(), "tool");
            byName.computeIfAbsent(name, ToolStats::new).accept(error);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalCalls", total);
        out.put("errorCount", errors);
        out.put("topTools", byName.values().stream()
                .sorted(Comparator.comparingInt(ToolStats::count).reversed().thenComparing(ToolStats::name))
                .limit(10)
                .map(ToolStats::toMap)
                .toList());
        return out;
    }

    private Map<String, Object> approvals(List<ConsoleEvent> events) {
        int total = 0;
        int approveOnly = 0;
        int approveExecute = 0;
        int reject = 0;
        for (ConsoleEvent event : events) {
            if (!"approval".equals(event.category())) {
                continue;
            }
            total++;
            String text = (event.name() + " " + event.summary() + " " + event.payload()).toLowerCase(Locale.ROOT);
            if (text.contains("approve_execute") || text.contains("approve-execute")) {
                approveExecute++;
            } else if (text.contains("approve_only") || text.contains("approve-only")) {
                approveOnly++;
            } else if (text.contains("reject")) {
                reject++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("approveOnly", approveOnly);
        out.put("approveExecute", approveExecute);
        out.put("reject", reject);
        return out;
    }

    private Map<String, Object> changesets(List<ConsoleEvent> events) {
        int total = 0;
        int diffViews = 0;
        int fileDiffViews = 0;
        for (ConsoleEvent event : events) {
            if (!"changeset".equals(event.category())) {
                continue;
            }
            total++;
            String name = event.name().toLowerCase(Locale.ROOT);
            if (name.contains("file") && name.contains("diff")) {
                fileDiffViews++;
            } else if (name.contains("diff")) {
                diffViews++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("diffViews", diffViews);
        out.put("fileDiffViews", fileDiffViews);
        return out;
    }

    private List<Map<String, Object>> recentErrors(List<ConsoleEvent> events) {
        return events.stream()
                .filter(ConsoleMetricsService::isError)
                .sorted(Comparator.comparing(ConsoleMetricsService::timeSortKey).reversed())
                .limit(10)
                .map(event -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", event.id());
                    out.put("sessionId", event.sessionId());
                    out.put("runId", event.runId());
                    out.put("time", event.time());
                    out.put("name", event.name());
                    out.put("summary", event.summary());
                    return out;
                })
                .toList();
    }

    private List<Map<String, Object>> activeSessions(List<ConsoleEvent> events) {
        Map<String, SessionStats> sessions = new LinkedHashMap<>();
        for (ConsoleEvent event : events) {
            if (event.sessionId().isBlank()) {
                continue;
            }
            sessions.computeIfAbsent(event.sessionId(), SessionStats::new).accept(event);
        }
        return sessions.values().stream()
                .sorted(Comparator.comparing(SessionStats::lastEventAtSortKey).reversed())
                .limit(10)
                .map(SessionStats::toMap)
                .toList();
    }

    private static long countRuns(Map<String, RunStats> runs, String status) {
        return runs.values().stream().filter(run -> status.equals(run.status)).count();
    }

    private static long averageDuration(Map<String, RunStats> runs) {
        List<Long> durations = runs.values().stream()
                .map(RunStats::durationMs)
                .filter(value -> value > 0)
                .toList();
        if (durations.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (long value : durations) {
            sum += value;
        }
        return Math.round((double) sum / durations.size());
    }

    private static boolean within(ConsoleEvent event, Instant since, Instant until) {
        Instant time = parseInstant(event.time());
        if (since != null && time != null && time.isBefore(since)) {
            return false;
        }
        if (until != null && time != null && time.isAfter(until)) {
            return false;
        }
        return true;
    }

    private static boolean isError(ConsoleEvent event) {
        String status = event.status().toLowerCase(Locale.ROOT);
        String name = event.name().toLowerCase(Locale.ROOT);
        return "error".equals(event.category()) || status.contains("error") || status.contains("fail") || name.contains("error") || name.contains("failed");
    }

    private static String timeSortKey(ConsoleEvent event) {
        return event != null && event.time() != null && !event.time().isBlank() ? event.time() : "";
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
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

    private static String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String text = value != null ? String.valueOf(value).trim() : "";
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static final class RunStats {
        private String status = "queued";
        private String createdAt = "";
        private String startedAt = "";
        private String finishedAt = "";

        private RunStats(String runId) {
        }

        private void accept(ConsoleEvent event) {
            if (createdAt.isBlank()) {
                createdAt = event.time();
            }
            String name = event.name().toLowerCase(Locale.ROOT);
            if ("run_started".equals(name) || "run_start".equals(name)) {
                status = "running";
                startedAt = event.time();
            } else if ("run_finished".equals(name) || "run_finish".equals(name)) {
                status = "finished";
                finishedAt = event.time();
            } else if ("run_failed".equals(name) || name.contains("error")) {
                status = "failed";
                finishedAt = event.time();
            } else if ("run_cancelled".equals(name)) {
                status = "cancelled";
                finishedAt = event.time();
            } else if ("run_submit".equals(name) || "run_queued".equals(name)) {
                createdAt = firstNonBlank(createdAt, event.time());
            }
        }

        private long durationMs() {
            Instant start = parseInstant(!startedAt.isBlank() ? startedAt : createdAt);
            Instant finish = parseInstant(finishedAt);
            if (start == null || finish == null || finish.isBefore(start)) {
                return 0L;
            }
            return Duration.between(start, finish).toMillis();
        }
    }

    private static final class ToolStats {
        private final String name;
        private int count;
        private int errorCount;

        private ToolStats(String name) {
            this.name = name;
        }

        private void accept(boolean error) {
            count++;
            if (error) {
                errorCount++;
            }
        }

        private String name() {
            return name;
        }

        private int count() {
            return count;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("count", count);
            out.put("errorCount", errorCount);
            return out;
        }
    }

    private static final class SessionStats {
        private final String sessionId;
        private int eventCount;
        private final Set<String> runIds = new LinkedHashSet<>();
        private String lastEventAt = "";

        private SessionStats(String sessionId) {
            this.sessionId = sessionId;
        }

        private void accept(ConsoleEvent event) {
            eventCount++;
            if (!event.runId().isBlank()) {
                runIds.add(event.runId());
            }
            if (event.time().compareTo(lastEventAt) > 0) {
                lastEventAt = event.time();
            }
        }

        private String lastEventAtSortKey() {
            return lastEventAt;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sessionId", sessionId);
            out.put("eventCount", eventCount);
            out.put("runCount", runIds.size());
            out.put("lastEventAt", lastEventAt);
            return out;
        }
    }
}
