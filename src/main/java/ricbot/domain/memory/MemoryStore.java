package ricbot.domain.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.common.HelperUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** File-backed structured memory and current history recall. */
public final class MemoryStore {
    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final Path memoryFile;
    private final Path memoryEntriesFile;
    private final Path historyFile;
    private final Path soulFile;
    private final Path userFile;
    private final Path cursorFile;
    private final MemoryRetriever memoryRetriever = new MemoryRetriever();
    private final Object historyLock = new Object();

    public MemoryStore(Path workspace) {
        Path root = workspace.toAbsolutePath().normalize();
        Path memoryDir = HelperUtils.ensureDir(root.resolve("memory"));
        memoryFile = memoryDir.resolve("MEMORY.md");
        memoryEntriesFile = memoryDir.resolve("memory_entries.jsonl");
        historyFile = memoryDir.resolve("history.jsonl");
        cursorFile = memoryDir.resolve(".cursor");
        soulFile = root.resolve("SOUL.md");
        userFile = root.resolve("USER.md");
        ensureSeedFile(memoryFile, "templates/memory/MEMORY.md");
        ensureSeedFile(userFile, "templates/USER.md");
        ensureSeedFile(soulFile, "templates/SOUL.md");
    }

    public String readMemory() { return readFile(memoryFile); }

    public String readUser() { return readFile(userFile); }

    public List<MemoryEntry> readMemoryEntries() {
        if (!Files.exists(memoryEntriesFile)) return new ArrayList<>();
        List<MemoryEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(memoryEntriesFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    Map<String, Object> value = MAPPER.readValue(line, new TypeReference<>() {});
                    entries.add(MemoryEntry.fromMap(value));
                }
            }
        } catch (Exception e) {
            log.warn("读取结构化记忆失败: {}", memoryEntriesFile, e);
        }
        return entries;
    }

    public void writeMemoryEntries(List<MemoryEntry> entries) {
        try {
            StringBuilder out = new StringBuilder();
            for (MemoryEntry entry : entries != null ? entries : List.<MemoryEntry>of()) {
                if (entry != null) out.append(MAPPER.writeValueAsString(entry.toMap())).append('\n');
            }
            Files.writeString(memoryEntriesFile, out.toString(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("写入结构化记忆失败: " + memoryEntriesFile, e);
        }
    }

    public List<MemoryEntry> mergeMemoryEntries(List<MemoryEntry> additions) {
        Map<String, MemoryEntry> merged = new LinkedHashMap<>();
        for (MemoryEntry entry : readMemoryEntries()) merged.put(entry.dedupeKey(), entry);
        for (MemoryEntry addition : additions != null ? additions : List.<MemoryEntry>of()) {
            if (addition == null || addition.getSummary().isBlank()) continue;
            MemoryEntry current = merged.get(addition.dedupeKey());
            if (current == null) {
                addition.touch();
                merged.put(addition.dedupeKey(), addition);
                continue;
            }
            current.setImportance(Math.max(current.getImportance(), addition.getImportance()));
            current.setConfidence(Math.max(current.getConfidence(), addition.getConfidence()));
            if (current.getDetails().isBlank()) current.setDetails(addition.getDetails());
            if (MemoryEntry.SCOPE_LONG_TERM.equals(addition.getScope())) current.setScope(addition.getScope());
            if (MemoryEntry.STATUS_DISCARDED.equals(addition.getStatus())) current.setStatus(addition.getStatus());
            current.setAliases(mergeStrings(current.getAliases(), addition.getAliases()));
            current.setTags(mergeStrings(current.getTags(), addition.getTags()));
            current.touch();
        }
        List<MemoryEntry> result = new ArrayList<>(merged.values());
        writeMemoryEntries(result);
        rebuildMarkdownViews(result);
        return result;
    }

    public void rebuildMarkdownViewsIfNeeded() {
        if (!Files.exists(memoryEntriesFile) || !markdownViewsNeedRebuild()) return;
        rebuildMarkdownViews(readMemoryEntries());
    }

    public List<MemoryEntry> recallMemories(String query, String taskGoal, int limit) {
        return recallScoredMemories(query, taskGoal, limit).stream()
                .map(MemoryRetriever.ScoredMemory::entry).toList();
    }

    public List<MemoryRetriever.ScoredMemory> recallScoredMemories(String query, String taskGoal, int limit) {
        List<MemoryEntry> entries = readMemoryEntries();
        List<MemoryRetriever.ScoredMemory> selected = memoryRetriever.score(entries, query, taskGoal).stream()
                .limit(Math.max(0, limit)).toList();
        if (!selected.isEmpty()) {
            Set<String> selectedIds = new java.util.HashSet<>();
            selected.forEach(item -> selectedIds.add(item.entry().getId()));
            entries.forEach(entry -> { if (selectedIds.contains(entry.getId())) entry.markUsed(); });
            writeMemoryEntries(entries);
        }
        return selected;
    }

    public void appendSessionSummary(String content) {
        appendHistoryEntry("session_summary", content != null ? content : "");
    }

    public void rawArchive(List<Map<String, Object>> messages) {
        appendHistoryEntry("raw_archive", Map.of(
                "messages_count", messages != null ? messages.size() : 0,
                "messages", messages != null ? messages : List.of()));
    }

    public List<String> recallArchivedHistory(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0 || !Files.exists(historyFile)) return List.of();
        Set<String> queryTokens = tokenize(query);
        List<ScoredHistory> matches = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(historyFile)) {
            String line;
            int order = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, Object> row = MAPPER.readValue(line, new TypeReference<>() {});
                String content = renderHistory(String.valueOf(row.getOrDefault("type", "")), row.get("content"));
                long hits = tokenize(content).stream().filter(queryTokens::contains).count();
                if (hits > 0) matches.add(new ScoredHistory(content, hits + order * 0.001d));
                order++;
            }
        } catch (Exception e) {
            log.warn("读取历史召回失败: {}", historyFile, e);
        }
        return matches.stream().sorted((a, b) -> Double.compare(b.score(), a.score())).limit(limit)
                .map(item -> HelperUtils.truncateText(item.content(), 260)).toList();
    }

    private void rebuildMarkdownViews(List<MemoryEntry> entries) {
        List<String> memory = new ArrayList<>();
        List<String> user = new ArrayList<>();
        List<String> soul = new ArrayList<>();
        for (MemoryEntry entry : entries != null ? entries : List.<MemoryEntry>of()) {
            if (!entry.isActive() || MemoryEntry.SCOPE_DISCARDABLE.equals(entry.getScope())) continue;
            if (entry.isSoulEntry()) soul.add(entry.renderLine());
            else if (entry.isUserProfile()) user.add(entry.renderLine());
            else memory.add(entry.renderLine());
        }
        try {
            Files.writeString(memoryFile, renderMarkdown("MEMORY", memory));
            Files.writeString(userFile, renderMarkdown("USER", user));
            Files.writeString(soulFile, renderMarkdown("SOUL", soul));
        } catch (IOException e) {
            throw new IllegalStateException("更新记忆视图失败", e);
        }
    }

    private void appendHistoryEntry(String type, Object content) {
        synchronized (historyLock) {
            try {
                int cursor = readCursor() + 1;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cursor", cursor);
                row.put("timestamp", Instant.now().toString());
                row.put("type", type);
                row.put("content", content);
                Files.writeString(historyFile, MAPPER.writeValueAsString(row) + '\n',
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Files.writeString(cursorFile, String.valueOf(cursor), StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("写入历史失败: " + historyFile, e);
            }
        }
    }

    private int readCursor() {
        try { return Files.exists(cursorFile) ? Integer.parseInt(Files.readString(cursorFile).trim()) : 0; }
        catch (Exception ignored) { return 0; }
    }

    private String renderHistory(String type, Object raw) {
        if ("session_summary".equals(type)) return "session summary: " + String.valueOf(raw);
        if (!"raw_archive".equals(type) || !(raw instanceof Map<?, ?> archive)) return "";
        int count = archive.get("messages_count") instanceof Number number ? number.intValue() : 0;
        List<String> snippets = new ArrayList<>();
        if (archive.get("messages") instanceof List<?> messages) {
            for (Object value : messages) {
                if (!(value instanceof Map<?, ?> message)) continue;
                String content = normalizeContent(message.get("content"));
                Object role = message.containsKey("role") ? message.get("role") : "message";
                if (!content.isBlank()) snippets.add(String.valueOf(role) + ": " + content);
                if (snippets.size() == 4) break;
            }
        }
        return "archived session" + (count > 0 ? " (" + count + " messages)" : "")
                + (snippets.isEmpty() ? "" : ": " + String.join(" | ", snippets));
    }

    private static String normalizeContent(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return HelperUtils.truncateText(text.trim(), 120);
        return HelperUtils.truncateText(String.valueOf(value).trim(), 120);
    }

    private boolean markdownViewsNeedRebuild() {
        try {
            long source = Files.getLastModifiedTime(memoryEntriesFile).toMillis();
            return !Files.exists(memoryFile) || !Files.exists(userFile) || !Files.exists(soulFile)
                    || Files.getLastModifiedTime(memoryFile).toMillis() < source
                    || Files.getLastModifiedTime(userFile).toMillis() < source
                    || Files.getLastModifiedTime(soulFile).toMillis() < source;
        } catch (IOException e) {
            return true;
        }
    }

    private static String renderMarkdown(String title, List<String> lines) {
        return "# " + title + "\n\n" + (lines.isEmpty() ? "_暂无结构化记忆条目。_\n"
                : String.join("\n", lines) + "\n");
    }

    private static List<String> mergeStrings(List<String> left, List<String> right) {
        Set<String> merged = new LinkedHashSet<>(left != null ? left : List.of());
        if (right != null) merged.addAll(right);
        return new ArrayList<>(merged);
    }

    private static String readFile(Path path) {
        try { return Files.readString(path); }
        catch (IOException e) { return ""; }
    }

    private static void ensureSeedFile(Path target, String resource) {
        if (Files.exists(target)) return;
        try (InputStream input = MemoryStore.class.getClassLoader().getResourceAsStream(resource)) {
            Files.createDirectories(target.getParent());
            Files.write(target, input != null ? input.readAllBytes() : new byte[0]);
        } catch (IOException e) {
            throw new IllegalStateException("初始化记忆文件失败: " + target, e);
        }
    }

    private static Set<String> tokenize(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return result;
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}_]+")) {
            if (token.length() >= 2) result.add(token);
        }
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i <= normalized.length(); i++) {
            char ch = i < normalized.length() ? normalized.charAt(i) : ' ';
            if (i < normalized.length() && Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                cjk.append(ch);
            } else {
                for (int n : List.of(2, 3)) for (int start = 0; start + n <= cjk.length(); start++) {
                    result.add(cjk.substring(start, start + n));
                }
                cjk.setLength(0);
            }
        }
        return result;
    }

    private record ScoredHistory(String content, double score) {}
}
