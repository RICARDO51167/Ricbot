package ricbot.domain.agent.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactStoreTest {
    @Test void writesReadsGrepsAndRejectsForeignRuns(@TempDir Path workspace) throws Exception {
        ArtifactStore store = new ArtifactStore(workspace, "root", "run-a", "task-a");
        ArtifactRef ref = store.writeText("alpha\nimportant rule\nomega", "test", 5);
        assertEquals("alpha", ref.summary());
        assertEquals("alpha\nimportant", store.read(ref.uri(), 0, 15));
        assertEquals("2:important rule", store.grep(ref.uri(), "important", 10).get(0));
        assertThrows(SecurityException.class, () -> store.read("artifact://run-b/" + ref.artifactId(), 0, 10));
        assertThrows(SecurityException.class, () -> store.read("artifact://run-a/../../etc", 0, 10));

        Files.writeString(workspace.resolve(".ricbot/artifacts/root").resolve(ref.path()), "tampered");
        assertThrows(IllegalStateException.class, () -> store.read(ref.uri(), 0, 10));
    }
}
