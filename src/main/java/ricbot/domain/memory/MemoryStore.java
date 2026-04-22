package ricbot.domain.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.common.HelperUtils;
import ricbot.infra.git.GitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 纯文件 I/O 记忆层。
 *
 * 对应 Python MemoryStore。:contentReference[oaicite:4]{index=4}
 */
public class MemoryStore {

    // 日志记录器，用于记录类运行时的日志信息
    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    // JSON 对象映射器，用于处理 JSON 序列化与反序列化
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    // 默认最大历史记录条目数
    private static final int DEFAULT_MAX_HISTORY = 1000;
    // 用于匹配旧版历史记录条目开头的正则表达式模式
    private static final Pattern LEGACY_ENTRY_START_RE =
            Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2}[^\\]]*)\\]\\s*");

    // 工作空间根路径
    private final Path workspace;
    // 最大历史记录条目数限制
    private final int maxHistoryEntries;

    // 记忆目录路径 (memory/)
    private final Path memoryDir;
    // 主记忆文件路径 (memory/MEMORY.md)
    private final Path memoryFile;
    // 结构化记忆文件路径 (memory/memory_entries.jsonl)
    private final Path memoryEntriesFile;
    // 历史记录文件路径 (memory/history.jsonl)
    private final Path historyFile;
    // 旧版历史记录文件路径 (memory/HISTORY.md)
    private final Path legacyHistoryFile;
    // Soul 文件路径 (SOUL.md)
    private final Path soulFile;
    // 用户文件路径 (USER.md)
    private final Path userFile;
    // 当前游标文件路径 (memory/.cursor)
    private final Path cursorFile;
    // Dream 处理游标文件路径 (memory/.dream_cursor)
    private final Path dreamCursorFile;

    // Git 存储管理对象
    private final GitStore git;

    // 用于同步访问游标文件的锁对象
    private final Object cursorLock = new Object();

    /**
     * 构造函数，使用默认最大历史记录数
     * @param workspace 工作空间路径
     */
    public MemoryStore(Path workspace) {
        this(workspace, DEFAULT_MAX_HISTORY);
    }

    /**
     * 构造函数，指定最大历史记录数
     * @param workspace 工作空间路径
     * @param maxHistoryEntries 最大历史记录条目数
     */
    public MemoryStore(Path workspace, int maxHistoryEntries) {
        this.workspace = workspace;
        this.maxHistoryEntries = maxHistoryEntries;
        // 初始化记忆目录并确保其存在
        this.memoryDir = HelperUtils.ensureDir(workspace.resolve("memory"));
        // 初始化各文件路径
        this.memoryFile = memoryDir.resolve("MEMORY.md");
        this.memoryEntriesFile = memoryDir.resolve("memory_entries.jsonl");
        this.historyFile = memoryDir.resolve("history.jsonl");
        this.legacyHistoryFile = memoryDir.resolve("HISTORY.md");
        this.soulFile = workspace.resolve("SOUL.md");
        this.userFile = workspace.resolve("USER.md");
        this.cursorFile = memoryDir.resolve(".cursor");
        this.dreamCursorFile = memoryDir.resolve(".dream_cursor");
        // 初始化 Git 存储，跟踪特定文件
        this.git = new GitStore(workspace, List.of("SOUL.md", "USER.md", "memory/MEMORY.md"));
        ensureSeedFile(memoryFile, "templates/memory/MEMORY.md");
        ensureSeedFile(userFile, "templates/USER.md");
        ensureSeedFile(soulFile, "templates/SOUL.md");
        // 尝试迁移旧版历史记录
        maybeMigrateLegacyHistory();
    }

    /**
     * 获取工作空间路径
     * @return 工作空间 Path
     */
    public Path getWorkspace() { return workspace; }

    /**
     * 获取 Git 存储对象
     * @return GitStore 实例
     */
    public GitStore getGit() { return git; }

    /**
     * 读取文件内容为字符串
     * @param path 文件路径
     * @return 文件内容，如果出错返回空字符串
     */
    public static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.debug("读取文件失败: {}", path, e);
            return "";
        }
    }

    /**
     * 确保种子文件存在，如果不存在则从资源中复制
     * @param target 目标文件路径
     * @param resourcePath 资源路径
     */
    private void ensureSeedFile(Path target, String resourcePath) {
        try {
            if (target == null || Files.exists(target)) {
                return;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream in = MemoryStore.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (in != null) {
                    Files.write(target, in.readAllBytes());
                } else {
                    Files.writeString(target, "");
                }
            }
        } catch (Exception e) {
            log.debug("初始化默认文件失败: {} <- {}", target, resourcePath, e);
        }
    }

    /**
     * 读取记忆文件内容
     * @return 记忆内容
     */
    public String readMemory() { return readFile(memoryFile); }

    /**
     * 读取 Soul 文件内容
     * @return Soul 内容
     */
    public String readSoul() { return readFile(soulFile); }

    /**
     * 读取用户文件内容
     * @return 用户内容
     */
    public String readUser() { return readFile(userFile); }

    /**
     * 获取 MEMORY.md 内容别名
     * @return 记忆内容
     */
    public String getMemoryMd() { return readMemory(); }

    /**
     * 获取 USER.md 内容别名
     * @return 用户内容
     */
    public String getUserMd() { return readUser(); }

    /**
     * 获取 SOUL.md 内容别名
     * @return Soul 内容
     */
    public String getSoulMd() { return readSoul(); }

    /**
     * 更新记忆文件内容
     * @param content 新内容
     * @throws IOException IO 异常
     */
    public void updateMemoryMd(String content) throws IOException { Files.writeString(memoryFile, content); }

    /**
     * 更新用户文件内容
     * @param content 新内容
     * @throws IOException IO 异常
     */
    public void updateUserMd(String content) throws IOException { Files.writeString(userFile, content); }

    /**
     * 更新 Soul 文件内容
     * @param content 新内容
     * @throws IOException IO 异常
     */
    public void updateSoulMd(String content) throws IOException { Files.writeString(soulFile, content); }

    /**
     * 获取未处理的历史记录
     * @return 未处理的历史记录列表
     */
    public List<Map<String, Object>> getUnprocessedHistory() {
        // 获取上次 Dream 处理的游标位置
        int since = getLastDreamCursor();
        // 读取该游标之后的历史记录
        return readUnprocessedHistory(since);
    }

    /**
     * 标记指定数量的历史记录为已处理
     * @param count 已处理的数量
     */
    public void markHistoryAsProcessed(int count) {
        if (count <= 0) {
            return;
        }
        synchronized (cursorLock) {
            int current = getLastDreamCursor();
            int max = getLastCursor();
            int next = Math.min(max, current + count);
            setLastDreamCursor(next);
        }
    }

    /**
     * 获取记忆上下文
     * @return 记忆内容，如果为空则返回空字符串
     */
    public String getMemoryContext() {
        rebuildMarkdownViewsIfNeeded();
        String memory = readMemory();
        String user = readUser();
        String soul = readSoul();

        StringBuilder sb = new StringBuilder();
        if (memory != null && !memory.isBlank()) {
            sb.append("MEMORY.md\n").append(memory.trim()).append("\n\n");
        }
        if (user != null && !user.isBlank()) {
            sb.append("USER.md\n").append(user.trim()).append("\n\n");
        }
        if (soul != null && !soul.isBlank()) {
            sb.append("SOUL.md\n").append(soul.trim()).append("\n\n");
        }
        String out = sb.toString().trim();
        return HelperUtils.truncateText(out, 12_000);
    }

    /**
     * 读取结构化记忆条目
     * @return 记忆条目列表
     */
    public List<MemoryEntry> readMemoryEntries() {
        if (!Files.exists(memoryEntriesFile)) {
            return new ArrayList<>();
        }
        List<MemoryEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(memoryEntriesFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> parsed = MAPPER.readValue(line, new TypeReference<>() {});
                entries.add(MemoryEntry.fromMap(parsed));
            }
        } catch (Exception e) {
            log.warn("读取结构化记忆失败: {}", memoryEntriesFile, e);
        }
        return entries;
    }

    /**
     * 写入结构化记忆条目
     * @param entries 记忆条目列表
     */
    public void writeMemoryEntries(List<MemoryEntry> entries) {
        List<MemoryEntry> normalized = entries != null ? entries : List.of();
        try {
            Files.createDirectories(memoryEntriesFile.getParent());
            StringBuilder sb = new StringBuilder();
            for (MemoryEntry entry : normalized) {
                if (entry == null) {
                    continue;
                }
                sb.append(MAPPER.writeValueAsString(entry.toMap())).append("\n");
            }
            Files.writeString(memoryEntriesFile, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("写入结构化记忆失败: " + memoryEntriesFile, e);
        }
    }

    /**
     * 合并候选记忆条目到现有记忆中
     * @param candidates 候选记忆条目列表
     * @return 合并后的记忆条目列表
     */
    public List<MemoryEntry> mergeMemoryEntries(List<MemoryEntry> candidates) {
        List<MemoryEntry> existing = readMemoryEntries();
        Map<String, MemoryEntry> byKey = new LinkedHashMap<>();
        for (MemoryEntry entry : existing) {
            byKey.put(entry.dedupeKey(), entry);
        }

        for (MemoryEntry candidate : candidates != null ? candidates : List.<MemoryEntry>of()) {
            if (candidate == null || candidate.getSummary() == null || candidate.getSummary().isBlank()) {
                continue;
            }
            String key = candidate.dedupeKey();
            MemoryEntry current = byKey.get(key);
            if (current == null) {
                candidate.touch();
                byKey.put(key, candidate);
                continue;
            }
            current.setImportance(Math.max(current.getImportance(), candidate.getImportance()));
            current.setConfidence(Math.max(current.getConfidence(), candidate.getConfidence()));
            if (current.getDetails().isBlank() && !candidate.getDetails().isBlank()) {
                current.setDetails(candidate.getDetails());
            }
            if (MemoryEntry.SCOPE_LONG_TERM.equals(candidate.getScope())) {
                current.setScope(candidate.getScope());
            }
            if (MemoryEntry.STATUS_DISCARDED.equals(candidate.getStatus())) {
                current.setStatus(MemoryEntry.STATUS_DISCARDED);
            }
            current.getAliases().addAll(candidate.getAliases());
            current.setAliases(current.getAliases().stream().distinct().toList());
            current.getTags().addAll(candidate.getTags());
            current.setTags(current.getTags().stream().distinct().toList());
            current.touch();
        }

        List<MemoryEntry> merged = new ArrayList<>(byKey.values());
        writeMemoryEntries(merged);
        rebuildMarkdownViews(merged);
        return merged;
    }

    /**
     * 如果需要，重建 Markdown 视图
     */
    public void rebuildMarkdownViewsIfNeeded() {
        if (!Files.exists(memoryEntriesFile)) {
            return;
        }
        if (!markdownViewsNeedRebuild()) {
            return;
        }
        rebuildMarkdownViews(readMemoryEntries());
    }

    /**
     * 根据记忆条目重建 Markdown 视图
     * @param entries 记忆条目列表
     */
    public void rebuildMarkdownViews(List<MemoryEntry> entries) {
        List<MemoryEntry> source = entries != null ? entries : List.of();
        List<String> memoryLines = new ArrayList<>();
        List<String> userLines = new ArrayList<>();
        List<String> soulLines = new ArrayList<>();
        for (MemoryEntry entry : source) {
            if (entry == null || !entry.isActive()) {
                continue;
            }
            if (MemoryEntry.SCOPE_DISCARDABLE.equals(entry.getScope())) {
                continue;
            }
            if (entry.isSoulEntry()) {
                soulLines.add(entry.renderLine());
            } else if (entry.isUserProfile()) {
                userLines.add(entry.renderLine());
            } else {
                memoryLines.add(entry.renderLine());
            }
        }
        try {
            updateMemoryMd(renderMarkdown("MEMORY", memoryLines));
            updateUserMd(renderMarkdown("USER", userLines));
            updateSoulMd(renderMarkdown("SOUL", soulLines));
        } catch (IOException e) {
            throw new RuntimeException("更新 Markdown 记忆视图失败", e);
        }
    }

    /**
     * 召回相关记忆
     * @param query 查询字符串
     * @param taskGoal 任务目标
     * @param limit 返回数量限制
     * @return 召回的记忆条目列表
     */
    public List<MemoryEntry> recallMemories(String query, String taskGoal, int limit) {
        List<MemoryEntry> all = readMemoryEntries();
        if (all.isEmpty()) {
            return List.of();
        }
        String combined = (query != null ? query : "") + "\n" + (taskGoal != null ? taskGoal : "");
        Set<String> queryTokens = tokenize(combined);

        List<ScoredMemory> scored = new ArrayList<>();
        for (MemoryEntry entry : all) {
            if (entry == null || !entry.isRecallable()) {
                continue;
            }
            String haystack = (entry.getSummary() + "\n" + entry.getDetails() + "\n" + String.join(" ", entry.getTags())).toLowerCase(Locale.ROOT);
            Set<String> memoryTokens = tokenize(haystack);
            long hits = memoryTokens.stream().filter(queryTokens::contains).count();
            double score = entry.getImportance() * 2.0d + entry.getConfidence();
            if (!queryTokens.isEmpty()) {
                score += (double) hits / Math.max(1d, queryTokens.size()) * 4.0d;
            }
            if (MemoryEntry.SCOPE_LONG_TERM.equals(entry.getScope())) {
                score += 1.5d;
            }
            scored.add(new ScoredMemory(entry, score));
        }

        List<MemoryEntry> selected = scored.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(Math.max(0, limit))
                .map(ScoredMemory::entry)
                .toList();
        if (!selected.isEmpty()) {
            List<MemoryEntry> allEntries = new ArrayList<>(all);
            for (MemoryEntry entry : allEntries) {
                if (selected.stream().anyMatch(sel -> sel.getId().equals(entry.getId()))) {
                    entry.markUsed();
                }
            }
            writeMemoryEntries(allEntries);
        }
        return selected;
    }

    /**
     * 召回归档历史
     * @param query 查询字符串
     * @param limit 返回数量限制
     * @return 召回的历史记录内容列表
     */
    public List<String> recallArchivedHistory(String query, int limit) {
        if (query == null || query.isBlank() || !Files.exists(historyFile) || limit <= 0) {
            return List.of();
        }
        Set<String> queryTokens = tokenize(query);
        List<ScoredHistory> scored = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(historyFile)) {
            String line;
            int order = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> parsed = MAPPER.readValue(line, new TypeReference<>() {});
                String type = String.valueOf(parsed.getOrDefault("type", ""));
                if (!"text".equals(type) && !"raw_archive".equals(type)) {
                    continue;
                }
                String content = renderArchivedHistoryContent(type, parsed.get("content"));
                if (content.isBlank()) {
                    continue;
                }
                Set<String> tokens = tokenize(content);
                long hits = tokens.stream().filter(queryTokens::contains).count();
                if (hits == 0) {
                    order++;
                    continue;
                }
                double score = hits + (order * 0.001d);
                scored.add(new ScoredHistory(content, score));
                order++;
            }
        } catch (Exception e) {
            log.warn("读取归档历史召回失败: {}", historyFile, e);
        }
        return scored.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .map(item -> HelperUtils.truncateText(item.content(), 260))
                .toList();
    }

    /**
     * 追加一条历史记录到 history.jsonl
     * @param content 历史内容
     */
    public void appendHistory(String content) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "text");
        entry.put("content", content != null ? content : "");
        appendHistoryEntry(entry);
    }

    /**
     * 原始归档消息列表（仅记录数量）
     * @param messages 消息列表
     */
    public void rawArchive(List<Map<String, Object>> messages) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "raw_archive");
        entry.put("content", Map.of(
                "messages_count", messages != null ? messages.size() : 0,
                "messages", messages != null ? messages : List.of()
        ));
        appendHistoryEntry(entry);
    }

    /**
     * 获取最后处理的游标值
     * @return 游标整数值，默认 0
     */
    public int getLastCursor() {
        synchronized (cursorLock) {
            try {
                if (!Files.exists(cursorFile)) return 0;
                return Integer.parseInt(Files.readString(cursorFile).trim());
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /**
     * 获取最后 Dream 处理的游标值
     * @return Dream 游标整数值，默认 0
     */
    public int getLastDreamCursor() {
        synchronized (cursorLock) {
            try {
                if (!Files.exists(dreamCursorFile)) return 0;
                return Integer.parseInt(Files.readString(dreamCursorFile).trim());
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /**
     * 设置最后 Dream 处理的游标值
     * @param cursor 新的游标值
     */
    public void setLastDreamCursor(int cursor) {
        synchronized (cursorLock) {
            try {
                Files.writeString(dreamCursorFile, String.valueOf(Math.max(0, cursor)),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 读取未处理的历史记录（游标大于 sinceCursor 的记录）
     * @param sinceCursor 起始游标
     * @return 未处理的历史记录列表
     */
    public List<Map<String, Object>> readUnprocessedHistory(int sinceCursor) {
        // 如果历史记录文件不存在，返回空列表
        if (!Files.exists(historyFile)) return List.of();

        List<Map<String, Object>> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(historyFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> parsed = MAPPER.readValue(line, new TypeReference<>() {});
                Object cursorObj = parsed.get("cursor");
                int cursor = cursorObj instanceof Number n ? n.intValue() : 0;
                if (cursor <= sinceCursor) {
                    continue;
                }

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("cursor", cursor);
                entry.put("timestamp", String.valueOf(parsed.getOrDefault("timestamp", "")));

                if (parsed.containsKey("content")) {
                    entry.put("content", parsed.get("content"));
                } else if (parsed.containsKey("payload")) {
                    entry.put("content", parsed.get("payload"));
                } else {
                    entry.put("content", "");
                }
                entries.add(entry);
            }
        } catch (Exception e) {
            log.warn("读取 history 失败: {}", historyFile, e);
        }
        return entries;
    }

    /**
     * 尝试迁移旧版历史记录文件到新版 JSONL 格式
     */
    private void maybeMigrateLegacyHistory() {
        // 如果旧版历史文件不存在，直接返回
        if (!Files.exists(legacyHistoryFile)) return;
        // 如果新版历史文件已存在，说明可能已经迁移过，直接返回
        if (Files.exists(historyFile)) return;
        try {
            List<String> lines = Files.readAllLines(legacyHistoryFile);
            StringBuilder current = new StringBuilder();
            boolean hasAny = false;

            for (String line : lines) {
                boolean isEntryStart = line != null && LEGACY_ENTRY_START_RE.matcher(line).find();
                if (isEntryStart && current.length() > 0) {
                    appendHistory(current.toString().trim());
                    current.setLength(0);
                    hasAny = true;
                }
                current.append(line != null ? line : "").append("\n");
            }
            if (current.length() > 0) {
                appendHistory(current.toString().trim());
                hasAny = true;
            }

            if (hasAny) {
                log.info("已迁移旧版 HISTORY.md -> history.jsonl");
            }
        } catch (IOException e) {
            log.warn("迁移旧版历史失败: {}", legacyHistoryFile, e);
        }
    }

    /**
     * 追加历史记录条目到文件
     * @param entry 历史记录条目
     */
    private void appendHistoryEntry(Map<String, Object> entry) {
        synchronized (cursorLock) {
            try {
                Files.createDirectories(historyFile.getParent());
                int nextCursor = getLastCursor() + 1;

                Map<String, Object> lineObj = new LinkedHashMap<>();
                lineObj.put("cursor", nextCursor);
                lineObj.put("timestamp", Instant.now().toString());
                if (entry != null) {
                    lineObj.putAll(entry);
                }

                String line = MAPPER.writeValueAsString(lineObj) + "\n";
                Files.writeString(historyFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Files.writeString(cursorFile, String.valueOf(nextCursor),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 渲染 Markdown 格式内容
     * @param title 标题
     * @param lines 内容行列表
     * @return Markdown 字符串
     */
    private String renderMarkdown(String title, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        if (lines == null || lines.isEmpty()) {
            sb.append("_暂无结构化记忆条目。_\n");
            return sb.toString();
        }
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim() + "\n";
    }

    private boolean markdownViewsNeedRebuild() {
        if (!Files.exists(memoryFile) || !Files.exists(userFile) || !Files.exists(soulFile)) {
            return true;
        }
        try {
            long entriesMtime = Files.getLastModifiedTime(memoryEntriesFile).toMillis();
            return Files.getLastModifiedTime(memoryFile).toMillis() < entriesMtime
                    || Files.getLastModifiedTime(userFile).toMillis() < entriesMtime
                    || Files.getLastModifiedTime(soulFile).toMillis() < entriesMtime;
        } catch (IOException e) {
            log.debug("检查记忆 Markdown 视图状态失败: {}", memoryEntriesFile, e);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private String renderArchivedHistoryContent(String type, Object rawContent) {
        if ("text".equals(type)) {
            return rawContent != null ? String.valueOf(rawContent) : "";
        }
        if (!"raw_archive".equals(type) || !(rawContent instanceof Map<?, ?> map)) {
            return rawContent != null ? String.valueOf(rawContent) : "";
        }

        Object countObj = map.get("messages_count");
        int count = countObj instanceof Number n ? n.intValue() : 0;
        List<String> snippets = new ArrayList<>();
        Object messagesObj = map.get("messages");
        if (messagesObj instanceof List<?> messages) {
            for (Object item : messages) {
                if (!(item instanceof Map<?, ?> rawMessage)) {
                    continue;
                }
                Map<String, Object> message = (Map<String, Object>) rawMessage;
                String role = String.valueOf(message.getOrDefault("role", ""));
                String content = normalizeArchivedMessageContent(message.get("content"));
                if (content.isBlank()) {
                    continue;
                }
                snippets.add((role.isBlank() ? "message" : role) + ": " + content);
                if (snippets.size() >= 4) {
                    break;
                }
            }
        }

        StringBuilder sb = new StringBuilder("archived session");
        if (count > 0) {
            sb.append(" (").append(count).append(" messages)");
        }
        if (!snippets.isEmpty()) {
            sb.append(": ").append(String.join(" | ", snippets));
        }
        return sb.toString();
    }

    private String normalizeArchivedMessageContent(Object rawContent) {
        if (rawContent == null) {
            return "";
        }
        if (rawContent instanceof String s) {
            return HelperUtils.truncateText(s.trim(), 120);
        }
        if (rawContent instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                parts.add(String.valueOf(item));
                if (parts.size() >= 3) {
                    break;
                }
            }
            return HelperUtils.truncateText(String.join(" ", parts).trim(), 120);
        }
        return HelperUtils.truncateText(String.valueOf(rawContent).trim(), 120);
    }

    /**
     * 对文本进行分词处理
     * @param text 输入文本
     * @return 分词后的集合
     */
    private Set<String> tokenize(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}_]+")) {
            if (token.length() >= 2) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * 带评分的记忆条目记录
     */
    private record ScoredMemory(MemoryEntry entry, double score) {
    }

    /**
     * 带评分的历史记录记录
     */
    private record ScoredHistory(String content, double score) {
    }
}
