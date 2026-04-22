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
    /**
     * 标记指定数量的历史记录为已处理
     * @param count 已处理的数量
     */
    public void markHistoryAsProcessed(int count) {
        // 如果计数小于或等于0，直接返回，不做任何操作
        if (count <= 0) {
            return;
        }
        // 使用锁确保线程安全，防止并发修改游标
        synchronized (cursorLock) {
            // 获取当前 Dream 处理的游标位置
            int current = getLastDreamCursor();
            // 获取最新的总历史记录游标位置
            int max = getLastCursor();
            // 计算新的游标位置：取当前游标加上处理数量与最大游标的较小值，防止越界
            int next = Math.min(max, current + count);
            // 更新 Dream 处理游标到新的位置
            setLastDreamCursor(next);
        }
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
        // 读取现有的所有记忆条目
        List<MemoryEntry> existing = readMemoryEntries();
        // 使用 LinkedHashMap 保持插入顺序，以 dedupeKey 为键存储记忆条目
        Map<String, MemoryEntry> byKey = new LinkedHashMap<>();
        for (MemoryEntry entry : existing) {
            byKey.put(entry.dedupeKey(), entry);
        }

        // 遍历候选记忆条目，如果 candidates 为 null 则使用空列表
        for (MemoryEntry candidate : candidates != null ? candidates : List.<MemoryEntry>of()) {
            // 跳过无效或摘要为空的候选条目
            if (candidate == null || candidate.getSummary() == null || candidate.getSummary().isBlank()) {
                continue;
            }
            // 获取候选条目的去重键
            String key = candidate.dedupeKey();
            // 查找是否存在相同键的现有条目
            MemoryEntry current = byKey.get(key);
            if (current == null) {
                // 如果不存在，更新候选条目的时间戳并加入映射
                candidate.touch();
                byKey.put(key, candidate);
                continue;
            }
            // 如果存在，合并重要性：取最大值
            current.setImportance(Math.max(current.getImportance(), candidate.getImportance()));
            // 合并置信度：取最大值
            current.setConfidence(Math.max(current.getConfidence(), candidate.getConfidence()));
            // 如果当前条目详情为空且候选条目详情不为空，则更新详情
            if (current.getDetails().isBlank() && !candidate.getDetails().isBlank()) {
                current.setDetails(candidate.getDetails());
            }
            // 如果候选条目是长期范围，则更新当前条目的范围
            if (MemoryEntry.SCOPE_LONG_TERM.equals(candidate.getScope())) {
                current.setScope(candidate.getScope());
            }
            // 如果候选条目状态为已丢弃，则更新当前条目状态为已丢弃
            if (MemoryEntry.STATUS_DISCARDED.equals(candidate.getStatus())) {
                current.setStatus(MemoryEntry.STATUS_DISCARDED);
            }
            // 合并别名列表，并去重
            current.getAliases().addAll(candidate.getAliases());
            current.setAliases(current.getAliases().stream().distinct().toList());
            // 合并标签列表，并去重
            current.getTags().addAll(candidate.getTags());
            current.setTags(current.getTags().stream().distinct().toList());
            // 更新当前条目的时间戳
            current.touch();
        }

        // 将映射中的值转换为列表
        List<MemoryEntry> merged = new ArrayList<>(byKey.values());
        // 写入合并后的记忆条目到文件
        writeMemoryEntries(merged);
        // 重建 Markdown 视图以反映最新变化
        rebuildMarkdownViews(merged);
        return merged;
    }

    /**
     * 如果需要，重建 Markdown 视图
     */
    public void rebuildMarkdownViewsIfNeeded() {
        // 如果结构化记忆文件不存在，直接返回
        if (!Files.exists(memoryEntriesFile)) {
            return;
        }
        // 检查 Markdown 视图是否需要重建，如果不需要则返回
        if (!markdownViewsNeedRebuild()) {
            return;
        }
        // 读取所有记忆条目并重建 Markdown 视图
        rebuildMarkdownViews(readMemoryEntries());
    }

    /**
     * 根据记忆条目重建 Markdown 视图
     * @param entries 记忆条目列表
     */
    public void rebuildMarkdownViews(List<MemoryEntry> entries) {
        // 如果 entries 为 null 则使用空列表
        List<MemoryEntry> source = entries != null ? entries : List.of();
        // 初始化用于存储 MEMORY.md、USER.md 和 SOUL.md 内容的行列表
        List<String> memoryLines = new ArrayList<>();
        List<String> userLines = new ArrayList<>();
        List<String> soulLines = new ArrayList<>();
        for (MemoryEntry entry : source) {
            // 跳过无效或非活跃的条目
            if (entry == null || !entry.isActive()) {
                continue;
            }
            // 跳过可丢弃范围的条目
            if (MemoryEntry.SCOPE_DISCARDABLE.equals(entry.getScope())) {
                continue;
            }
            // 根据条目类型分类添加到对应的行列表
            if (entry.isSoulEntry()) {
                soulLines.add(entry.renderLine());
            } else if (entry.isUserProfile()) {
                userLines.add(entry.renderLine());
            } else {
                memoryLines.add(entry.renderLine());
            }
        }
        try {
            // 更新 MEMORY.md 文件内容
            updateMemoryMd(renderMarkdown("MEMORY", memoryLines));
            // 更新 USER.md 文件内容
            updateUserMd(renderMarkdown("USER", userLines));
            // 更新 SOUL.md 文件内容
            updateSoulMd(renderMarkdown("SOUL", soulLines));
        } catch (IOException e) {
            // 如果发生 IO 异常，抛出运行时异常
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
        // 读取所有记忆条目
        List<MemoryEntry> all = readMemoryEntries();
        // 如果没有记忆条目，返回空列表
        if (all.isEmpty()) {
            return List.of();
        }
        // 组合查询字符串和任务目标
        String combined = (query != null ? query : "") + "\n" + (taskGoal != null ? taskGoal : "");
        // 对组合文本进行分词
        Set<String> queryTokens = tokenize(combined);

        // 初始化带评分的记忆条目列表
        List<ScoredMemory> scored = new ArrayList<>();
        for (MemoryEntry entry : all) {
            // 跳过无效或不可召回的条目
            if (entry == null || !entry.isRecallable()) {
                continue;
            }
            // 构建用于匹配的文本 haystack（包含摘要、详情和标签）
            String haystack = (entry.getSummary() + "\n" + entry.getDetails() + "\n" + String.join(" ", entry.getTags())).toLowerCase(Locale.ROOT);
            // 对 haystack 进行分词
            Set<String> memoryTokens = tokenize(haystack);
            // 计算查询 token 在记忆 token 中出现的次数
            long hits = memoryTokens.stream().filter(queryTokens::contains).count();
            // 基础评分：重要性 * 2 + 置信度
            double score = entry.getImportance() * 2.0d + entry.getConfidence();
            // 如果查询 token 不为空，增加基于匹配比例的评分
            if (!queryTokens.isEmpty()) {
                score += (double) hits / Math.max(1d, queryTokens.size()) * 4.0d;
            }
            // 如果是长期范围，额外增加评分
            if (MemoryEntry.SCOPE_LONG_TERM.equals(entry.getScope())) {
                score += 1.5d;
            }
            // 添加带评分的记忆条目
            scored.add(new ScoredMemory(entry, score));
        }

        // 按评分降序排序，限制返回数量，并提取记忆条目
        List<MemoryEntry> selected = scored.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(Math.max(0, limit))
                .map(ScoredMemory::entry)
                .toList();
        // 如果有选中的条目，标记它们为已使用并更新文件
        if (!selected.isEmpty()) {
            List<MemoryEntry> allEntries = new ArrayList<>(all);
            for (MemoryEntry entry : allEntries) {
                // 如果当前条目在选中列表中，标记为已使用
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
        // 如果查询为空、历史文件不存在或 limit <= 0，返回空列表
        if (query == null || query.isBlank() || !Files.exists(historyFile) || limit <= 0) {
            return List.of();
        }
        // 对查询字符串进行分词
        Set<String> queryTokens = tokenize(query);
        // 初始化带评分的历史记录列表
        List<ScoredHistory> scored = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(historyFile)) {
            String line;
            int order = 0;
            while ((line = reader.readLine()) != null) {
                // 跳过空行
                if (line.isBlank()) {
                    continue;
                }
                // 解析 JSONL 行
                Map<String, Object> parsed = MAPPER.readValue(line, new TypeReference<>() {});
                String type = String.valueOf(parsed.getOrDefault("type", ""));
                // 只处理 text 或 raw_archive 类型
                if (!"text".equals(type) && !"raw_archive".equals(type)) {
                    continue;
                }
                // 渲染归档历史内容
                String content = renderArchivedHistoryContent(type, parsed.get("content"));
                // 如果内容为空，跳过
                if (content.isBlank()) {
                    continue;
                }
                // 对内容进行分词
                Set<String> tokens = tokenize(content);
                // 计算查询 token 在内容 token 中出现的次数
                long hits = tokens.stream().filter(queryTokens::contains).count();
                // 如果没有命中，增加顺序计数并跳过
                if (hits == 0) {
                    order++;
                    continue;
                }
                // 计算评分：命中数 + 顺序权重
                double score = hits + (order * 0.001d);
                scored.add(new ScoredHistory(content, score));
                order++;
            }
        } catch (Exception e) {
            // 记录读取归档历史失败的警告日志
            log.warn("读取归档历史召回失败: {}", historyFile, e);
        }
        // 按评分降序排序，限制数量，截断内容并返回
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
        // 创建历史记录条目映射
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "text");
        entry.put("content", content != null ? content : "");
        // 调用通用方法追加条目
        appendHistoryEntry(entry);
    }

    /**
     * 原始归档消息列表（仅记录数量）
     * @param messages 消息列表
     */
    public void rawArchive(List<Map<String, Object>> messages) {
        // 创建原始归档条目映射
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "raw_archive");
        entry.put("content", Map.of(
                "messages_count", messages != null ? messages.size() : 0,
                "messages", messages != null ? messages : List.of()
        ));
        // 调用通用方法追加条目
        appendHistoryEntry(entry);
    }

    /**
     * 获取最后处理的游标值
     * @return 游标整数值，默认 0
     */
    public int getLastCursor() {
        synchronized (cursorLock) {
            try {
                // 如果游标文件不存在，返回 0
                if (!Files.exists(cursorFile)) return 0;
                // 读取并解析游标文件内容
                return Integer.parseInt(Files.readString(cursorFile).trim());
            } catch (Exception e) {
                // 如果发生异常，返回 0
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
                // 如果 Dream 游标文件不存在，返回 0
                if (!Files.exists(dreamCursorFile)) return 0;
                // 读取并解析 Dream 游标文件内容
                return Integer.parseInt(Files.readString(dreamCursorFile).trim());
            } catch (Exception e) {
                // 如果发生异常，返回 0
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
                // 写入 Dream 游标值，确保非负，并覆盖现有文件
                Files.writeString(dreamCursorFile, String.valueOf(Math.max(0, cursor)),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                // 如果发生 IO 异常，抛出运行时异常
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 读取未处理的历史记录（游标大于 sinceCursor 的记录）
     * @param sinceCursor 起始游标
     * @return 未处理的历史记录列表
     */
    /**
     * 读取未处理的历史记录（游标大于 sinceCursor 的记录）
     * @param sinceCursor 起始游标，只返回游标值严格大于此值的记录
     * @return 未处理的历史记录列表，每个元素是一个包含 cursor, timestamp, content 的 Map
     */
    public List<Map<String, Object>> readUnprocessedHistory(int sinceCursor) {
        // 如果历史记录文件不存在，直接返回空列表
        if (!Files.exists(historyFile)) return List.of();

        // 初始化用于存储未处理历史记录的列表
        List<Map<String, Object>> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(historyFile)) {
            String line;
            // 逐行读取 history.jsonl 文件
            while ((line = reader.readLine()) != null) {
                // 跳过空行
                if (line.isBlank()) {
                    continue;
                }
                // 将 JSON 行解析为 Map 对象
                Map<String, Object> parsed = MAPPER.readValue(line, new TypeReference<>() {});
                
                // 获取当前记录的游标值，如果不存在或类型不匹配则默认为 0
                Object cursorObj = parsed.get("cursor");
                int cursor = cursorObj instanceof Number n ? n.intValue() : 0;
                
                // 如果当前记录的游标小于或等于指定起始游标，则跳过（已处理过）
                if (cursor <= sinceCursor) {
                    continue;
                }

                // 构建新的条目 Map，只保留需要的字段
                Map<String, Object> entry = new LinkedHashMap<>();
                // 放入游标值
                entry.put("cursor", cursor);
                // 放入时间戳，转换为字符串，默认为空串
                entry.put("timestamp", String.valueOf(parsed.getOrDefault("timestamp", "")));

                // 兼容不同版本的内容字段：优先取 "content"，其次取 "payload"，最后默认为空串
                if (parsed.containsKey("content")) {
                    entry.put("content", parsed.get("content"));
                } else entry.put("content", parsed.getOrDefault("payload", ""));
                // 将构建好的条目添加到结果列表中
                entries.add(entry);
            }
        } catch (Exception e) {
            // 捕获异常并记录警告日志，防止程序崩溃
            log.warn("读取 history 失败: {}", historyFile, e);
        }
        // 返回所有未处理的历史记录
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
