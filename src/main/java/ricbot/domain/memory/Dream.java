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

    public Dream(LLMProvider provider, String model, MemoryStore store) {
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
            List<MemoryEntry> candidates = store.drainMemoryCandidates();
            if (!candidates.isEmpty()) {
                List<MemoryEntry> before = store.readMemoryEntries();
                store.mergeMemoryEntries(candidates);
                appendAudit("candidates", candidates, before, store.readMemoryEntries(), 0, "merged_candidates");
                ensureGitInitialized();
                store.getGit().autoCommit("dream: merge memory candidates");
                log.info("Dream: 已合并 {} 条即时记忆候选。", candidates.size());
            }

            // 1. 获取最近尚未被 Dream 处理的会话片段
            List<Map<String, Object>> newHistory = store.getUnprocessedHistory();
            // 检查获取到的历史记录是否为空或 null
            if (newHistory == null || newHistory.isEmpty()) {
                if (!candidates.isEmpty()) {
                    return DreamRunResult.updated("merged_candidates");
                }
                // 如果没有新的历史记录需要处理，记录日志并返回无操作结果
                log.info("Dream: 没有新的历史记录需要处理。");
                return DreamRunResult.noop("no_history");
            }

            // 获取当前的 dream 游标和普通游标位置，用于日志记录
            int dreamCursorBefore = store.getLastDreamCursor();
            int cursorBefore = store.getLastCursor();
            // 记录即将处理的历史记录数量及当前游标状态
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
            // 将序列化的记忆条目 JSON 字符串放入参数映射
            kwargs.put("memory_entries_json", stringifyEntries(store.readMemoryEntries()));

            // 使用 PromptTemplates 渲染 "agent/dream.md" 模板，生成最终的 prompt 字符串
            String prompt;
            try {
                // 渲染模板，如果失败则捕获异常
                prompt = PromptTemplates.renderTemplate("agent/dream.md", true, kwargs);
            } catch (Exception e) {
                // 记录模板渲染失败的警告日志
                log.warn("Dream: 模板渲染失败", e);
                // 返回无操作结果，标记原因为模板渲染失败
                return DreamRunResult.noop("template_render_failed");
            }

            // 初始化消息列表，用于发送给 LLM
            List<Map<String, Object>> messages = new ArrayList<>();
            // 创建系统消息对象
            Map<String, Object> systemMsg = new LinkedHashMap<>();
            // 设置角色为 system
            systemMsg.put("role", "system");
            // 设置系统指令内容，规定输出格式和要求
            systemMsg.put("content",
                    "你负责更新三个文件：MEMORY.md / USER.md / SOUL.md。\n"
                            + "输出要求：\n"
                            + "1) 只输出更新结果，不要输出解释。\n"
                            + "2) 使用 Markdown 标题段落输出，标题必须严格为：### MEMORY.md / ### USER.md / ### SOUL.md。\n"
                            + "3) 每个标题下给出对应文件的完整内容（不是 diff）。\n"
                            + "4) 如果无需更新，输出严格为：(nothing)\n"
            );
            // 将系统消息添加到消息列表
            messages.add(systemMsg);

            // 创建用户消息对象
            Map<String, Object> userMsg = new LinkedHashMap<>();
            // 设置角色为 user
            userMsg.put("role", "user");
            // 设置内容为之前生成的 prompt
            userMsg.put("content", prompt);
            // 将用户消息添加到消息列表
            messages.add(userMsg);

            // 4. 调用 LLM
            // 调用 provider 的 chatWithRetry 方法，发送消息并获取响应，支持重试机制
            LLMResponse response = provider.chatWithRetry(messages, List.of(), model);
            // 从响应中获取内容字符串
            String content = response.getContent();

            // 对内容进行标准化处理：去除首尾空白，若为 null 则转为空字符串
            String normalized = content != null ? content.trim() : "";
            // 检查 LLM 返回的内容是否为 null、空白或明确的 "(nothing)" 或 "(无内容)" 标记
            if (normalized.isBlank() || "(nothing)".equalsIgnoreCase(normalized) || "(无内容)".equals(normalized)) {
                // 记录 LLM 未返回任何更新内容的日志
                log.info("Dream: LLM 未返回任何更新内容。");
                // 虽然没有更新文件，但这段历史已经处理过了，标记为已处理
                store.markHistoryAsProcessed(newHistory.size());
                // 返回无操作结果，标记原因为无更新
                return DreamRunResult.noop("no_update");
            }

            // 尝试解析结构化记忆条目（JSON 格式）
            List<MemoryEntry> memoryEntries = parseMemoryEntries(normalized);
            // 如果解析到了非空的结构化记忆条目
            if (!memoryEntries.isEmpty()) {
                // 合并记忆条目到存储中
                List<MemoryEntry> before = store.readMemoryEntries();
                store.mergeMemoryEntries(memoryEntries);
                appendAudit("llm_structured", memoryEntries, before, store.readMemoryEntries(), newHistory.size(), "updated");
                // 确保 Git 仓库已初始化
                ensureGitInitialized();
                // 自动提交 Git 变更，备注为更新结构化记忆
                store.getGit().autoCommit("dream: update structured memories");
                // 标记这些历史记录已被 Dream 处理
                store.markHistoryAsProcessed(newHistory.size());
                // 记录结构化记忆更新成功的日志
                log.info("Dream: 结构化记忆更新成功。");
                // 返回更新成功结果
                return DreamRunResult.updated("updated");
            }

            // 如果结构化解析失败，尝试解析 Markdown 格式的更新
            ParseResult parsed = parseUpdates(normalized);
            // 检查解析结果中是否包含任何有效的更新内容
            if (!parsed.hasAnyUpdates()) {
                // 记录无法解析模型输出的警告日志
                log.warn("Dream: 无法解析模型输出，已跳过本次更新。");
                // 标记这些历史记录已被 Dream 处理，避免重复处理
                store.markHistoryAsProcessed(newHistory.size());
                // 返回无操作结果，标记原因为解析失败
                return DreamRunResult.noop("parse_failed");
            }
            // 解析并保存更新内容到对应的记忆文件中
            parseAndSaveUpdates(parsed);
            store.appendDreamAudit(Map.of(
                    "source", "llm_markdown",
                    "status", "updated",
                    "history_count", newHistory.size(),
                    "memory_entry_candidates", 0,
                    "added", 0,
                    "updated", 0,
                    "discarded", 0
            ));

            // 5.1 写入版本快照，支持 /dream-log 与 /dream-restore
            // 确保 Git 仓库已初始化
            ensureGitInitialized();
            // 自动提交 Git 变更，备注为更新 memory/user/soul 文件
            store.getGit().autoCommit("dream: update memory/user/soul");

            // 6. 标记这些历史记录已被 Dream 处理
            // 更新存储状态，标记刚才处理的历史记录条目数为已处理
            store.markHistoryAsProcessed(newHistory.size());

            // 记录记忆更新成功的日志
            log.info("Dream: 记忆更新成功。");
            // 返回更新成功结果
            return DreamRunResult.updated("updated");

        } catch (Exception e) {
            // 捕获异常，记录记忆整合过程中发生的错误日志
            log.error("Dream: 记忆整合过程中发生错误", e);
            // 发生异常时返回无操作结果，标记原因为错误
            return DreamRunResult.noop("error");
        }
    }

    /**
     * 格式化历史记录为字符串，用于构建 Prompt。
     *
     * @param history 历史消息列表
     * @return 格式化后的字符串
     */
    private String formatHistory(List<Map<String, Object>> history) {
        // 创建 StringBuilder 用于拼接字符串
        StringBuilder sb = new StringBuilder();
        // 遍历每一条历史消息
        for (Map<String, Object> msg : history) {
            // 如果消息为空，跳过
            if (msg == null) {
                continue;
            }
            // 获取角色信息，如果为空则默认为 "unknown"
            String role = msg.get("role") != null ? String.valueOf(msg.get("role")) : "unknown";
            // 获取消息内容
            Object content = msg.get("content");
            // 将内容对象转换为字符串
            String text = stringifyContent(content);
            String type = msg.get("type") != null ? String.valueOf(msg.get("type")) : "text";
            if ("session_summary".equals(type)) {
                sb.append("archived_session_summary: ").append(text).append("\n");
            } else {
                // 拼接角色和内容到结果中
                sb.append(role).append(": ").append(text).append("\n");
            }
            // 获取工具调用信息
            Object toolCalls = msg.get("tool_calls");
            // 如果存在工具调用，将其拼接到结果中
            if (toolCalls != null) {
                sb.append("tool_calls: ").append(String.valueOf(toolCalls)).append("\n");
            }
        }
        // 返回最终拼接的字符串
        return sb.toString();
    }

    /**
     * 确保 Git 仓库已初始化。
     * 如果未初始化，则尝试初始化，并在失败时记录警告日志。
     */
    private void ensureGitInitialized() {
        // 检查 Git 是否已初始化
        if (!store.getGit().isInitialized()) {
            // 尝试初始化 Git 仓库
            boolean inited = store.getGit().init();
            // 如果初始化失败，记录警告日志
            if (!inited) {
                log.warn("Dream: 初始化记忆 Git 仓库失败，后续将无法使用 dream restore。");
            }
        }
    }

    /**
     * 解析并保存更新内容到对应的记忆文件中。
     *
     * @param parsed 解析结果，包含三个文件的更新内容
     * @throws IOException 如果写入文件时发生错误
     */
    private void parseAndSaveUpdates(ParseResult parsed) throws IOException {
        // 如果 MEMORY.md 有更新内容，则更新存储
        if (parsed.memoryMd() != null) store.updateMemoryMd(parsed.memoryMd());
        // 如果 USER.md 有更新内容，则更新存储
        if (parsed.userMd() != null) store.updateUserMd(parsed.userMd());
        // 如果 SOUL.md 有更新内容，则更新存储
        if (parsed.soulMd() != null) store.updateSoulMd(parsed.soulMd());
    }

    /**
     * 从文本中解析结构化记忆条目（MemoryEntry）。
     *
     * @param text 包含 JSON 格式记忆条目的文本
     * @return 解析后的记忆条目列表，如果解析失败或为空则返回空列表
     */
    private List<MemoryEntry> parseMemoryEntries(String text) {
        // 如果文本为空或空白，返回空列表
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            // 创建 ObjectMapper 实例并注册模块以支持更多数据类型
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            // 将文本解析为 Map 对象
            Map<?, ?> parsed = mapper.readValue(text, Map.class);
            // 获取 "entries" 字段
            Object entriesObj = parsed.get("entries");
            // 如果 "entries" 不是 List 类型，返回空列表
            if (!(entriesObj instanceof List<?> list)) {
                return List.of();
            }
            // 创建记忆条目列表
            List<MemoryEntry> entries = new ArrayList<>();
            // 遍历列表中的每一项
            for (Object item : list) {
                // 如果项不是 Map 类型，跳过
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                // 强制转换为 Map<String, Object>
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) raw;
                // 从 Map 创建 MemoryEntry 对象
                MemoryEntry entry = MemoryEntry.fromMap(map);
                // 如果摘要为空或空白，跳过该条目
                if (entry.getSummary() == null || entry.getSummary().isBlank()) {
                    continue;
                }
                // 将有效条目添加到列表中
                entries.add(entry);
            }
            // 返回解析后的条目列表
            return entries;
        } catch (Exception ignored) {
            // 如果解析过程中发生异常，忽略并返回空列表
            return List.of();
        }
    }

    /**
     * 将记忆条目列表序列化为字符串表示形式。
     *
     * @param entries 记忆条目列表
     * @return 序列化后的字符串
     */
    private String stringifyEntries(List<MemoryEntry> entries) {
        // 如果列表为空或 null，返回空数组字符串
        if (entries == null || entries.isEmpty()) {
            return "[]";
        }
        // 创建 StringBuilder 用于拼接 JSON 数组字符串
        StringBuilder sb = new StringBuilder("[\n");
        // 遍历每个记忆条目
        for (MemoryEntry entry : entries) {
            // 将条目转换为 Map 并追加到字符串中
            sb.append(entry.toMap()).append("\n");
        }
        // 添加结束括号
        sb.append("]");
        // 返回最终字符串
        return sb.toString();
    }

    /**
     * 解析 LLM 返回的更新文本。
     * 优先尝试解析为 JSON 格式，如果失败则尝试通过 Markdown 标题解析。
     *
     * @param text LLM 返回的文本
     * @return 解析结果对象
     */
    private ParseResult parseUpdates(String text) {
        // 如果文本为空或空白，返回空的解析结果
        if (text == null || text.isBlank()) {
            return new ParseResult(null, null, null);
        }

        // 尝试将文本解析为 JSON 格式
        ParseResult json = tryParseJson(text);
        // 如果 JSON 解析成功且包含有效更新，直接返回
        if (json != null && json.hasAnyUpdates()) {
            return json;
        }

        // 如果 JSON 解析失败或无更新，尝试通过 Markdown 标题解析
        return parseByHeadings(text);
    }

    /**
     * 通过 Markdown 标题解析文本，提取 MEMORY.md, USER.md, SOUL.md 的内容。
     *
     * @param text 待解析的文本
     * @return 解析结果对象
     */
    private ParseResult parseByHeadings(String text) {
        // 使用 LinkedHashMap 保持插入顺序，存储各个部分的内容
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        // 当前正在解析的部分名称
        String current = null;
        // 标记是否在代码块内部
        boolean inFence = false;

        // 按行分割文本
        String[] lines = text.split("\\R");
        // 遍历每一行
        for (String line : lines) {
            // 检查是否是代码块开始标记
            Matcher fenceStart = FENCE_START.matcher(line);
            if (fenceStart.matches()) {
                inFence = true;
            // 检查是否是代码块结束标记
            } else if (inFence && FENCE_END.matcher(line).matches()) {
                inFence = false;
            }

            // 检查是否是标题行
            Matcher m = HEADING.matcher(line);
            // 如果不在代码块内且匹配到标题
            if (!inFence && m.matches()) {
                // 获取标题文本
                String title = m.group(2) != null ? m.group(2).trim() : "";
                // 规范化标题名称
                String key = normalizeSectionTitle(title);
                // 如果是有效的部分名称
                if (key != null) {
                    // 设置当前部分
                    current = key;
                    // 初始化该部分的 StringBuilder
                    sections.putIfAbsent(current, new StringBuilder());
                    // 跳过当前行，不将其作为内容
                    continue;
                }
            }

            // 如果当前有激活的部分，将行内容追加到对应部分
            if (current != null) {
                sections.get(current).append(line).append("\n");
            }
        }

        // 构建并返回解析结果，对各部分内容进行规范化处理
        return new ParseResult(
                normalizeSectionBody(sections.get("MEMORY.MD")),
                normalizeSectionBody(sections.get("USER.MD")),
                normalizeSectionBody(sections.get("SOUL.MD"))
        );
    }

    /**
     * 尝试将文本解析为 JSON 格式。
     *
     * @param text 待解析的文本
     * @return 如果解析成功返回 ParseResult，否则返回 null
     */
    private ParseResult tryParseJson(String text) {
        // 去除首尾空白
        String trimmed = text.trim();
        // 如果不是以 { 开头并以 } 结尾，则认为不是 JSON 对象，返回 null
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            return null;
        }
        try {
            // 创建 ObjectMapper 实例
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(trimmed, Map.class);
            // 从 Map 中提取各部分内容，支持多种键名
            String memory = pickString(map, "memory_md", "MEMORY.md", "memory");
            String user = pickString(map, "user_md", "USER.md", "user");
            String soul = pickString(map, "soul_md", "SOUL.md", "soul");
            // 将空字符串转换为 null 后构建解析结果
            return new ParseResult(emptyToNull(memory), emptyToNull(user), emptyToNull(soul));
        } catch (Exception e) {
            // 如果解析异常，返回 null
            return null;
        }
    }

    /**
     * 从 Map 中按优先级查找字符串值。
     *
     * @param map 数据 Map
     * @param keys 待查找的键名列表
     * @return 找到的第一个字符串值，如果未找到则返回 null
     */
    private static String pickString(Map<String, Object> map, String... keys) {
        // 遍历所有可能的键名
        for (String k : keys) {
            // 获取对应的值
            Object v = map.get(k);
            // 如果值是字符串类型，直接返回
            if (v instanceof String s) {
                return s;
            }
        }
        // 如果未找到任何字符串值，返回 null
        return null;
    }

    /**
     * 规范化章节标题，将其转换为标准的大写格式。
     *
     * @param title 原始标题
     * @return 规范化后的标题，如果无效则返回 null
     */
    private static String normalizeSectionTitle(String title) {
        // 如果标题为空或空白，返回 null
        if (title == null || title.isBlank()) {
            return null;
        }
        // 转换为大写
        String upper = title.trim().toUpperCase(Locale.ROOT);
        // 检查是否在允许的章节名称集合中
        if (SECTION_NAMES.contains(upper)) {
            return upper;
        }
        // 如果不在集合中，返回 null
        return null;
    }

    /**
     * 规范化章节内容，去除首尾空白，如果内容为空则返回 null。
     *
     * @param sb 章节内容的 StringBuilder
     * @return 规范化后的字符串，如果为空则返回 null
     */
    private static String normalizeSectionBody(StringBuilder sb) {
        // 如果 StringBuilder 为 null，返回 null
        if (sb == null) {
            return null;
        }
        // 转换为字符串并去除首尾空白
        String s = sb.toString().trim();
        // 如果结果为空白，返回 null，否则返回结果
        return s.isBlank() ? null : s;
    }

    /**
     * 将空字符串或空白字符串转换为 null。
     *
     * @param s 输入字符串
     * @return 如果输入为 null 或空白则返回 null，否则返回修剪后的字符串
     */
    private static String emptyToNull(String s) {
        // 如果输入为 null，直接返回 null
        if (s == null) {
            return null;
        }
        // 去除首尾空白
        String t = s.trim();
        // 如果结果为空白，返回 null，否则返回结果
        return t.isBlank() ? null : t;
    }

    /**
     * 将消息内容对象转换为字符串表示。
     * 支持 String、List（包含文本、图片等复杂结构）以及其他类型。
     *
     * @param content 消息内容对象
     * @return 字符串表示
     */
    private static String stringifyContent(Object content) {
        // 如果内容为 null，返回空字符串
        if (content == null) {
            return "";
        }
        // 如果内容是字符串，直接返回
        if (content instanceof String s) {
            return s;
        }
        // 如果内容是列表（通常用于多模态消息）
        if (content instanceof List<?> list) {
            // 创建 StringBuilder 用于拼接
            StringBuilder sb = new StringBuilder();
            // 遍历列表中的每一项
            for (Object itemObj : list) {
                // 如果项不是 Map 类型，跳过
                if (!(itemObj instanceof Map<?, ?> rawItem)) {
                    continue;
                }
                // 强制转换为 Map
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) rawItem;
                // 获取类型字段
                String type = item.get("type") != null ? String.valueOf(item.get("type")) : "";
                // 如果是文本类型
                if ("text".equals(type)) {
                    // 追加文本内容
                    sb.append(item.get("text") != null ? String.valueOf(item.get("text")) : "");
                    sb.append("\n");
                // 如果是图片 URL 类型
                } else if ("image_url".equals(type)) {
                    // 追加占位符
                    sb.append("[image]").append("\n");
                } else {
                    // 其他类型，追加类型标记
                    sb.append("[").append(type).append("]").append("\n");
                }
            }
            // 返回拼接后的字符串并去除首尾空白
            return sb.toString().trim();
        }
        // 其他类型，直接转换为字符串
        return String.valueOf(content);
    }

    private void appendAudit(
            String source,
            List<MemoryEntry> candidates,
            List<MemoryEntry> before,
            List<MemoryEntry> after,
            int historyCount,
            String status
    ) {
        Map<String, MemoryEntry> beforeByKey = new LinkedHashMap<>();
        for (MemoryEntry entry : before != null ? before : List.<MemoryEntry>of()) {
            if (entry != null) {
                beforeByKey.put(entry.dedupeKey(), entry);
            }
        }

        int added = 0;
        int updated = 0;
        int discarded = 0;
        for (MemoryEntry candidate : candidates != null ? candidates : List.<MemoryEntry>of()) {
            if (candidate == null || candidate.getSummary() == null || candidate.getSummary().isBlank()) {
                continue;
            }
            if (MemoryEntry.STATUS_DISCARDED.equals(candidate.getStatus())) {
                discarded++;
            }
            if (beforeByKey.containsKey(candidate.dedupeKey())) {
                updated++;
            } else {
                added++;
            }
        }

        store.appendDreamAudit(Map.of(
                "source", source,
                "status", status,
                "history_count", historyCount,
                "memory_entry_candidates", candidates != null ? candidates.size() : 0,
                "total_before", before != null ? before.size() : 0,
                "total_after", after != null ? after.size() : 0,
                "added", added,
                "updated", updated,
                "discarded", discarded
        ));
    }

    /**
     * Dream 运行结果记录类。
     *
     * @param updated 是否进行了更新
     * @param status 状态描述
     */
    public record DreamRunResult(boolean updated, String status) {
        /**
         * 创建表示已更新的结果对象。
         *
         * @param status 状态描述
         * @return DreamRunResult 对象
         */
        public static DreamRunResult updated(String status) {
            return new DreamRunResult(true, status);
        }

        /**
         * 创建表示无操作的结果对象。
         *
         * @param status 状态描述
         * @return DreamRunResult 对象
         */
        public static DreamRunResult noop(String status) {
            return new DreamRunResult(false, status);
        }
    }

    /**
     * 解析结果记录类，包含三个文件的更新内容。
     *
     * @param memoryMd MEMORY.md 的更新内容
     * @param userMd USER.md 的更新内容
     * @param soulMd SOUL.md 的更新内容
     */
    private record ParseResult(String memoryMd, String userMd, String soulMd) {
        /**
         * 检查是否有任何有效的更新内容。
         *
         * @return 如果有任意一个文件内容非空且非空白，返回 true
         */
        private boolean hasAnyUpdates() {
            return (memoryMd != null && !memoryMd.isBlank())
                    || (userMd != null && !userMd.isBlank())
                    || (soulMd != null && !soulMd.isBlank());
        }
    }
}
