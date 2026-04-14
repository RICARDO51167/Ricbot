package ricbot.core.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SessionHelper {

    public static Path ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Path getLegacySessionsDir() {
        return Path.of(System.getProperty("user.home"), ".ricbot", "sessions");
    }

    public static String safeFilename(String input) {
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 找到一个合法消息起点。
     * 这里先给最简版本：默认返回 0。
     *
     * 以后你可以继续把 Python 里的 find_legal_message_start 逻辑补进来。
     */
    public static int findLegalMessageStart(List<Map<String, Object>> messages) {
        return 0;
    }
}
