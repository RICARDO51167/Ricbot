package ricbot.domain.workspace;

import java.util.List;
import java.util.Locale;

public final class RuntimeArtifactFilter {
    private static final List<String> RUNTIME_ROOTS = List.of(
            "notes/",
            ".team/",
            ".traces/",
            ".ricbot/",
            ".workspaces/",
            "target/",
            "logs/"
    );

    private RuntimeArtifactFilter() {
    }

    public static boolean isRuntimeArtifact(String rawPath) {
        String path = normalize(rawPath);
        if (path.isBlank()) {
            return true;
        }
        if ("session.json".equals(path)) {
            return true;
        }
        for (String root : RUNTIME_ROOTS) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String rawPath) {
        String value = rawPath != null ? rawPath.trim() : "";
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        return value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
