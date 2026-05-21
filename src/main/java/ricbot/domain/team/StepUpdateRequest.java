package ricbot.domain.team;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record StepUpdateRequest(
        String targetPath,
        String oldText,
        String newText,
        String command,
        String reason,
        List<String> suggestedTests,
        String updateReason
) {
    public StepUpdateRequest {
        targetPath = clean(targetPath);
        oldText = oldText != null ? oldText : null;
        newText = newText != null ? newText : null;
        command = clean(command);
        reason = clean(reason);
        suggestedTests = copy(suggestedTests);
        updateReason = clean(updateReason);
    }

    public static StepUpdateRequest empty() {
        return new StepUpdateRequest("", null, null, "", "", List.of(), "");
    }

    public static StepUpdateRequest fromMap(Map<String, Object> raw) {
        Map<String, Object> safe = raw != null ? raw : Map.of();
        return new StepUpdateRequest(
                string(safe.get("targetPath")),
                nullableString(safe.get("oldText")),
                nullableString(safe.get("newText")),
                string(safe.get("command")),
                string(safe.get("reason")),
                stringList(safe.get("suggestedTests")),
                string(safe.get("updateReason"))
        );
    }

    public List<String> updatedFields() {
        List<String> out = new ArrayList<>();
        if (!targetPath.isBlank()) out.add("targetPath");
        if (oldText != null) out.add("oldText");
        if (newText != null) out.add("newText");
        if (!command.isBlank()) out.add("command");
        if (!reason.isBlank()) out.add("reason");
        if (!suggestedTests.isEmpty()) out.add("suggestedTests");
        if (!updateReason.isBlank()) out.add("updateReason");
        return List.copyOf(out);
    }

    private static String nullableString(Object raw) {
        return raw != null ? String.valueOf(raw) : null;
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw).trim() : "";
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String value = string(item);
                if (!value.isBlank()) {
                    out.add(value);
                }
            }
        } else {
            String value = string(raw);
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> copy(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values != null ? values : List.<String>of()) {
            String cleaned = clean(value);
            if (!cleaned.isBlank() && !out.contains(cleaned)) {
                out.add(cleaned);
            }
        }
        return List.copyOf(out);
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
