package ricbot.core.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.infra.git.GitStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GitStoreTest {

    @Test
    void diffCommits_returnsTrackedFileDiff(@TempDir Path workspace) throws Exception {
        GitStore git = new GitStore(workspace, List.of("memory/MEMORY.md"));
        assertTrue(git.init());

        Path memory = workspace.resolve("memory").resolve("MEMORY.md");
        Files.createDirectories(memory.getParent());
        Files.writeString(memory, "alpha\n");
        String first = git.autoCommit("first");

        Files.writeString(memory, "alpha\nbeta\n");
        String second = git.autoCommit("second");

        String diff = git.diffCommits(first, second);
        assertTrue(diff.contains("MEMORY.md"), diff);
        assertTrue(diff.contains("+beta"), diff);
    }
}
