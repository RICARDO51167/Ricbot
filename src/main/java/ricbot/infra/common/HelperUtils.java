package ricbot.infra.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 通用工具类，提供字符串处理、文件操作及时间格式化等辅助方法。
 */
public final class HelperUtils {

    private static final Pattern THINK_BLOCK = Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_THINK = Pattern.compile("^\\s*<think>[\\s\\S]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern THOUGHT_BLOCK = Pattern.compile("<thought>[\\s\\S]*?</thought>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_THOUGHT = Pattern.compile("^\\s*<thought>[\\s\\S]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[<>:\"/\\\\|?*]");

    private HelperUtils() {
    }

    public static String stripThink(String text) {
        if (text == null) {
            return "";
        }
        String out = THINK_BLOCK.matcher(text).replaceAll("");
        out = TRAILING_THINK.matcher(out).replaceAll("");
        out = THOUGHT_BLOCK.matcher(out).replaceAll("");
        out = TRAILING_THOUGHT.matcher(out).replaceAll("");
        return out.trim();
    }

    public static Path ensureDir(Path path) {
        try {
            Files.createDirectories(path);
            return path;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + path, e);
        }
    }

    public static String currentTimeStr(String timezone) {
        ZonedDateTime now = timezone != null && !timezone.isBlank()
                ? ZonedDateTime.now(java.time.ZoneId.of(timezone))
                : ZonedDateTime.now();
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)")) +
                " (" + (timezone != null ? timezone : now.getZone()) + ", UTC" +
                now.getOffset().getId().replace("Z", "+00:00") + ")";
    }

    public static String safeFilename(String name) {
        return UNSAFE_CHARS.matcher(name).replaceAll("_").trim();
    }

    public static String truncateText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... (truncated)";
    }

}
