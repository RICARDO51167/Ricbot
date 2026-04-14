package ricbot.common.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

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

    public static String detectImageMime(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        if (startsWith(data, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "image/png";
        }
        if (startsWith(data, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(data, "GIF87a".getBytes()) || startsWith(data, "GIF89a".getBytes())) {
            return "image/gif";
        }
        if (startsWith(data, "RIFF".getBytes()) && new String(data, 8, 4).equals("WEBP")) {
            return "image/webp";
        }
        return null;
    }

    public static List<Map<String, Object>> buildImageContentBlocks(byte[] raw, String mime, String path, String label) {
        String b64 = Base64.getEncoder().encodeToString(raw);
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("path", path);

        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", "data:" + mime + ";base64," + b64);

        Map<String, Object> image = new LinkedHashMap<>();
        image.put("type", "image_url");
        image.put("image_url", imageUrl);
        image.put("_meta", meta);

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", label);

        blocks.add(image);
        blocks.add(text);
        return blocks;
    }

    public static Path ensureDir(Path path) {
        try {
            Files.createDirectories(path);
            return path;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + path, e);
        }
    }

    public static String timestamp() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
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

    public static String imagePlaceholderText(String path) {
        return imagePlaceholderText(path, "[image]");
    }

    public static String imagePlaceholderText(String path, String empty) {
        return path != null && !path.isBlank() ? "[image: " + path + "]" : empty;
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

    /**
     * 对齐 helpers.py 里的 find_legal_message_start
     */
    public static int findLegalMessageStart(List<Map<String, Object>> messages) {
        Set<String> declared = new HashSet<>();
        int start = 0;

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            String role = String.valueOf(msg.get("role"));

            if ("assistant".equals(role)) {
                Object toolCallsObj = msg.get("tool_calls");
                if (toolCallsObj instanceof List<?> toolCalls) {
                    for (Object tcObj : toolCalls) {
                        if (tcObj instanceof Map<?, ?> tc) {
                            Object id = tc.get("id");
                            if (id != null) {
                                declared.add(String.valueOf(id));
                            }
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                Object tid = msg.get("tool_call_id");
                if (tid != null && !declared.contains(String.valueOf(tid))) {
                    start = i + 1;
                    declared.clear();
                    for (int j = start; j <= i; j++) {
                        Map<String, Object> prev = messages.get(j);
                        if ("assistant".equals(String.valueOf(prev.get("role")))) {
                            Object tcObj = prev.get("tool_calls");
                            if (tcObj instanceof List<?> toolCalls) {
                                for (Object tcItem : toolCalls) {
                                    if (tcItem instanceof Map<?, ?> tc) {
                                        Object id = tc.get("id");
                                        if (id != null) {
                                            declared.add(String.valueOf(id));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return start;
    }

    public static String stringifyTextBlocks(List<Map<String, Object>> content) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> block : content) {
            if (!"text".equals(block.get("type"))) {
                return null;
            }
            Object text = block.get("text");
            if (!(text instanceof String s)) {
                return null;
            }
            parts.add(s);
        }
        return String.join("\n", parts);
    }

    public static List<String> splitMessage(String content, int maxLen) {
        if (content == null || content.isEmpty()) {
            return List.of("");
        }
        if (maxLen <= 0 || content.length() <= maxLen) {
            return List.of(content);
        }

        List<String> chunks = new ArrayList<>();
        String remaining = content;

        while (remaining.length() > maxLen) {
            int split = remaining.lastIndexOf('\n', maxLen);
            if (split <= 0) {
                split = maxLen;
            }
            chunks.add(remaining.substring(0, split).trim());
            remaining = remaining.substring(split).trim();
        }

        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
        return chunks;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}