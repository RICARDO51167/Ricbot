package ricbot.domain.trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TraceTimeline(
        String traceId,
        String sessionId,
        String taskId,
        String startedAt,
        String endedAt,
        String status,
        List<TraceTimelineEvent> events,
        String summary,
        List<String> warnings,
        Map<String, Object> relatedWorkspace,
        Map<String, Object> relatedChangeSet,
        Map<String, Object> relatedReport
) {
    public TraceTimeline {
        traceId = clean(traceId);
        sessionId = clean(sessionId);
        taskId = clean(taskId);
        startedAt = clean(startedAt);
        endedAt = clean(endedAt);
        status = clean(status).isBlank() ? "UNKNOWN" : status.trim();
        events = events != null ? List.copyOf(events) : List.of();
        summary = clean(summary);
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
        relatedWorkspace = relatedWorkspace != null ? Map.copyOf(relatedWorkspace) : Map.of();
        relatedChangeSet = relatedChangeSet != null ? Map.copyOf(relatedChangeSet) : Map.of();
        relatedReport = relatedReport != null ? Map.copyOf(relatedReport) : Map.of();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traceId", traceId);
        out.put("sessionId", sessionId);
        out.put("taskId", taskId);
        out.put("startedAt", startedAt);
        out.put("endedAt", endedAt);
        out.put("status", status);
        out.put("events", events.stream().map(TraceTimelineEvent::toMap).toList());
        out.put("summary", summary);
        out.put("warnings", warnings);
        out.put("relatedWorkspace", relatedWorkspace);
        out.put("relatedChangeSet", relatedChangeSet);
        out.put("relatedReport", relatedReport);
        return out;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
