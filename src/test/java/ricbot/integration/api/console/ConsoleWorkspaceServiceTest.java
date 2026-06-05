package ricbot.integration.api.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleWorkspaceServiceTest {
    @Test
    void workspaceTree_returnsRootNodes(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(workspace.resolve("src/main/java/App.java"), "class App {}\n");
        Files.writeString(workspace.resolve("pom.xml"), "<project />\n");
        Files.createDirectories(workspace.resolve("node_modules/pkg"));
        Files.writeString(workspace.resolve("node_modules/pkg/index.js"), "module.exports = {}\n");

        WorkspaceTreeResponse response = new ConsoleWorkspaceService(workspace).tree("", 3, false);

        assertEquals(workspace.toAbsolutePath().normalize().toString(), response.workspace());
        assertEquals("", response.root());
        assertTrue(response.nodes().stream().anyMatch(node -> node.path().equals("src") && node.type() == WorkspaceNodeType.DIRECTORY));
        assertTrue(response.nodes().stream().anyMatch(node -> node.path().equals("pom.xml") && node.type() == WorkspaceNodeType.FILE));
        assertFalse(response.nodes().stream().anyMatch(node -> node.path().equals("node_modules")));
    }

    @Test
    void workspaceTree_respectsDepth(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/app"));
        Files.writeString(workspace.resolve("src/main/java/app/App.java"), "class App {}\n");

        WorkspaceTreeResponse depthOne = new ConsoleWorkspaceService(workspace).tree("", 1, false);
        WorkspaceTreeNode src = depthOne.nodes().stream()
                .filter(node -> node.path().equals("src"))
                .findFirst()
                .orElseThrow();

        assertEquals(WorkspaceNodeType.DIRECTORY, src.type());
        assertEquals(0, src.children().size());
    }

    @Test
    void workspaceTree_rejectsPathTraversal(@TempDir Path workspace) {
        ConsoleWorkspaceService service = new ConsoleWorkspaceService(workspace);

        ConsoleWorkspaceException error = assertThrows(ConsoleWorkspaceException.class,
                () -> service.tree("../outside", 2, false));

        assertEquals("path_outside_workspace", error.code());
    }

    @Test
    void workspaceFileContent_readsTextFile(@TempDir Path workspace) throws Exception {
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
    void workspaceFileContent_rejectsPathTraversal(@TempDir Path workspace) {
        ConsoleWorkspaceService service = new ConsoleWorkspaceService(workspace);

        ConsoleWorkspaceException error = assertThrows(ConsoleWorkspaceException.class,
                () -> service.fileContent("../outside.txt"));

        assertEquals("path_outside_workspace", error.code());
    }

    @Test
    void workspaceFileContent_rejectsLargeFile(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("large.txt"), "x".repeat(ConsoleWorkspaceService.DEFAULT_MAX_FILE_BYTES + 1));

        WorkspaceFileContent large = new ConsoleWorkspaceService(workspace).fileContent("large.txt");

        assertFalse(large.binary());
        assertTrue(large.truncated());
        assertEquals("", large.content());
    }

    @Test
    void workspaceFileContent_marksBinaryFile(@TempDir Path workspace) throws Exception {
        Files.write(workspace.resolve("image.bin"), new byte[]{0, 1, 2, 3});

        WorkspaceFileContent binary = new ConsoleWorkspaceService(workspace).fileContent("image.bin");

        assertTrue(binary.binary());
        assertFalse(binary.truncated());
        assertEquals("", binary.content());
    }

    @Test
    void workspaceFileContent_returnsNotFoundForMissingFile(@TempDir Path workspace) {
        ConsoleWorkspaceService service = new ConsoleWorkspaceService(workspace);

        ConsoleWorkspaceException error = assertThrows(ConsoleWorkspaceException.class,
                () -> service.fileContent("missing.txt"));

        assertEquals("file_not_found", error.code());
        assertEquals(404, error.status());
    }

    @Test
    void workspaceEndpoints_doNotModifyFiles(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("README.md");
        Files.writeString(file, "before\n");
        String before = Files.readString(file);

        ConsoleWorkspaceService service = new ConsoleWorkspaceService(workspace);
        service.tree("", 2, false);
        service.fileContent("README.md");

        assertEquals(before, Files.readString(file));
    }

    @Test
    void operationsReportUnconfiguredWorkspace() {
        ConsoleWorkspaceService service = new ConsoleWorkspaceService(null);

        ConsoleWorkspaceException error = assertThrows(ConsoleWorkspaceException.class,
                () -> service.tree("", 2, false));

        assertEquals("workspace_not_configured", error.code());
    }
}
