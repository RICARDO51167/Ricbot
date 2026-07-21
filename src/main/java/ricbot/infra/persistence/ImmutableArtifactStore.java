package ricbot.infra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Content-addressed immutable Artifact storage. */
public final class ImmutableArtifactStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final Path root;

    public ImmutableArtifactStore(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        root = workspace.toAbsolutePath().normalize().resolve(".ricbot").resolve("artifacts").resolve("sha256");
    }

    public ArtifactReference putJson(String artifactType, Object value) {
        try {
            String type = required(artifactType, "artifactType");
            byte[] content = MAPPER.writeValueAsBytes(value != null ? value : Map.of());
            String digest = sha256(content);
            Path target = root.resolve(digest.substring(0, 2)).resolve(digest + ".json");
            if (!Files.isRegularFile(target)) writeOnce(target, content);
            return new ArtifactReference(
                    ArtifactReference.CURRENT_SCHEMA_VERSION,
                    "sha256:" + digest,
                    type,
                    "application/json",
                    digest,
                    content.length,
                    workspacePath(target),
                    Files.getLastModifiedTime(target).toInstant()
            );
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException argument) throw argument;
            throw new IllegalStateException("failed to persist immutable artifact", e);
        }
    }

    public byte[] read(ArtifactReference reference) {
        if (reference == null) throw new IllegalArgumentException("artifact reference is required");
        Path target = root.resolve(reference.sha256().substring(0, 2)).resolve(reference.sha256() + ".json");
        try {
            byte[] content = Files.readAllBytes(target);
            if (!reference.sha256().equals(sha256(content))) {
                throw new IllegalStateException("artifact digest mismatch: " + reference.artifactId());
            }
            return content;
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("failed to read immutable artifact: " + reference.artifactId(), e);
        }
    }

    private void writeOnce(Path target, byte[] content) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.createDirectories(target.getParent());
        Files.write(temporary, content);
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException ignored) {
                // Another process persisted the same content-addressed Artifact.
            } catch (AtomicMoveNotSupportedException ignored) {
                try {
                    Files.move(temporary, target);
                } catch (FileAlreadyExistsException concurrentWriter) {
                    // The immutable content has already won the race.
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String workspacePath(Path path) {
        Path workspace = root.getParent().getParent().getParent();
        return workspace.relativize(path).toString().replace('\\', '/');
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }

    public record ArtifactReference(
            int schemaVersion,
            String artifactId,
            String artifactType,
            String mediaType,
            String sha256,
            long sizeBytes,
            String path,
            Instant createdAt
    ) {
        public static final int CURRENT_SCHEMA_VERSION = 1;

        public ArtifactReference {
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported artifact reference schema: " + schemaVersion);
            }
            artifactId = required(artifactId, "artifactId");
            artifactType = required(artifactType, "artifactType");
            mediaType = required(mediaType, "mediaType");
            sha256 = required(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 digest");
            }
            if (!artifactId.equals("sha256:" + sha256)) {
                throw new IllegalArgumentException("artifactId must match sha256");
            }
            if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes cannot be negative");
            path = required(path, "path");
            createdAt = createdAt != null ? createdAt : Instant.now();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("schema_version", schemaVersion);
            out.put("artifact_id", artifactId);
            out.put("artifact_type", artifactType);
            out.put("media_type", mediaType);
            out.put("sha256", sha256);
            out.put("size_bytes", sizeBytes);
            out.put("path", path);
            out.put("created_at", createdAt.toString());
            return out;
        }
    }
}
