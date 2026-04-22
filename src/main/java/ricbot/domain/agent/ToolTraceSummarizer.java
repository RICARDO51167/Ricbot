package ricbot.domain.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ToolTraceSummarizer {

    List<Map<String, Object>> summarize(List<Map<String, Object>> toolEvents) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (toolEvents == null) {
            return out;
        }
        for (Map<String, Object> event : toolEvents) {
            if (event == null) {
                continue;
            }
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("tool_name", stringValue(event.get("name")));
            trace.put("status", stringValue(event.get("status")));
            trace.put("arguments_summary", abbreviate(stringValue(event.get("arguments_summary")), 180));
            trace.put("result_summary", abbreviate(stringValue(event.get("result_summary")), 220));
            trace.put("detail", abbreviate(stringValue(event.get("detail")), 220));
            trace.put("duration_ms", event.get("duration_ms"));
            trace.put("truncated", Boolean.TRUE.equals(event.get("truncated")));
            trace.put("executed_at", event.getOrDefault("executed_at", Instant.now().toString()));
            out.add(trace);
        }
        return out;
    }

    List<String> renderRecent(List<Map<String, Object>> traces, int maxItems) {
        List<String> rendered = new ArrayList<>();
        if (traces == null || maxItems <= 0) {
            return rendered;
        }
        int start = Math.max(0, traces.size() - maxItems);
        for (int i = start; i < traces.size(); i++) {
            Map<String, Object> trace = traces.get(i);
            if (trace == null) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(stringValue(trace.get("tool_name"))).append(": ").append(stringValue(trace.get("status")));
            String args = stringValue(trace.get("arguments_summary"));
            if (!args.isBlank()) {
                sb.append(" | args=").append(args);
            }
            String result = stringValue(trace.get("result_summary"));
            if (!result.isBlank()) {
                sb.append(" | result=").append(result);
            }
            if (Boolean.TRUE.equals(trace.get("truncated"))) {
                sb.append(" | truncated=true");
            }
            rendered.add(sb.toString());
        }
        return rendered;
    }

    private static String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }
}
