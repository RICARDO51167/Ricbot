package ricbot.infra.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public final class HelperUtils {

    // 用于匹配 <think>...</think> 块的正则表达式（不区分大小写，非贪婪模式）
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);
    // 用于匹配以 <think> 开头但未闭合的尾部内容（不区分大小写）
    private static final Pattern TRAILING_THINK = Pattern.compile("^\\s*<think>[\\s\\S]*$", Pattern.CASE_INSENSITIVE);
    // 用于匹配 <thought>...</thought> 块的正则表达式（不区分大小写，非贪婪模式）
    private static final Pattern THOUGHT_BLOCK = Pattern.compile("<thought>[\\s\\S]*?</thought>", Pattern.CASE_INSENSITIVE);
    // 用于匹配以 <thought> 开头但未闭合的尾部内容（不区分大小写）
    private static final Pattern TRAILING_THOUGHT = Pattern.compile("^\\s*<thought>[\\s\\S]*$", Pattern.CASE_INSENSITIVE);
    // 用于匹配文件名中不安全字符的正则表达式（如 <>:"/\|?*）
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[<>:\"/\\\\|?*]");

    // 私有构造函数，防止实例化
    private HelperUtils() {
    }

    /**
     * 移除文本中的 <think> 和 <thought> 标签及其内容
     *
     * @param text 输入文本
     * @return 清理后的文本
     */
    public static String stripThink(String text) {
        // 如果输入为 null，返回空字符串
        if (text == null) {
            return "";
        }
        // 移除完整的 <think> 块
        String out = THINK_BLOCK.matcher(text).replaceAll("");
        // 移除未闭合的尾部 <think> 内容
        out = TRAILING_THINK.matcher(out).replaceAll("");
        // 移除完整的 <thought> 块
        out = THOUGHT_BLOCK.matcher(out).replaceAll("");
        // 移除未闭合的尾部 <thought> 内容
        out = TRAILING_THOUGHT.matcher(out).replaceAll("");
        // 去除首尾空白并返回
        return out.trim();
    }

    /**
     * 确保指定路径的目录存在，如果不存在则创建
     *
     * @param path 目录路径
     * @return 目录路径
     */
    public static Path ensureDir(Path path) {
        try {
            // 创建目录（包括父目录）
            Files.createDirectories(path);
            return path;
        } catch (IOException e) {
            // 如果创建失败，抛出运行时异常
            throw new RuntimeException("Failed to create directory: " + path, e);
        }
    }

    /**
     * 获取指定时区的当前时间格式化字符串
     *
     * @param timezone 时区 ID
     * @return 格式化后的时间字符串
     */
    public static String currentTimeStr(String timezone) {
        // 根据时区参数获取当前时间
        ZonedDateTime now = timezone != null && !timezone.isBlank()
                ? ZonedDateTime.now(java.time.ZoneId.of(timezone))
                : ZonedDateTime.now();
        // 格式化时间并附加时区信息
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)")) +
                " (" + (timezone != null ? timezone : now.getZone()) + ", UTC" +
                now.getOffset().getId().replace("Z", "+00:00") + ")";
    }

    /**
     * 将文件名中的不安全字符替换为下划线
     *
     * @param name 原始文件名
     * @return 安全的文件名
     */
    public static String safeFilename(String name) {
        return UNSAFE_CHARS.matcher(name).replaceAll("_").trim();
    }

    /**
     * 截断文本到指定最大字符数
     *
     * @param text    原始文本
     * @param maxChars 最大字符数
     * @return 截断后的文本
     */
    public static String truncateText(String text, int maxChars) {
        // 如果文本为 null，返回空字符串
        if (text == null) {
            return "";
        }
        // 如果最大字符数无效或文本长度未超过限制，直接返回原文本
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        // 截断文本并添加省略提示
        return text.substring(0, maxChars) + "\n... (truncated)";
    }

}
