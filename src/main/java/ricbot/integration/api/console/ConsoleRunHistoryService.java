package ricbot.integration.api.console;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ConsoleRunHistoryService {
    private final ConsoleEventStore store;

    public ConsoleRunHistoryService(ConsoleEventStore store) {
        this.store = store;
    }

    public Map<String, Object> history(String sessionId, String statusFilter, String keyword, int limit) {
        int max = limit > 0 ? Math.min(limit, 200) : 50;
        List<ConsoleEvent> events = store != null ? store.listBySession(sessionId, "", "", 10_000) : List.of();
        Map<String, RunAccumulator> runs = new LinkedHashMap<>();
        for (ConsoleEvent event : events) {
            if (event.runId().isBlank()) {
                continue;
            }
            RunAccumulator run = runs.computeIfAbsent(event.runId(), id -> new RunAccumulator(sessionId, id));
            run.accept(event);
        }
        String safeStatus = clean(statusFilter).toLowerCase(Locale.ROOT);
        String safeKeyword = clean(keyword).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> summaries = runs.values().stream()
                .map(RunAccumulator::toMap)
                .filter(summary -> safeStatus.isBlank() || "all".equals(safeStatus)
                        || safeStatus.equals(String.valueOf(summary.get("status")).toLowerCase(Locale.ROOT)))
                .filter(summary -> safeKeyword.isBlank() || summary.toString().toLowerCase(Locale.ROOT).contains(safeKeyword))
                .sorted((left, right) -> String.valueOf(right.get("createdAt")).compareTo(String.valueOf(left.get("createdAt"))))
                .limit(max)
                .toList();
        return Map.of("sessionId", sessionId != null ? sessionId : "", "runs", summaries);
    }

    private static final class RunAccumulator {
        private final String sessionId;
        private final String runId;
        private String status = "queued";
        private String inputPreview = "";
        private String createdAt = "";
        private String startedAt = "";
        private String finishedAt = "";
        private String model = "";
        private int toolCallCount;
        private int approvalCount;
        private int changeSetCount;
        private int errorCount;
        private String lastEventName = "";
        private String lastEventSummary = "";

        private RunAccumulator(String sessionId, String runId) {
            this.sessionId = sessionId;
            this.runId = runId;
        }

        private void accept(ConsoleEvent event) {
            if (createdAt.isBlank()) {
                createdAt = event.time();
            }
            lastEventName = event.name();
            lastEventSummary = event.summary();
            String name = event.name().toLowerCase(Locale.ROOT);
            Map<String, Object> payload = event.payload();
            inputPreview = firstNonBlank(inputPreview, payload.get("inputPreview"), nested(payload.get("run"), "inputPreview"), payload.get("input"));
            model = firstNonBlank(model, payload.get("model"), nested(payload.get("run"), "model"));
            if ("run_submit".equals(name) || "run_queued".equals(name)) {
                status = "queued";
                createdAt = firstNonBlank(createdAt, event.time());
            } else if ("run_started".equals(name) || "run_start".equals(name)) {
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
            }
            if ("tool".equals(event.category())) {
                toolCallCount++;
            } else if ("approval".equals(event.category())) {
                approvalCount++;
            } else if ("changeset".equals(event.category())) {
                changeSetCount++;
            }
            if ("error".equals(event.category()) || event.status().toLowerCase(Locale.ROOT).contains("error")) {
                errorCount++;
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", runId);
            out.put("sessionId", sessionId);
            out.put("status", status);
            out.put("inputPreview", inputPreview);
            out.put("createdAt", createdAt);
            out.put("startedAt", startedAt);
            out.put("finishedAt", finishedAt);
            out.put("durationMs", durationMs());
            out.put("model", model);
            out.put("toolCallCount", toolCallCount);
            out.put("approvalCount", approvalCount);
            out.put("changeSetCount", changeSetCount);
            out.put("errorCount", errorCount);
            out.put("lastEventName", lastEventName);
            out.put("lastEventSummary", lastEventSummary);
            return out;
        }

        private long durationMs() {
            Instant start = parseInstant(!startedAt.isBlank() ? startedAt : createdAt);
            Instant finish = parseInstant(finishedAt);
            if (start == null || finish == null || finish.isBefore(start)) {
                return 0L;
            }
            return Duration.between(start, finish).toMillis();
        }

        private static Object nested(Object raw, String key) {
            if (raw instanceof Map<?, ?> map) {
                return map.get(key);
            }
            return "";
        }
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
}
