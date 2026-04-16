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

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
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
}
