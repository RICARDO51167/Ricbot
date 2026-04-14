package ricbot.infra.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 对应 Python: paths.py / runtime path helpers
 */
public final class RuntimePaths {

    private RuntimePaths() {
    }

    public static Path getDataDir() {
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot");
        ensureDir(dir);
        return dir;
    }

    public static Path getRuntimeSubdir(String name) {
        Path dir = getDataDir().resolve(name);
        ensureDir(dir);
        return dir;
    }

    public static Path getMediaDir() {
        return getRuntimeSubdir("media");
    }

    public static Path getMediaDir(String channel) {
        if (channel == null || channel.isBlank()) {
            return getMediaDir();
        }
        Path dir = getMediaDir().resolve(channel);
        ensureDir(dir);
        return dir;
    }

    public static Path getCronDir() {
        return getRuntimeSubdir("cron");
    }

    public static Path getLogsDir() {
        return getRuntimeSubdir("logs");
    }

    public static Path getWorkspacePath(String workspace) {
        Path path;
        if (workspace == null || workspace.isBlank()) {
            path = Path.of(System.getProperty("user.home"), ".ricbot", "workspace");
        } else {
            path = Path.of(workspace);
        }
        ensureDir(path);
        return path.toAbsolutePath().normalize();
    }

    public static boolean isDefaultWorkspace(String workspace) {
        Path current = getWorkspacePath(workspace);
        Path def = getWorkspacePath(null);
        return current.equals(def);
    }

    public static Path getCliHistoryPath() {
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot", "history");
        ensureDir(dir);
        return dir.resolve("cli_history");
    }

    public static Path getBridgeInstallDir() {
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot", "bridge");
        ensureDir(dir);
        return dir;
    }

    public static Path getLegacySessionsDir() {
        Path dir = Path.of(System.getProperty("user.home"), ".ricbot", "sessions");
        ensureDir(dir);
        return dir;
    }

    private static void ensureDir(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create directory: " + path, e);
        }
    }
}
