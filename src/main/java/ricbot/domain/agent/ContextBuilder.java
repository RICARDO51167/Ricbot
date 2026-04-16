package ricbot.domain.agent;

import ricbot.infra.template.PromptTemplates;
import ricbot.integration.llm.api.LLMProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ContextBuilder for assembling LLM messages, injecting runtime context, and managing conversation history.
 */
public class ContextBuilder {

    public static final String RUNTIME_CONTEXT_TAG = "[RUNTIME_CONTEXT]";
    public static final String RUNTIME_CONTEXT_END = "[/RUNTIME_CONTEXT]";

    private static final int MAX_INLINE_IMAGE_BYTES = 2_000_000;

    private final Path workspace;
    private final String timezone;
    private final List<String> disabledSkills;

    public ContextBuilder(Path workspace) {
        this(workspace, null, null);
    }

    public ContextBuilder(Path workspace, String timezone, List<String> disabledSkills) {
        this.workspace = workspace;
        this.timezone = timezone;
        this.disabledSkills = disabledSkills != null ? disabledSkills : new ArrayList<>();
    }

    public String getTimezone() {
        return timezone;
    }

    public List<Map<String, Object>> buildMessages(
            List<Map<String, Object>> history,
            String currentMessage,
            List<String> media,
            String channel,
            String chatId,
            String sessionSummary,
            String currentRole
    ) {
        List<Map<String, Object>> messages = new ArrayList<>();

        String runtime = buildRuntimeContext(channel, chatId, timezone);
        messages.add(systemMessage(buildSystemPrompt(sessionSummary, runtime, channel)));

        if (history != null && !history.isEmpty()) {
            messages.addAll(sanitizeHistory(history));
        }

        if (currentMessage != null) {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("role", currentRole != null ? currentRole : "user");

            Object content = buildUserContent(currentMessage, media);
            current.put("content", content);
            messages.add(current);
        }

        return LLMProvider.sanitizeEmptyContent(messages);
    }

    public static String buildRuntimeContext(String channel, String chatId, String timezone) {
        String tz = timezone != null && !timezone.isBlank() ? timezone : "UTC";

        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
            tz = "UTC";
        }

        String now = ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("now", now);
        payload.put("timezone", tz);
        if (channel != null && !channel.isBlank()) {
            payload.put("channel", channel);
        }
        if (chatId != null && !chatId.isBlank()) {
            payload.put("chat_id", chatId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(RUNTIME_CONTEXT_TAG).append("\n");
        sb.append(payload.toString()).append("\n");
        sb.append(RUNTIME_CONTEXT_END);
        return sb.toString();
    }

    private Map<String, Object> systemMessage(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "system");
        m.put("content", text);
        return m;
    }

    private String buildSystemPrompt(String sessionSummary, String runtimeContext, String channel) {
        Map<String, Object> kwargs = new HashMap<>();
        String workspacePath = workspace != null ? workspace.toAbsolutePath().normalize().toString() : "";
        kwargs.put("workspace_path", workspacePath);
        kwargs.put("runtime", runtimeContext != null ? runtimeContext : "");
        kwargs.put("platform_policy", "");
        kwargs.put("channel", channel != null ? channel : "");
        kwargs.put("disabled_skills", (disabledSkills == null || disabledSkills.isEmpty()) ? "" : String.join(", ", disabledSkills));
        kwargs.put("session_summary", (sessionSummary == null) ? "" : sessionSummary);

        String system;
        try {
            system = PromptTemplates.renderTemplate("agent/identity.md", true, kwargs);
        } catch (Exception e) {
            system = "You are ricbot.";
        }
        return system;
    }

    private Object buildUserContent(String text, List<String> media) {
        List<String> items = media != null ? media : List.of();
        if (items.isEmpty()) {
            return text;
        }

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "text", "text", text));
        for (String ref : items) {
            Map<String, Object> block = buildImageUrlBlock(ref);
            if (block != null) {
                blocks.add(block);
            } else if (ref != null && !ref.isBlank()) {
                blocks.add(Map.of("type", "text", "text", "[media: " + ref + "]"));
            }
        }
        return blocks;
    }

    private Map<String, Object> buildImageUrlBlock(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:")) {
            return Map.of("type", "image_url", "image_url", Map.of("url", trimmed));
        }

        try {
            Path p = Path.of(trimmed);
            if (!Files.exists(p) || !Files.isRegularFile(p)) {
                return null;
            }

            long size = Files.size(p);
            if (size <= 0 || size > MAX_INLINE_IMAGE_BYTES) {
                return null;
            }

            byte[] raw = Files.readAllBytes(p);
            String mime = detectImageMime(raw);
            if (mime == null) {
                return null;
            }

            String b64 = Base64.getEncoder().encodeToString(raw);
            return Map.of("type", "image_url", "image_url", Map.of("url", "data:" + mime + ";base64," + b64));
        } catch (Exception e) {
            return null;
        }
    }

    private static String detectImageMime(byte[] data) {
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

    private List<Map<String, Object>> sanitizeHistory(List<Map<String, Object>> history) {
        int start = findLegalMessageStart(history);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = start; i < history.size(); i++) {
            Map<String, Object> msg = history.get(i);
            if (msg == null) {
                continue;
            }
            Object role = msg.get("role");
            if (!(role instanceof String) || ((String) role).isBlank()) {
                continue;
            }
            if (!msg.containsKey("content") && !"assistant".equals(role)) {
                continue;
            }
            out.add(msg);
        }
        return out;
    }

    private int findLegalMessageStart(List<Map<String, Object>> messages) {
        Set<String> declared = new HashSet<>();
        int start = 0;

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (msg == null) {
                continue;
            }
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
                }
            }
        }
        return start;
    }
}
