package ricbot.domain.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Small durable vector index suitable for workspace/tenant-local retrieval. */
public final class FileVectorIndex {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, double[]>> TYPE = new TypeReference<>() { };
    private final Path file;
    private final Map<String, double[]> vectors;
    private boolean dirty;

    public FileVectorIndex(Path directory, String modelId) {
        if (directory == null) throw new IllegalArgumentException("directory is required");
        this.file = directory.resolve(hash(required(modelId)) + ".json");
        this.vectors = load();
    }

    public synchronized Optional<double[]> get(String key) {
        double[] vector = vectors.get(required(key));
        return vector != null ? Optional.of(vector.clone()) : Optional.empty();
    }

    public synchronized double[] getOrCompute(String key, Supplier<double[]> supplier) {
        String clean = required(key);
        double[] existing = vectors.get(clean);
        if (existing != null) return existing.clone();
        double[] computed = java.util.Objects.requireNonNull(supplier.get(), "embedding").clone();
        if (computed.length == 0) throw new IllegalArgumentException("embedding must not be empty");
        vectors.put(clean, computed);
        dirty = true;
        return computed.clone();
    }

    public synchronized void retainOnly(java.util.Set<String> keys) {
        if (vectors.keySet().removeIf(key -> keys == null || !keys.contains(key))) dirty = true;
    }

    public synchronized int size() {
        return vectors.size();
    }

    public synchronized void flush() {
        if (!dirty) return;
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.write(temporary, MAPPER.writeValueAsBytes(vectors));
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (Exception e) {
            throw new IllegalStateException("failed to persist vector index", e);
        } finally {
            try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }

    private Map<String, double[]> load() {
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(MAPPER.readValue(file.toFile(), TYPE));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load vector index", e);
        }
    }

    private static String required(String value) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("value is required");
        return clean;
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
