package ricbot.domain.memory;

import java.util.List;
import java.util.Locale;

public enum MemoryType {
    WORKING,
    EPISODIC,
    SEMANTIC,
    PERCEPTUAL;

    public static MemoryType fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MemoryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static MemoryType infer(String legacyType, String scope, String source, List<String> tags) {
        if (MemoryEntry.SCOPE_SHORT_TERM.equals(scope)) {
            return WORKING;
        }
        if (contains(tags, "image") || contains(tags, "attachment") || contains(tags, "perceptual")) {
            return PERCEPTUAL;
        }
        String normalizedSource = source != null ? source.toLowerCase(Locale.ROOT) : "";
        if (normalizedSource.contains("tool") || normalizedSource.contains("history") || contains(tags, "episode")) {
            return EPISODIC;
        }
        return SEMANTIC;
    }

    private static boolean contains(List<String> values, String target) {
        if (values == null || target == null) {
            return false;
        }
        for (String value : values) {
            if (target.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
