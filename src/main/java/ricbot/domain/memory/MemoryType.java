package ricbot.domain.memory;

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

}
