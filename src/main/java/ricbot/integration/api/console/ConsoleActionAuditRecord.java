package ricbot.integration.api.console;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ConsoleActionAuditRecord(
        String id,
        String timestamp,
        String action,
        String targetType,
        String targetId,
        String result,
        String operator,
        String remoteAddress,
        String userAgent,
        String message,
        List<String> warnings,
        String requestId
) {
    public ConsoleActionAuditRecord {
        id = clean(id).isBlank() ? "console_action_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : clean(id);
        timestamp = clean(timestamp).isBlank() ? Instant.now().toString() : clean(timestamp);
        action = clean(action);
        targetType = clean(targetType);
        targetId = clean(targetId);
        result = clean(result);
        operator = clean(operator);
        remoteAddress = clean(remoteAddress);
        userAgent = clean(userAgent);
        message = clean(message);
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
        requestId = clean(requestId).isBlank() ? UUID.randomUUID().toString() : clean(requestId);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("timestamp", timestamp);
        out.put("action", action);
        out.put("targetType", targetType);
        out.put("targetId", targetId);
        out.put("result", result);
        out.put("operator", operator);
        out.put("remoteAddress", remoteAddress);
        out.put("userAgent", userAgent);
        out.put("message", message);
        out.put("warnings", warnings);
        out.put("requestId", requestId);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static ConsoleActionAuditRecord fromMap(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        Object warningsRaw = raw.get("warnings");
        List<String> warnings = warningsRaw instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        return new ConsoleActionAuditRecord(
                string(raw.get("id")),
                string(raw.get("timestamp")),
                string(raw.get("action")),
                string(raw.get("targetType")),
                string(raw.get("targetId")),
                string(raw.get("result")),
                string(raw.get("operator")),
                string(raw.get("remoteAddress")),
                string(raw.get("userAgent")),
                string(raw.get("message")),
                warnings,
                string(raw.get("requestId"))
        );
    }

    private static String string(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
