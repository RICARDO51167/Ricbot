package ricbot.domain.memory;

import ricbot.infra.template.PromptTemplates;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对应 Python: Dream
 *
 * 长期记忆整合器。
 * 1. 读取最近的会话历史。
 * 2. 通过 LLM 提取关键事实、用户偏好、机器人性格设定。
 * 3. 更新 MEMORY.md, USER.md, SOUL.md。
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

    public Dream(Path workspace, LLMProvider provider, String model, MemoryStore store) {
        this.provider = provider;
        this.model = model;
        this.store = store;
    }

    /**
     * 执行一次 Dream 整合任务。
     *
     * @return 如果有新内容处理并更新了记忆，返回 true。
     */
    public boolean run() {
        DreamRunResult result = runDetailed();
        return result.updated();
    }

    public DreamRunResult runDetailed() {
        // 记录开始记忆整合的日志
        log.info("Dream: 开始记忆整合...");

        try {
            // 1. 获取最近尚未被 Dream 处理的会话片段
            List<Map<String, Object>> newHistory = store.getUnprocessedHistory();
            // 检查获取到的历史记录是否为空或 null
            if (newHistory == null || newHistory.isEmpty()) {
                // 如果没有新的历史记录需要处理，记录日志并返回 false
                log.info("Dream: 没有新的历史记录需要处理。");
                return DreamRunResult.noop("no_history");
            }

            int dreamCursorBefore = store.getLastDreamCursor();
            int cursorBefore = store.getLastCursor();
            log.info("Dream: 将处理 {} 条历史记录 (dream_cursor={}/{})", newHistory.size(), dreamCursorBefore, cursorBefore);

            // 2. 读取现有记忆文件内容
            // 从存储中获取 MEMORY.md 的内容
            String memoryMd = store.getMemoryMd();
            // 从存储中获取 USER.md 的内容
            String userMd = store.getUserMd();
            // 从存储中获取 SOUL.md 的内容
            String soulMd = store.getSoulMd();

            // 3. 构建 Prompt
            // 创建一个 HashMap 用于存储模板渲染所需的参数
            Map<String, Object> kwargs = new HashMap<>();
            // 将格式化后的历史记录放入参数映射
            kwargs.put("history", formatHistory(newHistory));
            // 将现有的 MEMORY.md 内容放入参数映射
            kwargs.put("memory_md", memoryMd);
            // 将现有的 USER.md 内容放入参数映射
            kwargs.put("user_md", userMd);
            // 将现有的 SOUL.md 内容放入参数映射
            kwargs.put("soul_md", soulMd);

            // 使用 PromptTemplates 渲染 "agent/dream.md" 模板，生成最终的 prompt 字符串
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

            // 4. 调用 LLM
            // 调用 provider 的 chat 方法，发送消息并获取响应
            LLMResponse response = provider.chatWithRetry(messages, List.of(), model);
            // 从响应中获取内容字符串
            String content = response.getContent();

            String normalized = content != null ? content.trim() : "";
            // 检查 LLM 返回的内容是否为 null、空白或明确的 "(nothing)" 标记
            if (normalized.isBlank() || "(nothing)".equalsIgnoreCase(normalized) || "(无内容)".equals(normalized)) {
                // 记录 LLM 未返回任何更新内容的日志
                log.info("Dream: LLM 未返回任何更新内容。");
                // 虽然没有更新文件，但这段历史已经处理过了，标记为已处理
                store.markHistoryAsProcessed(newHistory.size());
                // 返回 false，表示没有进行实质性的记忆更新
                return DreamRunResult.noop("no_update");
            }

            // 5. 解析并保存更新后的记忆
            // 调用 parseAndSaveUpdates 方法解析 LLM 返回的内容并保存到相应的记忆文件中
            ParseResult parsed = parseUpdates(normalized);
            if (!parsed.hasAnyUpdates()) {
                log.warn("Dream: 无法解析模型输出，已跳过本次更新。");
                store.markHistoryAsProcessed(newHistory.size());
                return DreamRunResult.noop("parse_failed");
            }
            parseAndSaveUpdates(parsed);

            // 5.1 写入版本快照，支持 /dream-log 与 /dream-restore
            ensureGitInitialized();
            store.getGit().autoCommit("dream: update memory/user/soul");

            // 6. 标记这些历史记录已被 Dream 处理
            // 更新存储状态，标记刚才处理的历史记录条目数为已处理
            store.markHistoryAsProcessed(newHistory.size());

            // 记录记忆更新成功的日志
            log.info("Dream: 记忆更新成功。");
            // 返回 true，表示成功处理并更新了记忆
            return DreamRunResult.updated("updated");

        } catch (Exception e) {
            // 捕获异常，记录记忆整合过程中发生的错误日志
            log.error("Dream: 记忆整合过程中发生错误", e);
            // 发生异常时返回 false
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
