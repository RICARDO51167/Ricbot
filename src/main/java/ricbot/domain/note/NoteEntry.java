package ricbot.domain.note;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record NoteEntry(
        String id,
        String title,
        String category,
        String type,
        String path,
        List<String> tags,
        String createdAt,
        String updatedAt,
        boolean archived
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("title", title);
        out.put("category", category);
        out.put("type", type);
        out.put("path", path);
        out.put("tags", tags != null ? tags : List.of());
        out.put("created_at", createdAt);
        out.put("updated_at", updatedAt);
        out.put("archived", archived);
        return out;
    }

    public static NoteEntry fromMap(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        return new NoteEntry(
                string(raw.get("id")),
                string(raw.get("title")),
                normalizeCategory(string(raw.get("category"))),
                normalizeType(string(raw.get("type"))),
                string(raw.get("path")),
                stringList(raw.get("tags")),
                string(raw.get("created_at")),
                string(raw.get("updated_at")),
                bool(raw.get("archived"))
        );
    }

    public NoteEntry withPath(String nextPath) {
        return new NoteEntry(id, title, category, type, nextPath, tags, createdAt, Instant.now().toString(), archived);
    }

    public NoteEntry withArchived(boolean nextArchived) {
        return new NoteEntry(id, title, category, type, path, tags, createdAt, Instant.now().toString(), nextArchived);
    }

    static String normalizeCategory(String value) {
        String normalized = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "project", "tasks", "blockers", "temporary", "archive" -> normalized;
            case "task" -> "tasks";
            case "blocker" -> "blockers";
            case "temp" -> "temporary";
            default -> "tasks";
        };
    }

    static String normalizeType(String value) {
        String normalized = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "task_state", "conclusion", "blocker", "action", "reference", "decision", "note" -> normalized;
            default -> "note";
        };
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static boolean bool(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw != null && Boolean.parseBoolean(String.valueOf(raw));
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }
}
