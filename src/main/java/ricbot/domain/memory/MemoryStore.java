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
 */
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final int DEFAULT_MAX_HISTORY = 1000;
    private static final Pattern LEGACY_ENTRY_START_RE =
            Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2}[^\\]]*)\\]\\s*");

    private final Path workspace;
    private final int maxHistoryEntries;

    private final Path memoryDir;
    private final Path memoryFile;
    private final Path historyFile;
    private final Path legacyHistoryFile;
    private final Path soulFile;
    private final Path userFile;
    private final Path cursorFile;
    private final Path dreamCursorFile;

    private final GitStore git;

    private final Object cursorLock = new Object();

    public MemoryStore(Path workspace) {
        this(workspace, DEFAULT_MAX_HISTORY);
    }

    public MemoryStore(Path workspace, int maxHistoryEntries) {
        this.workspace = workspace;
        this.maxHistoryEntries = maxHistoryEntries;
        this.memoryDir = HelperUtils.ensureDir(workspace.resolve("memory"));
        this.memoryFile = memoryDir.resolve("MEMORY.md");
        this.historyFile = memoryDir.resolve("history.jsonl");
        this.legacyHistoryFile = memoryDir.resolve("HISTORY.md");
        this.soulFile = workspace.resolve("SOUL.md");
        this.userFile = workspace.resolve("USER.md");
        this.cursorFile = memoryDir.resolve(".cursor");
        this.dreamCursorFile = memoryDir.resolve(".dream_cursor");
        this.git = new GitStore(workspace, List.of("SOUL.md", "USER.md", "memory/MEMORY.md"));
        ensureSeedFile(memoryFile, "templates/memory/MEMORY.md");
        ensureSeedFile(userFile, "templates/USER.md");
        ensureSeedFile(soulFile, "templates/SOUL.md");
        maybeMigrateLegacyHistory();
    }

    public Path getWorkspace() { return workspace; }

    public GitStore getGit() { return git; }

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

    public String readMemory() { return readFile(memoryFile); }

    public String readSoul() { return readFile(soulFile); }

    public String readUser() { return readFile(userFile); }

    public String getMemoryMd() { return readMemory(); }

    public String getUserMd() { return readUser(); }

    public String getSoulMd() { return readSoul(); }

    public void updateMemoryMd(String content) throws IOException { Files.writeString(memoryFile, content); }

    public void updateUserMd(String content) throws IOException { Files.writeString(userFile, content); }

    public void updateSoulMd(String content) throws IOException { Files.writeString(soulFile, content); }

    public List<Map<String, Object>> getUnprocessedHistory() {
        int since = getLastDreamCursor();
        return readUnprocessedHistory(since);
    }

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

    public void appendHistory(String content) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "text");
        entry.put("content", content != null ? content : "");
        appendHistoryEntry(entry);
    }

    public void rawArchive(List<Map<String, Object>> messages) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "raw_archive");
        entry.put("content", Map.of(
                "messages_count", messages != null ? messages.size() : 0,
                "messages", messages != null ? messages : List.of()
        ));
        appendHistoryEntry(entry);
    }

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

    public List<Map<String, Object>> readUnprocessedHistory(int sinceCursor) {
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

    private void maybeMigrateLegacyHistory() {
        if (!Files.exists(legacyHistoryFile)) return;
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
