package ricbot.domain.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.common.HelperUtils;
import ricbot.tool.filesystem.FileToolSupport;
import ricbot.domain.retrieval.EmbeddingProvider;
import ricbot.domain.retrieval.HashingEmbeddingProvider;
import ricbot.domain.retrieval.HybridScoring;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
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
    private final String tenantId;
    private final EmbeddingProvider embeddingProvider;
    private final Map<String, double[]> embeddingCache = new java.util.concurrent.ConcurrentHashMap<>();

    public WorkspaceRagService(Path workspace) {
        this(workspace, "default", new HashingEmbeddingProvider());
    }

    public WorkspaceRagService(Path workspace, String tenantId, EmbeddingProvider embeddingProvider) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.tenantId = tenantId != null && !tenantId.isBlank() ? tenantId.trim() : "default";
        this.embeddingProvider = embeddingProvider != null ? embeddingProvider : new HashingEmbeddingProvider();
        Path baseRag = this.workspace.resolve(".rag");
        this.ragDir = "default".equals(this.tenantId)
                ? baseRag
                : baseRag.resolve("tenants").resolve(sha256(this.tenantId));
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
        Map<String, FileState> fileStates = new LinkedHashMap<>();
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
                fileStates.put(relative(path), fileState(path, fileChunks.size()));
            }
            writeChunks(chunks);
            writeJson(symbolIndexFile, symbols);
            writeScanState(fileStates, files, chunks.size(), 0, 0, 0, 0);
            return new IndexReport(files, chunks.size(), symbols.size(), chunksFile.toString(), files, 0, 0, 0);
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
        ensureLayout();
        Map<String, FileState> previous = readFileStates();
        if (previous.isEmpty() || !Files.exists(chunksFile)) {
            return indexWorkspace();
        }

        Map<String, Path> currentFiles = discoverIndexableFiles();
        Map<String, FileState> nextStates = new LinkedHashMap<>();
        Set<String> changedPaths = new LinkedHashSet<>();
        int added = 0;
        int modified = 0;
        int deleted = 0;
        int skipped = 0;

        for (Map.Entry<String, Path> entry : currentFiles.entrySet()) {
            String rel = entry.getKey();
            Path path = entry.getValue();
            FileState current = fileState(path, 0);
            FileState old = previous.get(rel);
            if (old == null) {
                added++;
                changedPaths.add(rel);
            } else if (!old.sameFingerprint(current)) {
                modified++;
                changedPaths.add(rel);
            } else {
                skipped++;
                current = old;
            }
            nextStates.put(rel, current);
        }

        for (String rel : previous.keySet()) {
            if (!currentFiles.containsKey(rel)) {
                deleted++;
                changedPaths.add(rel);
            }
        }

        List<FileChunk> nextChunks = new ArrayList<>();
        Map<String, List<String>> symbols = readSymbolIndex();
        symbols.keySet().removeIf(changedPaths::contains);

        for (FileChunk chunk : readChunks()) {
            if (!changedPaths.contains(chunk.path())) {
                nextChunks.add(chunk);
            }
        }

        for (String rel : changedPaths) {
            Path path = currentFiles.get(rel);
            if (path == null) {
                nextStates.remove(rel);
                continue;
            }
            List<FileChunk> fileChunks = chunkFile(path);
            nextChunks.addAll(fileChunks);
            List<String> fileSymbols = extractSymbols(path);
            if (!fileSymbols.isEmpty()) {
                symbols.put(rel, fileSymbols);
            }
            nextStates.put(rel, fileState(path, fileChunks.size()));
        }

        nextChunks.sort(Comparator.comparing(FileChunk::path).thenComparingInt(FileChunk::startLine));
        try {
            writeChunks(nextChunks);
            writeJson(symbolIndexFile, symbols);
            writeScanState(nextStates, nextStates.size(), nextChunks.size(), added, modified, deleted, skipped);
            return new IndexReport(nextStates.size(), nextChunks.size(), symbols.size(), chunksFile.toString(), added, modified, deleted, skipped);
        } catch (IOException e) {
            throw new RuntimeException("refresh changed files failed: " + workspace, e);
        }
    }

    private List<SearchResult> search(String query, int limit, Set<String> kinds) {
        ensureIndex();
        Set<String> queryTokens = tokenize(query);
        double[] queryEmbedding = embeddingProvider.embed(query);
        List<SearchResult> results = new ArrayList<>();
        for (FileChunk chunk : readChunks()) {
            if (!kinds.contains(chunk.kind())) {
                continue;
            }
            double lexicalScore = score(queryTokens, chunk);
            double vectorScore = Math.max(0d, HybridScoring.cosine(
                    queryEmbedding,
                    embeddingCache.computeIfAbsent(
                            chunk.id() + ":" + chunk.updatedAt(),
                            ignored -> embeddingProvider.embed(chunk.path() + "\n" + String.join(" ", chunk.symbols()) + "\n" + chunk.text())
                    )
            ));
            double score = (lexicalScore * 0.72d) + (vectorScore * 2.4d);
            if (queryTokens.isEmpty()) {
                score = 0.05d;
            }
            if (score <= 0d) {
                continue;
            }
            results.add(new SearchResult(chunk, score, lexicalScore, vectorScore,
                    snippet(chunk.text(), queryTokens)));
        }
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results.stream().limit(Math.max(1, limit)).toList();
    }

    private Map<String, Path> discoverIndexableFiles() {
        Map<String, Path> out = new LinkedHashMap<>();
        try (var stream = Files.walk(workspace)) {
            for (Path path : stream.toList()) {
                if (!shouldIndex(path)) {
                    continue;
                }
                out.put(relative(path), path.toAbsolutePath().normalize());
            }
        } catch (IOException e) {
            throw new RuntimeException("discover indexable files failed: " + workspace, e);
        }
        return out;
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

    private Map<String, List<String>> readSymbolIndex() {
        Map<String, Object> raw = readJson(symbolIndexFile);
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            List<String> values = new ArrayList<>();
            if (entry.getValue() instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        values.add(String.valueOf(item));
                    }
                }
            }
            if (!values.isEmpty()) {
                out.put(entry.getKey(), values);
            }
        }
        return out;
    }

    private Map<String, FileState> readFileStates() {
        Map<String, Object> raw = readJson(scanStateFile);
        Object rawFiles = raw.get("files");
        if (!(rawFiles instanceof Map<?, ?> fileMap)) {
            return Map.of();
        }
        Map<String, FileState> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : fileMap.entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> stateMap)) {
                continue;
            }
            FileState state = FileState.fromMap(stateMap);
            if (state != null) {
                out.put(String.valueOf(entry.getKey()), state);
            }
        }
        return out;
    }

    private void writeScanState(
            Map<String, FileState> fileStates,
            int filesCount,
            int chunksCount,
            int added,
            int modified,
            int deleted,
            int skipped
    ) {
        Map<String, Object> files = new LinkedHashMap<>();
        for (Map.Entry<String, FileState> entry : fileStates.entrySet()) {
            files.put(entry.getKey(), entry.getValue().toMap());
        }
        writeJson(scanStateFile, Map.of(
                "indexed_at", Instant.now().toString(),
                "files_count", filesCount,
                "chunks_count", chunksCount,
                "mode", "keyword_path_mtime_sha256",
                "vector_index", workspace.relativize(vectorIndexDir).toString(),
                "last_refresh", Map.of(
                        "added", added,
                        "modified", modified,
                        "deleted", deleted,
                        "skipped", skipped
                ),
                "files", files
        ));
    }

    private FileState fileState(Path path, int chunkCount) {
        try {
            return new FileState(
                    relative(path),
                    Files.size(path),
                    Files.getLastModifiedTime(path).toMillis(),
                    sha256(path),
                    chunkCount,
                    Instant.now().toString()
            );
        } catch (IOException e) {
            throw new RuntimeException("read file state failed: " + path, e);
        }
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("sha256 failed: " + path, e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
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

    public record IndexReport(int files, int chunks, int symbols, String chunksFile, int added, int modified, int deleted, int skipped) {
        public IndexReport(int files, int chunks, int symbols, String chunksFile) {
            this(files, chunks, symbols, chunksFile, files, 0, 0, 0);
        }
    }

    public String tenantId() { return tenantId; }
    public String embeddingModelId() { return embeddingProvider.modelId(); }

    public record SearchResult(
            FileChunk chunk,
            double score,
            double lexicalScore,
            double vectorScore,
            String snippet
    ) {
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

    private record FileState(
            String path,
            long size,
            long lastModified,
            String sha256,
            int chunkCount,
            String indexedAt
    ) {
        boolean sameFingerprint(FileState other) {
            return other != null
                    && size == other.size
                    && lastModified == other.lastModified
                    && String.valueOf(sha256).equals(other.sha256);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("size", size);
            out.put("lastModified", lastModified);
            out.put("sha256", sha256);
            out.put("chunkCount", chunkCount);
            out.put("indexedAt", indexedAt);
            return out;
        }

        static FileState fromMap(Map<?, ?> raw) {
            if (raw == null) {
                return null;
            }
            return new FileState(
                    string(raw.get("path")),
                    longValue(raw.get("size")),
                    longValue(raw.get("lastModified")),
                    string(raw.get("sha256")),
                    intValue(raw.get("chunkCount")),
                    string(raw.get("indexedAt"))
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

        private static long longValue(Object raw) {
            if (raw instanceof Number n) {
                return n.longValue();
            }
            if (raw != null) {
                try {
                    return Long.parseLong(String.valueOf(raw));
                } catch (Exception ignored) {
                }
            }
            return 0L;
        }
    }
}
