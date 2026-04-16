package ricbot.domain.memory;

import ricbot.infra.template.PromptTemplates;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆整合器。
 */
public class Dream {

    private static final Logger log = LoggerFactory.getLogger(Dream.class);
    private final LLMProvider provider;
    private final String model;
    private final MemoryStore store;

    private static final Set<String> SECTION_NAMES = Set.of("MEMORY.MD", "USER.MD", "SOUL.MD");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{2,6})\\s*([^#].*?)\\s*$");
    private static final Pattern FENCE_START = Pattern.compile("^\\s*```\\s*(\\w+)?\\s*$");
    private static final Pattern FENCE_END = Pattern.compile("^\\s*```\\s*$");

    public Dream(LLMProvider provider, String model, MemoryStore store) {
        this.provider = provider;
        this.model = model;
        this.store = store;
    }

    public boolean run() {
        DreamRunResult result = runDetailed();
        return result.updated();
    }

    public DreamRunResult runDetailed() {
        log.info("Dream: 开始记忆整合...");

        try {
            List<Map<String, Object>> newHistory = store.getUnprocessedHistory();
            if (newHistory == null || newHistory.isEmpty()) {
                log.info("Dream: 没有新的历史记录需要处理。");
                return DreamRunResult.noop("no_history");
            }

            int dreamCursorBefore = store.getLastDreamCursor();
            int cursorBefore = store.getLastCursor();
            log.info("Dream: 将处理 {} 条历史记录 (dream_cursor={}/{})", newHistory.size(), dreamCursorBefore, cursorBefore);

            String memoryMd = store.getMemoryMd();
            String userMd = store.getUserMd();
            String soulMd = store.getSoulMd();

            Map<String, Object> kwargs = new HashMap<>();
            kwargs.put("history", formatHistory(newHistory));
            kwargs.put("memory_md", memoryMd);
            kwargs.put("user_md", userMd);
            kwargs.put("soul_md", soulMd);

            String prompt;
            try {
                prompt = PromptTemplates.renderTemplate("agent/dream.md", true, kwargs);
            } catch (Exception e) {
                log.warn("Dream: 模板渲染失败", e);
                return DreamRunResult.noop("template_render_failed");
            }

            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> systemMsg = new LinkedHashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content",
                    "你负责更新三个文件：MEMORY.md / USER.md / SOUL.md。\n"
                            + "输出要求：\n"
                            + "1) 只输出更新结果，不要输出解释。\n"
                            + "2) 使用 Markdown 标题段落输出，标题必须严格为：### MEMORY.md / ### USER.md / ### SOUL.md。\n"
                            + "3) 每个标题下给出对应文件的完整内容（不是 diff）。\n"
                            + "4) 如果无需更新，输出严格为：(nothing)\n"
            );
            messages.add(systemMsg);

            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);

            LLMResponse response = provider.chatWithRetry(messages, List.of(), model);
            String content = response.getContent();

            String normalized = content != null ? content.trim() : "";
            if (normalized.isBlank() || "(nothing)".equalsIgnoreCase(normalized) || "(无内容)".equals(normalized)) {
                log.info("Dream: LLM 未返回任何更新内容。");
                store.markHistoryAsProcessed(newHistory.size());
                return DreamRunResult.noop("no_update");
            }

            ParseResult parsed = parseUpdates(normalized);
            if (!parsed.hasAnyUpdates()) {
                log.warn("Dream: 无法解析模型输出，已跳过本次更新。");
                store.markHistoryAsProcessed(newHistory.size());
                return DreamRunResult.noop("parse_failed");
            }
            parseAndSaveUpdates(parsed);

            ensureGitInitialized();
            store.getGit().autoCommit("dream: update memory/user/soul");

            store.markHistoryAsProcessed(newHistory.size());

            log.info("Dream: 记忆更新成功。");
            return DreamRunResult.updated("updated");

        } catch (Exception e) {
            log.error("Dream: 记忆整合过程中发生错误", e);
            return DreamRunResult.noop("error");
        }
    }

    private String formatHistory(List<Map<String, Object>> history) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : history) {
            if (msg == null) {
                continue;
            }
            String role = msg.get("role") != null ? String.valueOf(msg.get("role")) : "unknown";
            Object content = msg.get("content");
            String text = stringifyContent(content);
            sb.append(role).append(": ").append(text).append("\n");
            Object toolCalls = msg.get("tool_calls");
            if (toolCalls != null) {
                sb.append("tool_calls: ").append(String.valueOf(toolCalls)).append("\n");
            }
        }
        return sb.toString();
    }

    private void ensureGitInitialized() {
        if (!store.getGit().isInitialized()) {
            boolean inited = store.getGit().init();
            if (!inited) {
                log.warn("Dream: 初始化记忆 Git 仓库失败，后续将无法使用 dream restore。");
            }
        }
    }

    private void parseAndSaveUpdates(ParseResult parsed) throws IOException {
        if (parsed.memoryMd() != null) store.updateMemoryMd(parsed.memoryMd());
        if (parsed.userMd() != null) store.updateUserMd(parsed.userMd());
        if (parsed.soulMd() != null) store.updateSoulMd(parsed.soulMd());
    }

    private ParseResult parseUpdates(String text) {
        if (text == null || text.isBlank()) {
            return new ParseResult(null, null, null);
        }

        ParseResult json = tryParseJson(text);
        if (json != null && json.hasAnyUpdates()) {
            return json;
        }

        return parseByHeadings(text);
    }

    private ParseResult parseByHeadings(String text) {
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        String current = null;
        boolean inFence = false;

        String[] lines = text.split("\\R");
        for (String line : lines) {
            Matcher fenceStart = FENCE_START.matcher(line);
            if (fenceStart.matches()) {
                inFence = true;
            } else if (inFence && FENCE_END.matcher(line).matches()) {
                inFence = false;
            }

            Matcher m = HEADING.matcher(line);
            if (!inFence && m.matches()) {
                String title = m.group(2) != null ? m.group(2).trim() : "";
                String key = normalizeSectionTitle(title);
                if (key != null) {
                    current = key;
                    sections.putIfAbsent(current, new StringBuilder());
                    continue;
                }
            }

            if (current != null) {
                sections.get(current).append(line).append("\n");
            }
        }

        return new ParseResult(
                normalizeSectionBody(sections.get("MEMORY.MD")),
                normalizeSectionBody(sections.get("USER.MD")),
                normalizeSectionBody(sections.get("SOUL.MD"))
        );
    }

    private ParseResult tryParseJson(String text) {
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(trimmed, Map.class);
            String memory = pickString(map, "memory_md", "MEMORY.md", "memory");
            String user = pickString(map, "user_md", "USER.md", "user");
            String soul = pickString(map, "soul_md", "SOUL.md", "soul");
            return new ParseResult(emptyToNull(memory), emptyToNull(user), emptyToNull(soul));
        } catch (Exception e) {
            return null;
        }
    }

    private static String pickString(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private static String normalizeSectionTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String upper = title.trim().toUpperCase(Locale.ROOT);
        if (SECTION_NAMES.contains(upper)) {
            return upper;
        }
        return null;
    }

    private static String normalizeSectionBody(StringBuilder sb) {
        if (sb == null) {
            return null;
        }
        String s = sb.toString().trim();
        return s.isBlank() ? null : s;
    }

    private static String emptyToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    private static String stringifyContent(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object itemObj : list) {
                if (!(itemObj instanceof Map<?, ?> rawItem)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) rawItem;
                String type = item.get("type") != null ? String.valueOf(item.get("type")) : "";
                if ("text".equals(type)) {
                    sb.append(item.get("text") != null ? String.valueOf(item.get("text")) : "");
                    sb.append("\n");
                } else if ("image_url".equals(type)) {
                    sb.append("[image]").append("\n");
                } else {
                    sb.append("[").append(type).append("]").append("\n");
                }
            }
            return sb.toString().trim();
        }
        return String.valueOf(content);
    }

    public record DreamRunResult(boolean updated, String status) {
        public static DreamRunResult updated(String status) {
            return new DreamRunResult(true, status);
        }

        public static DreamRunResult noop(String status) {
            return new DreamRunResult(false, status);
        }
    }

    private record ParseResult(String memoryMd, String userMd, String soulMd) {
        private boolean hasAnyUpdates() {
            return (memoryMd != null && !memoryMd.isBlank())
                    || (userMd != null && !userMd.isBlank())
                    || (soulMd != null && !soulMd.isBlank());
        }
    }
}
