package ricbot.domain.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.common.HelperUtils;
import ricbot.tool.filesystem.FileToolSupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class WorkspaceRagService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int CHUNK_LINES = 80;
    private static final int MAX_CHUNKS_PER_FILE = 80;

    private final Path workspace;
    private final Path ragDir;
    private final Path codeIndexDir;
    private final Path chunksFile;
    private final Path symbolIndexFile;
    private final Path scanStateFile;
    private final Path vectorIndexDir;

    public WorkspaceRagService(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.ragDir = this.workspace.resolve(".rag");
        this.codeIndexDir = ragDir.resolve("code_index");
        this.chunksFile = codeIndexDir.resolve("file_chunks.jsonl");
        this.symbolIndexFile = codeIndexDir.resolve("symbol_index.json");
        this.scanStateFile = codeIndexDir.resolve("last_scan_state.json");
        this.vectorIndexDir = codeIndexDir.resolve("vector_index");
        ensureLayout();
    }

    public IndexReport indexWorkspace() {
        ensureLayout();
        List<FileChunk> chunks = new ArrayList<>();
        Map<String, List<String>> symbols = new LinkedHashMap<>();
        int files = 0;
        try (var stream = Files.walk(workspace)) {
            for (Path path : stream.toList()) {
                if (!shouldIndex(path)) {
                    continue;
                }
                files++;
                List<FileChunk> fileChunks = chunkFile(path);
                chunks.addAll(fileChunks);
                List<String> fileSymbols = extractSymbols(path);
                if (!fileSymbols.isEmpty()) {
                    symbols.put(relative(path), fileSymbols);
                }
            }
            writeChunks(chunks);
            writeJson(symbolIndexFile, symbols);
            writeJson(scanStateFile, Map.of(
                    "indexed_at", Instant.now().toString(),
                    "files", files,
                    "chunks", chunks.size(),
                    "mode", "keyword_path_mtime",
                    "vector_index", workspace.relativize(vectorIndexDir).toString()
            ));
            return new IndexReport(files, chunks.size(), symbols.size(), chunksFile.toString());
        } catch (IOException e) {
            throw new RuntimeException("index workspace failed: " + workspace, e);
        }
    }

    public List<SearchResult> searchCode(String query, int limit) {
        return search(query, limit, Set.of("code"));
    }

    public List<SearchResult> searchDocs(String query, int limit) {
        return search(query, limit, Set.of("docs", "notes"));
    }

    public List<SearchResult> searchProjectKnowledge(String query, int limit) {
        return search(query, limit, Set.of("code", "docs", "notes"));
    }

    public List<SearchResult> findRelatedFiles(String query, int limit) {
        Map<String, SearchResult> byFile = new LinkedHashMap<>();
        for (SearchResult result : searchProjectKnowledge(query, Math.max(20, limit * 5))) {
            SearchResult current = byFile.get(result.chunk().path());
            if (current == null || result.score() > current.score()) {
                byFile.put(result.chunk().path(), result);
            }
        }
        return byFile.values().stream()
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public String explainSymbol(String symbol) {
        Map<String, Object> raw = readJson(symbolIndexFile);
        if (raw.isEmpty()) {
            indexWorkspace();
            raw = readJson(symbolIndexFile);
        }
        String needle = symbol != null ? symbol.toLowerCase(Locale.ROOT) : "";
        List<String> matches = new ArrayList<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                String value = String.valueOf(item);
                if (value.toLowerCase(Locale.ROOT).contains(needle)) {
                    matches.add(entry.getKey() + ": " + value);
                }
            }
        }
        if (matches.isEmpty()) {
            return "未找到 symbol：" + symbol;
        }
        return String.join("\n", matches.stream().limit(30).toList());
    }

    public IndexReport refreshChangedFiles() {
        return indexWorkspace();
    }

    private List<SearchResult> search(String query, int limit, Set<String> kinds) {
        ensureIndex();
        Set<String> queryTokens = tokenize(query);
        List<SearchResult> results = new ArrayList<>();
        for (FileChunk chunk : readChunks()) {
            if (!kinds.contains(chunk.kind())) {
                continue;
            }
            double score = score(queryTokens, chunk);
            if (queryTokens.isEmpty()) {
                score = 0.05d;
            }
            if (score <= 0d) {
                continue;
            }
            results.add(new SearchResult(chunk, score, snippet(chunk.text(), queryTokens)));
        }
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results.stream().limit(Math.max(1, limit)).toList();
    }

    private double score(Set<String> queryTokens, FileChunk chunk) {
        double text = overlap(queryTokens, tokenize(chunk.text())) * 4.0d;
        double path = overlap(queryTokens, tokenize(chunk.path())) * 2.0d;
        double symbol = overlap(queryTokens, tokenize(String.join(" ", chunk.symbols()))) * 3.0d;
        double priority = sourcePriority(chunk.path(), chunk.kind());
        double recency = recency(chunk.updatedAt()) * 0.4d;
        return text + path + symbol + priority + recency;
    }

    private double sourcePriority(String path, String kind) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith("readme.md")) {
            return 1.2d;
        }
        if (lower.contains("/docs/") || "docs".equals(kind)) {
            return 0.8d;
        }
        if (lower.contains("/notes/") || "notes".equals(kind)) {
            return 0.7d;
        }
        if (lower.contains("/src/main/")) {
            return 0.4d;
        }
        return 0.1d;
    }

    private double recency(String updatedAt) {
        try {
            long ageDays = java.time.Duration.between(Instant.parse(updatedAt), Instant.now()).toDays();
            return 1.0d / (1.0d + Math.max(0, ageDays) / 30.0d);
        } catch (Exception ignored) {
            return 0d;
        }
    }

    private List<FileChunk> chunkFile(Path path) {
        List<FileChunk> out = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<String> symbols = extractSymbols(path);
            String kind = kindOf(path);
            String rel = relative(path);
            String updatedAt = Files.getLastModifiedTime(path).toInstant().toString();
            int chunkIndex = 0;
            for (int start = 0; start < lines.size() && chunkIndex < MAX_CHUNKS_PER_FILE; start += CHUNK_LINES) {
                int end = Math.min(lines.size(), start + CHUNK_LINES);
                String text = String.join("\n", lines.subList(start, end)).strip();
                if (text.isBlank()) {
                    continue;
                }
                out.add(new FileChunk(
                        rel + "#" + (chunkIndex + 1),
                        rel,
                        kind,
                        start + 1,
                        end,
                        text,
                        symbols,
                        updatedAt
                ));
                chunkIndex++;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<String> extractSymbols(Path path) {
        List<String> out = new ArrayList<>();
        String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
        if (!fileName.endsWith(".java")) {
            return out;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String stripped = line.strip();
                if (stripped.matches(".*\\b(class|interface|enum|record)\\s+[A-Za-z_][A-Za-z0-9_]*.*")
                        || stripped.matches(".*\\b(public|private|protected)\\s+.*\\)\\s*\\{?\\s*$")) {
                    out.add(lineNo + ": " + HelperUtils.truncateText(stripped, 180).replace("\n", " "));
                }
                if (out.size() >= 80) {
                    break;
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private boolean shouldIndex(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            return false;
        }
        String rel = relative(normalized).replace('\\', '/');
        if (rel.startsWith(".git/") || rel.startsWith("target/") || rel.startsWith(".rag/")
                || rel.startsWith(".idea/") || rel.contains("/node_modules/")) {
            return false;
        }
        if (isLikelyGenerated(rel)) {
            return false;
        }
        try {
            if (Files.size(normalized) > MAX_FILE_BYTES || FileToolSupport.isBinary(normalized)) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return matchesAny(rel,
                "**/*.java",
                "**/*.md",
                "**/*.txt",
                "**/*.json",
                "**/*.yaml",
                "**/*.yml",
                "**/*.xml",
                "README.md",
                "notes/**/*.md",
                "docs/**/*.md"
        );
    }

    private boolean isLikelyGenerated(String rel) {
        return rel.endsWith(".class")
                || rel.endsWith(".jar")
                || rel.endsWith(".png")
                || rel.endsWith(".jpg")
                || rel.endsWith(".jpeg")
                || rel.endsWith(".gif")
                || rel.endsWith(".webp");
    }

    private boolean matchesAny(String rel, String... globs) {
        Path relPath = Path.of(rel);
        for (String glob : globs) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            if (matcher.matches(relPath) || matcher.matches(Path.of(rel.replace("\\", "/")))) {
                return true;
            }
        }
        return false;
    }

    private String kindOf(Path path) {
        String rel = relative(path).replace('\\', '/').toLowerCase(Locale.ROOT);
        if (rel.startsWith("notes/")) {
            return "notes";
        }
        if (rel.endsWith(".md") || rel.startsWith("docs/") || rel.equals("readme.md")) {
            return "docs";
        }
        return "code";
    }

    private void ensureIndex() {
        if (!Files.exists(chunksFile)) {
            indexWorkspace();
        }
    }

    private void writeChunks(List<FileChunk> chunks) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (FileChunk chunk : chunks) {
            sb.append(MAPPER.writeValueAsString(chunk.toMap())).append("\n");
        }
        Files.writeString(chunksFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private List<FileChunk> readChunks() {
        if (!Files.exists(chunksFile)) {
            return List.of();
        }
        List<FileChunk> chunks = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(chunksFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                FileChunk chunk = FileChunk.fromMap(MAPPER.readValue(line, MAP_TYPE));
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("read rag chunks failed: " + chunksFile, e);
        }
        return chunks;
    }

    private void ensureLayout() {
        try {
            Files.createDirectories(codeIndexDir);
            Files.createDirectories(vectorIndexDir);
        } catch (IOException e) {
            throw new RuntimeException("create rag layout failed: " + codeIndexDir, e);
        }
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("write json failed: " + path, e);
        }
    }

    private Map<String, Object> readJson(Path path) {
        if (!Files.exists(path)) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(Files.readString(path), MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
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
        addCjkNgrams(normalized, out);
        return out;
    }

    private void addCjkNgrams(String text, Set<String> out) {
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                cjk.append(ch);
            } else {
                addNgrams(cjk, out);
                cjk.setLength(0);
            }
        }
        addNgrams(cjk, out);
    }

    private void addNgrams(StringBuilder cjk, Set<String> out) {
        int len = cjk.length();
        for (int n : List.of(2, 3)) {
            if (len < n) {
                continue;
            }
            for (int i = 0; i <= len - n; i++) {
                out.add(cjk.substring(i, i + n));
            }
        }
    }

    private boolean isCjk(char ch) {
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private double overlap(Set<String> queryTokens, Set<String> contentTokens) {
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
            return 0d;
        }
        long hits = contentTokens.stream().filter(queryTokens::contains).count();
        double queryCoverage = (double) hits / Math.max(1d, queryTokens.size());
        double contentCoverage = (double) hits / Math.max(1d, Math.min(contentTokens.size(), queryTokens.size() * 2));
        return (queryCoverage * 0.75d) + (contentCoverage * 0.25d);
    }

    private String snippet(String text, Set<String> queryTokens) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\\R");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (String token : queryTokens) {
                if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                    return HelperUtils.truncateText(line.strip(), 260);
                }
            }
        }
        return HelperUtils.truncateText(text.strip(), 260);
    }

    private String relative(Path path) {
        return workspace.relativize(path.toAbsolutePath().normalize()).toString();
    }

    public record IndexReport(int files, int chunks, int symbols, String chunksFile) {
    }

    public record SearchResult(FileChunk chunk, double score, String snippet) {
    }

    public record FileChunk(
            String id,
            String path,
            String kind,
            int startLine,
            int endLine,
            String text,
            List<String> symbols,
            String updatedAt
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("path", path);
            out.put("kind", kind);
            out.put("start_line", startLine);
            out.put("end_line", endLine);
            out.put("text", text);
            out.put("symbols", symbols != null ? symbols : List.of());
            out.put("updated_at", updatedAt);
            return out;
        }

        static FileChunk fromMap(Map<String, Object> raw) {
            if (raw == null) {
                return null;
            }
            return new FileChunk(
                    string(raw.get("id")),
                    string(raw.get("path")),
                    string(raw.get("kind")),
                    intValue(raw.get("start_line")),
                    intValue(raw.get("end_line")),
                    string(raw.get("text")),
                    stringList(raw.get("symbols")),
                    string(raw.get("updated_at"))
            );
        }

        private static String string(Object raw) {
            return raw != null ? String.valueOf(raw) : "";
        }

        private static int intValue(Object raw) {
            if (raw instanceof Number n) {
                return n.intValue();
            }
            if (raw != null) {
                try {
                    return Integer.parseInt(String.valueOf(raw));
                } catch (Exception ignored) {
                }
            }
            return 0;
        }

        private static List<String> stringList(Object raw) {
            List<String> out = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        out.add(String.valueOf(item));
                    }
                }
            }
            return out;
        }
    }
}
