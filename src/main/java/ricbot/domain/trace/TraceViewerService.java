package ricbot.domain.trace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a diagnostic timeline exclusively from trace projections of runtime events. */
public final class TraceViewerService {
    private final TraceStore traces;

    public TraceViewerService(Path workspace) {
        traces = new TraceStore(workspace.toAbsolutePath().normalize());
    }

    public TraceTimeline lastTimeline() {
        TraceStore.TraceSummary latest = traces.loadLatestTrace();
        return latest != null && latest.eventCount() > 0 ? byTrace(latest.traceId()) : empty("No trace found.");
    }

    public TraceTimeline show(String id) {
        String token = clean(id);
        if (token.isBlank()) return empty("No trace found: missing id.");
        if (traces.listTraces().contains(token)) return byTrace(token);
        String sessionTrace = token.startsWith("trace_") ? token : traces.traceIdForSession(token);
        if (!traces.loadEvents(sessionTrace).isEmpty()) return byTrace(sessionTrace);
        for (String traceId : traces.listTraces()) {
            List<TraceEvent> matches = traces.loadEvents(traceId).stream().filter(event -> matches(event, token)).toList();
            if (!matches.isEmpty()) return build(traceId, matches);
        }
        return empty("No trace found for: " + token);
    }

    private TraceTimeline byTrace(String traceId) { return build(traceId, traces.loadEvents(traceId)); }

    private TraceTimeline build(String traceId, List<TraceEvent> source) {
        List<TraceTimelineEvent> events = source.stream().map(this::event)
                .sorted(Comparator.comparing(item -> item.timestamp().isBlank() ? "9999" : item.timestamp())).toList();
        String sessionId = source.stream().map(TraceEvent::sessionId).filter(value -> !value.isBlank()).findFirst().orElse("");
        String taskId = source.stream().map(this::taskId).filter(value -> !value.isBlank()).findFirst().orElse("");
        String status = events.stream().anyMatch(item -> item.severity() == TraceTimelineSeverity.ERROR)
                ? "FAILED" : events.isEmpty() ? "UNKNOWN" : "COMPLETED";
        return new TraceTimeline(traceId, sessionId, taskId,
                events.isEmpty() ? "" : events.get(0).timestamp(),
                events.isEmpty() ? "" : events.get(events.size() - 1).timestamp(),
                status, events, "events=" + events.size() + (taskId.isBlank() ? "" : " task=" + taskId),
                events.isEmpty() ? List.of("no trace events found for trace: " + traceId) : List.of(),
                Map.of(), Map.of(), Map.of());
    }

    private TraceTimelineEvent event(TraceEvent event) {
        Map<String, String> refs = new LinkedHashMap<>();
        put(refs, "sessionId", event.sessionId());
        put(refs, "taskId", taskId(event));
        put(refs, "runId", string(event.payload().get("runId")));
        put(refs, "changeSetId", event.changeSetId());
        return new TraceTimelineEvent(event.createdAt(), event.type().name(), event.type().name(), event.message(),
                TraceTimelineSource.TRACE, severity(event), refs);
    }

    private TraceTimelineSeverity severity(TraceEvent event) {
        String value = (event.type() + " " + event.message() + " " + event.payload()).toLowerCase(java.util.Locale.ROOT);
        if (value.contains("failed") || value.contains("error") || value.contains("reject") || value.contains("denied"))
            return TraceTimelineSeverity.ERROR;
        if (value.contains("blocked") || value.contains("approval") || value.contains("warning"))
            return TraceTimelineSeverity.WARNING;
        return TraceTimelineSeverity.INFO;
    }

    private boolean matches(TraceEvent event, String token) {
        return token.equals(event.sessionId()) || token.equals(taskId(event))
                || token.equals(string(event.payload().get("runId")))
                || token.equals(event.changeSetId()) || token.equals(event.approvalRequestId());
    }

    private String taskId(TraceEvent event) { return string(event.payload().get("taskId")); }
    private TraceTimeline empty(String warning) {
        return new TraceTimeline("", "", "", "", "", "UNKNOWN", List.of(), "No trace found.",
                List.of(warning), Map.of(), Map.of(), Map.of());
    }
    private static void put(Map<String, String> target, String key, String value) {
        String clean = clean(value); if (!clean.isBlank()) target.put(key, clean);
    }
    private static String string(Object value) { return value != null ? String.valueOf(value).trim() : ""; }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
