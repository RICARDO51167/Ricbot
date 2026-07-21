package ricbot.infra.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmutableArtifactStoreTest {
    @Test
    void storesJsonOnceByContentDigest(@TempDir Path workspace) throws Exception {
        ImmutableArtifactStore store = new ImmutableArtifactStore(workspace);

        ImmutableArtifactStore.ArtifactReference first = store.putJson("VerificationEvidence", Map.of("status", "PASS"));
        Path artifact = workspace.resolve(first.path());
        var modifiedAt = Files.getLastModifiedTime(artifact);
        ImmutableArtifactStore.ArtifactReference repeated = store.putJson("VerificationEvidence", Map.of("status", "PASS"));

        assertEquals(first.artifactId(), repeated.artifactId());
        assertEquals(first.path(), repeated.path());
        assertEquals(first.createdAt(), repeated.createdAt());
        assertEquals(modifiedAt, Files.getLastModifiedTime(artifact));
        assertArrayEquals(Files.readAllBytes(artifact), store.read(first));
        assertTrue(first.path().startsWith(".ricbot/artifacts/sha256/"));
        assertNotEquals(first.artifactId(), store.putJson("VerificationEvidence", Map.of("status", "FAIL")).artifactId());
    }

    @Test
    void rejectsReferencesThatDoNotMatchTheirDigest() {
        assertThrows(IllegalArgumentException.class, () -> new ImmutableArtifactStore.ArtifactReference(
                1, "sha256:not-the-digest", "evidence", "application/json", "a".repeat(64),
                1, "artifact.json", null));
    }
}
