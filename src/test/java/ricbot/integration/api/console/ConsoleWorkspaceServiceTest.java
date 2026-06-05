package ricbot.integration.api.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleWorkspaceServiceTest {
    @Test
    void tree_listsWorkspaceNodesAndSkipsIgnoredDirectories(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(workspace.resolve("src/main/java/App.java"), "class App {}\n");
        Files.createDirectories(workspace.resolve("node_modules/pkg"));
        Files.writeString(workspace.resolve("node_modules/pkg/index.js"), "module.exports = {}\n");

        ConsoleWorkspaceService service = new ConsoleWorkspaceService(workspace);

        WorkspaceTreeResponse response = service.tree("", 3, false);

        assertEquals(workspace.toAbsolutePath().normalize().toString(), response.workspace());
        assertTrue(response.nodes().stream().anyMatch(node -> node.path().equals("src") && node.type() == WorkspaceNodeType.DIRECTORY));
        assertTrue(response.nodes().stream()
                .filter(node -> node.path().equals("src"))
                .flatMap(node -> node.children().stream())
                .anyMatch(node -> node.path().equals("src/main")));
        assertFalse(response.nodes().stream().anyMatch(node -> node.path().equals("node_modules")));
    }

    @Test
    void fileContent_readsTextWithLanguageAndMetadata(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(workspace.resolve("src/main/java/App.java"), "class App {}\n");

        WorkspaceFileContent content = new ConsoleWorkspaceService(workspace)
                .fileContent("src/main/java/App.java");

        assertEquals("src/main/java/App.java", content.path());
        assertEquals("java", content.language());
        assertFalse(content.binary());
        assertFalse(content.truncated());
        assertEquals("class App {}\n", content.content());
        assertTrue(content.size() > 0);
        assertFalse(content.modifiedAt().isBlank());
    }

    @Test
    void fileContent_rejectsTraversalAndIgnoredPaths(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve(".git/config"), "secret\n");

        ConsoleWorkspaceService service = new ConsoleWorkspaceService(workspace);

        ConsoleWorkspaceException traversal = assertThrows(ConsoleWorkspaceException.class,
                () -> service.fileContent("../outside.txt"));
        assertEquals("path_outside_workspace", traversal.code());

        ConsoleWorkspaceException ignored = assertThrows(ConsoleWorkspaceException.class,
                () -> service.fileContent(".git/config"));
        assertEquals("path_not_previewable", ignored.code());
    }

    @Test
    void fileContent_marksBinaryAndLargeFilesWithoutReturningContent(@TempDir Path workspace) throws Exception {
        Files.write(workspace.resolve("image.bin"), new byte[]{0, 1, 2, 3});
        Files.writeString(workspace.resolve("large.txt"), "x".repeat(ConsoleWorkspaceService.DEFAULT_MAX_FILE_BYTES + 1));

        ConsoleWorkspaceService service = new ConsoleWorkspaceService(workspace);

        WorkspaceFileContent binary = service.fileContent("image.bin");
        assertTrue(binary.binary());
        assertEquals("", binary.content());

        WorkspaceFileContent large = service.fileContent("large.txt");
        assertTrue(large.truncated());
        assertEquals("", large.content());
    }

    @Test
    void operationsReportUnconfiguredWorkspace() {
        ConsoleWorkspaceService service = new ConsoleWorkspaceService(null);

        ConsoleWorkspaceException error = assertThrows(ConsoleWorkspaceException.class,
                () -> service.tree("", 2, false));

        assertEquals("workspace_not_configured", error.code());
    }
}
