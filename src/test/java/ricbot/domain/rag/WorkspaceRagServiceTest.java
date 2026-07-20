package ricbot.domain.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceRagServiceTest {

    @Test
    void persistsVectorsByModelAndChunkFingerprint(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "durable vector content");
        AtomicInteger calls = new AtomicInteger();
        ricbot.domain.retrieval.EmbeddingProvider provider = new ricbot.domain.retrieval.EmbeddingProvider() {
            public String modelId() { return "test-real-model"; }
            public double[] embed(String text) { calls.incrementAndGet(); return new double[]{text.length(), 1d}; }
        };

        new WorkspaceRagService(workspace, "tenant", provider).indexWorkspace();
        int firstIndexCalls = calls.get();
        new WorkspaceRagService(workspace, "tenant", provider).indexWorkspace();

        assertTrue(firstIndexCalls > 0);
        assertEquals(firstIndexCalls, calls.get());
        try (var files = Files.list(workspace.resolve(".rag/tenants").resolve(
                sha256("tenant")).resolve("code_index/vector_index"))) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void exposesHybridScoresAndIsolatesTenantIndexes(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Recovery.java"),
                "class Recovery { void durableCheckpointReplay() {} }");
        WorkspaceRagService tenantA = new WorkspaceRagService(workspace, "tenant-a",
                new ricbot.domain.retrieval.HashingEmbeddingProvider());
        WorkspaceRagService tenantB = new WorkspaceRagService(workspace, "tenant-b",
                new ricbot.domain.retrieval.HashingEmbeddingProvider());

        tenantA.indexWorkspace();
        WorkspaceRagService.SearchResult result = tenantA.searchCode("durable checkpoint replay", 1).get(0);

        assertTrue(result.lexicalScore() > 0d);
        assertTrue(result.vectorScore() > 0d);
        assertEquals("tenant-a", tenantA.tenantId());
        assertNotEquals(tenantA.indexWorkspace().chunksFile(), tenantB.indexWorkspace().chunksFile());
    }
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void refreshChangedFiles_handlesAddedModifiedAndDeletedFiles(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("README.md"), "alpha readme\n");
        Files.writeString(workspace.resolve("docs/a.md"), "alpha document\n");

        WorkspaceRagService service = new WorkspaceRagService(workspace);
        WorkspaceRagService.IndexReport initial = service.indexWorkspace();
        assertEquals(2, initial.files());

        Files.writeString(workspace.resolve("docs/b.md"), "beta added document\n");
        WorkspaceRagService.IndexReport added = service.refreshChangedFiles();
        assertEquals(1, added.added());
        assertEquals(0, added.modified());
        assertEquals(0, added.deleted());
        assertTrue(service.searchDocs("beta", 5).stream().anyMatch(result -> result.chunk().path().equals("docs/b.md")));

        Files.writeString(workspace.resolve("docs/a.md"), "gamma modified document\n");
        WorkspaceRagService.IndexReport modified = service.refreshChangedFiles();
        assertEquals(0, modified.added());
        assertEquals(1, modified.modified());
        assertEquals(0, modified.deleted());
        assertTrue(service.searchDocs("gamma", 5).stream().anyMatch(result -> result.chunk().path().equals("docs/a.md")));

        Files.delete(workspace.resolve("docs/b.md"));
        WorkspaceRagService.IndexReport deleted = service.refreshChangedFiles();
        assertEquals(0, deleted.added());
        assertEquals(0, deleted.modified());
        assertEquals(1, deleted.deleted());
        assertFalse(service.searchDocs("beta", 5).stream().anyMatch(result -> result.chunk().path().equals("docs/b.md")));

        Map<String, Object> state = MAPPER.readValue(
                Files.readString(workspace.resolve(".rag").resolve("code_index").resolve("last_scan_state.json")),
                new TypeReference<>() {
                }
        );
        assertTrue(state.containsKey("files"));
        Map<?, ?> files = (Map<?, ?>) state.get("files");
        assertTrue(files.containsKey("README.md"));
        assertTrue(files.containsKey("docs/a.md"));
        assertFalse(files.containsKey("docs/b.md"));
        Map<?, ?> aState = (Map<?, ?>) files.get("docs/a.md");
        assertTrue(aState.containsKey("sha256"));
        assertTrue(aState.containsKey("chunkCount"));
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
