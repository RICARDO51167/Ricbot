package ricbot.domain.note;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.common.HelperUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class NoteService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<Map<String, Object>>> INDEX_TYPE = new TypeReference<>() {
    };

    private final Path workspace;
    private final Path notesDir;
    private final Path indexFile;

    public NoteService(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.notesDir = this.workspace.resolve("notes");
        this.indexFile = notesDir.resolve("index.json");
        ensureLayout();
    }

    public NoteEntry create(String title, String category, String type, String content, List<String> tags) {
        String now = Instant.now().toString();
        String normalizedCategory = NoteEntry.normalizeCategory(category);
        String normalizedType = NoteEntry.normalizeType(type);
        String safeTitle = safeSlug(title != null && !title.isBlank() ? title : normalizedType);
        String id = normalizedCategory + "_" + normalizedType + "_" + safeTitle + "_" + now.replaceAll("[^0-9]", "");
        Path path = uniquePath(notesDir.resolve(normalizedCategory), id + ".md");
        NoteEntry entry = new NoteEntry(
                id,
                title != null && !title.isBlank() ? title.trim() : normalizedType,
                normalizedCategory,
                normalizedType,
                workspace.relativize(path).toString(),
                dedupe(tags),
                now,
                now,
                false
        );
        writeNoteFile(path, entry, content);
        List<NoteEntry> entries = new ArrayList<>(list());
        entries.add(entry);
        writeIndex(entries);
        return entry;
    }

    public NoteEntry update(String id, String content, String append) {
        NoteEntry entry = findById(id);
        if (entry == null) {
            throw new IllegalArgumentException("note not found: " + id);
        }
        Path path = workspace.resolve(entry.path()).toAbsolutePath().normalize();
        String next = content != null ? content : readBody(path);
        if (append != null && !append.isBlank()) {
            next = next.stripTrailing() + "\n\n" + append.trim() + "\n";
        }
        NoteEntry updated = new NoteEntry(
                entry.id(),
                entry.title(),
                entry.category(),
                entry.type(),
                entry.path(),
                entry.tags(),
                entry.createdAt(),
                Instant.now().toString(),
                entry.archived()
        );
        writeNoteFile(path, updated, next);
        replaceIndexEntry(updated);
        return updated;
    }

    public NoteEntry appendProjectFile(String fileName, String title, String type, String append, List<String> tags) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\") || !fileName.endsWith(".md")) {
            throw new IllegalArgumentException("invalid project note file: " + fileName);
        }
        String path = "notes/project/" + fileName;
        NoteEntry existing = findByPath(path);
        if (existing == null) {
            String now = Instant.now().toString();
            existing = new NoteEntry(
                    "project_" + fileName.substring(0, fileName.length() - 3).replaceAll("[^A-Za-z0-9_]+", "_"),
                    title != null && !title.isBlank() ? title.trim() : fileName,
                    "project",
                    NoteEntry.normalizeType(type),
                    path,
                    dedupe(tags),
                    now,
                    now,
                    false
            );
            writeNoteFile(workspace.resolve(path).toAbsolutePath().normalize(), existing, append);
            List<NoteEntry> entries = new ArrayList<>(list());
            entries.add(existing);
            writeIndex(entries);
            return existing;
        }
        return update(existing.id(), null, append);
    }

    public List<NoteEntry> list() {
        ensureLayout();
        if (!Files.exists(indexFile)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(Files.readString(indexFile), INDEX_TYPE);
            List<NoteEntry> out = new ArrayList<>();
            for (Map<String, Object> row : raw) {
                NoteEntry entry = NoteEntry.fromMap(row);
                if (entry != null && entry.id() != null && !entry.id().isBlank()) {
                    out.add(entry);
                }
            }
            out.sort(Comparator.comparing(NoteEntry::updatedAt, Comparator.nullsLast(String::compareTo)).reversed());
            return out;
        } catch (Exception e) {
            throw new RuntimeException("read notes index failed: " + indexFile, e);
        }
    }

    public List<SearchResult> search(String query, int limit) {
        Set<String> queryTokens = tokenize(query);
        List<SearchResult> scored = new ArrayList<>();
        for (NoteEntry entry : list()) {
            if (entry.archived()) {
                continue;
            }
            Path path = workspace.resolve(entry.path()).toAbsolutePath().normalize();
            String content = readBody(path);
            double score = overlap(queryTokens, tokenize(entry.title() + " " + String.join(" ", entry.tags()))) * 3.0d
                    + overlap(queryTokens, tokenize(content)) * 2.0d;
            if (queryTokens.isEmpty()) {
                score = 0.1d;
            }
            if (score <= 0d) {
                continue;
            }
            scored.add(new SearchResult(entry, score, snippet(content, queryTokens)));
        }
        scored.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return scored.stream().limit(Math.max(1, limit)).toList();
    }

    public String summary(String id) {
        NoteEntry entry = findById(id);
        if (entry == null) {
            throw new IllegalArgumentException("note not found: " + id);
        }
        String body = readBody(workspace.resolve(entry.path()).toAbsolutePath().normalize());
        return entry.title() + "\n" + HelperUtils.truncateText(body.strip(), 800);
    }

    public NoteEntry promote(String id, String category) {
        NoteEntry entry = findById(id);
        if (entry == null) {
            throw new IllegalArgumentException("note not found: " + id);
        }
        String nextCategory = NoteEntry.normalizeCategory(category);
        Path oldPath = workspace.resolve(entry.path()).toAbsolutePath().normalize();
        Path nextPath = uniquePath(notesDir.resolve(nextCategory), Path.of(entry.path()).getFileName().toString());
        try {
            Files.createDirectories(nextPath.getParent());
            Files.move(oldPath, nextPath);
        } catch (IOException e) {
            throw new RuntimeException("promote note failed: " + id, e);
        }
        NoteEntry promoted = new NoteEntry(
                entry.id(),
                entry.title(),
                nextCategory,
                entry.type(),
                workspace.relativize(nextPath).toString(),
                entry.tags(),
                entry.createdAt(),
                Instant.now().toString(),
                false
        );
        replaceIndexEntry(promoted);
        return promoted;
    }

    public NoteEntry archive(String id) {
        NoteEntry entry = findById(id);
        if (entry == null) {
            throw new IllegalArgumentException("note not found: " + id);
        }
        Path oldPath = workspace.resolve(entry.path()).toAbsolutePath().normalize();
        Path nextPath = uniquePath(notesDir.resolve("archive"), Path.of(entry.path()).getFileName().toString());
        try {
            Files.createDirectories(nextPath.getParent());
            Files.move(oldPath, nextPath);
        } catch (IOException e) {
            throw new RuntimeException("archive note failed: " + id, e);
        }
        NoteEntry archived = entry.withPath(workspace.relativize(nextPath).toString()).withArchived(true);
        replaceIndexEntry(archived);
        return archived;
    }

    public boolean delete(String id) {
        List<NoteEntry> entries = new ArrayList<>(list());
        NoteEntry found = null;
        for (NoteEntry entry : entries) {
            if (entry.id().equals(id)) {
                found = entry;
                break;
            }
        }
        if (found == null) {
            return false;
        }
        try {
            Files.deleteIfExists(workspace.resolve(found.path()).toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new RuntimeException("delete note failed: " + id, e);
        }
        entries.removeIf(entry -> entry.id().equals(id));
        writeIndex(entries);
        return true;
    }

    private NoteEntry findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (NoteEntry entry : list()) {
            if (id.equals(entry.id())) {
                return entry;
            }
        }
        return null;
    }

    private NoteEntry findByPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        for (NoteEntry entry : list()) {
            if (normalized.equals(entry.path().replace('\\', '/'))) {
                return entry;
            }
        }
        return null;
    }

    private void replaceIndexEntry(NoteEntry updated) {
        List<NoteEntry> entries = new ArrayList<>(list());
        boolean replaced = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(updated.id())) {
                entries.set(i, updated);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            entries.add(updated);
        }
        writeIndex(entries);
    }

    private void writeIndex(List<NoteEntry> entries) {
        ensureLayout();
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (NoteEntry entry : entries != null ? entries : List.<NoteEntry>of()) {
                rows.add(entry.toMap());
            }
            Files.writeString(indexFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rows), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("write notes index failed: " + indexFile, e);
        }
    }

    private void writeNoteFile(Path path, NoteEntry entry, String content) {
        try {
            Files.createDirectories(path.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("id: ").append(entry.id()).append("\n");
            sb.append("title: ").append(entry.title()).append("\n");
            sb.append("category: ").append(entry.category()).append("\n");
            sb.append("type: ").append(entry.type()).append("\n");
            sb.append("tags: ").append(String.join(", ", entry.tags())).append("\n");
            sb.append("created_at: ").append(entry.createdAt()).append("\n");
            sb.append("updated_at: ").append(entry.updatedAt()).append("\n");
            sb.append("---\n\n");
            sb.append(content != null ? content.strip() : "");
            sb.append("\n");
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("write note failed: " + path, e);
        }
    }

    private String readBody(Path path) {
        try {
            if (!Files.exists(path)) {
                return "";
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.startsWith("---")) {
                int end = text.indexOf("\n---", 3);
                if (end >= 0) {
                    return text.substring(end + 4).stripLeading();
                }
            }
            return text;
        } catch (IOException e) {
            return "";
        }
    }

    private void ensureLayout() {
        try {
            Files.createDirectories(notesDir.resolve("project"));
            Files.createDirectories(notesDir.resolve("tasks"));
            Files.createDirectories(notesDir.resolve("blockers"));
            Files.createDirectories(notesDir.resolve("temporary"));
            Files.createDirectories(notesDir.resolve("archive"));
            if (!Files.exists(indexFile)) {
                Files.writeString(indexFile, "[]\n", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new RuntimeException("create notes layout failed: " + notesDir, e);
        }
    }

    private Path uniquePath(Path dir, String fileName) {
        Path candidate = dir.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String name = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        for (int i = 2; i < 1000; i++) {
            Path next = dir.resolve(name + "_" + i + ".md");
            if (!Files.exists(next)) {
                return next;
            }
        }
        return dir.resolve(name + "_" + UUID.randomUUID() + ".md");
    }

    private String safeSlug(String value) {
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (ascii.isBlank()) {
            ascii = HelperUtils.safeFilename(value).replaceAll("\\s+", "_");
        }
        return ascii.isBlank() ? "note" : ascii;
    }

    private List<String> dedupe(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values != null ? values : List.<String>of()) {
            if (value != null && !value.isBlank()) {
                seen.add(value.trim());
            }
        }
        return new ArrayList<>(seen);
    }

    private Set<String> tokenize(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}_]+")) {
            if (token.length() >= 2) {
                out.add(token);
            }
        }
        return out;
    }

    private double overlap(Set<String> queryTokens, Set<String> contentTokens) {
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
            return 0d;
        }
        long hits = contentTokens.stream().filter(queryTokens::contains).count();
        return (double) hits / Math.max(1, queryTokens.size());
    }

    private String snippet(String content, Set<String> queryTokens) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String[] lines = content.strip().split("\\R");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (String token : queryTokens) {
                if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                    return HelperUtils.truncateText(line.strip(), 220);
                }
            }
        }
        return HelperUtils.truncateText(content.strip(), 220);
    }

    public record SearchResult(NoteEntry entry, double score, String snippet) {
    }
}
