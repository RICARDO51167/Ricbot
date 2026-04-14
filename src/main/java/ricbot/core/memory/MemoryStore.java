package ricbot.core.memory;

import ricbot.infra.git.GitStore;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 纯文件 I/O 记忆层。
 *
 * 对应 Python MemoryStore。:contentReference[oaicite:4]{index=4}
 */
public class MemoryStore {

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

    public MemoryStore(Path workspace) {
        this(workspace, DEFAULT_MAX_HISTORY);
    }

    public MemoryStore(Path workspace, int maxHistoryEntries) {
        this.workspace = workspace;
        this.maxHistoryEntries = maxHistoryEntries;
        this.memoryDir = ensureDir(workspace.resolve("memory"));
        this.memoryFile = memoryDir.resolve("MEMORY.md");
        this.historyFile = memoryDir.resolve("history.jsonl");
        this.legacyHistoryFile = memoryDir.resolve("HISTORY.md");
        this.soulFile = workspace.resolve("SOUL.md");
        this.userFile = workspace.resolve("USER.md");
        this.cursorFile = memoryDir.resolve(".cursor");
        this.dreamCursorFile = memoryDir.resolve(".dream_cursor");
        this.git = new GitStore(workspace, List.of("SOUL.md", "USER.md", "memory/MEMORY.md"));
        maybeMigrateLegacyHistory();
    }

    public Path getWorkspace() { return workspace; }
    public GitStore getGit() { return git; }

    public static Path ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "";
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
        int current = getLastDreamCursor();
        setLastDreamCursor(current + count);
    }

    public String getMemoryContext() {
        String memory = readMemory();
        return memory == null ? "" : memory;
    }

    public void appendHistory(String content) {
        try {
            Files.createDirectories(historyFile.getParent());
            int nextCursor = getLastCursor() + 1;
            String line = "{\"cursor\":" + nextCursor
                    + ",\"timestamp\":\"" + LocalDateTime.now()
                    + "\",\"content\":" + jsonEscape(content) + "}\n";
            Files.writeString(historyFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(cursorFile, String.valueOf(nextCursor));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void rawArchive(List<Map<String, Object>> messages) {
        appendHistory("(raw archive) " + messages.size() + " messages");
    }

    public int getLastCursor() {
        try {
            if (!Files.exists(cursorFile)) return 0;
            return Integer.parseInt(Files.readString(cursorFile).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public int getLastDreamCursor() {
        try {
            if (!Files.exists(dreamCursorFile)) return 0;
            return Integer.parseInt(Files.readString(dreamCursorFile).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public void setLastDreamCursor(int cursor) {
        try {
            Files.writeString(dreamCursorFile, String.valueOf(cursor));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Map<String, Object>> readUnprocessedHistory(int sinceCursor) {
        if (!Files.exists(historyFile)) return List.of();

        List<Map<String, Object>> entries = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(historyFile);
            for (String line : lines) {
                if (line.isBlank()) continue;
                // 简化版：只做很轻量解析
                Map<String, Object> entry = new HashMap<>();
                entry.put("cursor", extractInt(line, "\"cursor\":", 0));
                entry.put("timestamp", extractString(line, "\"timestamp\":\"", "\""));
                entry.put("content", extractString(line, "\"content\":", null));
                Object c = entry.get("cursor");
                if (((Integer) c) > sinceCursor) {
                    entries.add(entry);
                }
            }
        } catch (IOException ignored) {
        }
        return entries;
    }

    private void maybeMigrateLegacyHistory() {
        if (!Files.exists(legacyHistoryFile)) return;
        if (Files.exists(historyFile)) return;
        try {
            String text = Files.readString(legacyHistoryFile);
            if (!text.isBlank()) {
                appendHistory(text);
            }
        } catch (IOException ignored) {
        }
    }

    private static int extractInt(String line, String prefix, int defaultValue) {
        try {
            int idx = line.indexOf(prefix);
            if (idx < 0) return defaultValue;
            int start = idx + prefix.length();
            int end = line.indexOf(",", start);
            if (end < 0) end = line.indexOf("}", start);
            return Integer.parseInt(line.substring(start, end).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String extractString(String line, String prefix, String suffix) {
        try {
            int idx = line.indexOf(prefix);
            if (idx < 0) return "";
            int start = idx + prefix.length();
            if (suffix == null) {
                return line.substring(start).trim();
            }
            int end = line.indexOf(suffix, start);
            return end >= 0 ? line.substring(start, end) : line.substring(start);
        } catch (Exception e) {
            return "";
        }
    }

    private static String jsonEscape(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}