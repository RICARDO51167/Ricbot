package ricbot.domain.trace;

import java.util.LinkedHashMap;
import java.util.Map;

public record TraceTimelineEvent(
        String timestamp,
        String type,
        String title,
        String detail,
        TraceTimelineSource source,
        TraceTimelineSeverity severity,
        Map<String, String> refs
) {
    public TraceTimelineEvent {
        timestamp = clean(timestamp);
        type = clean(type);
        title = clean(title);
        detail = clean(detail);
        source = source != null ? source : TraceTimelineSource.TRACE;
        severity = severity != null ? severity : TraceTimelineSeverity.INFO;
        refs = refs != null ? Map.copyOf(refs) : Map.of();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", timestamp);
        out.put("type", type);
        out.put("title", title);
        out.put("detail", detail);
        out.put("source", source.name());
        out.put("severity", severity.name());
        out.put("refs", refs);
        return out;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
