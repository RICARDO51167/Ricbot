package ricbot.integration.api.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertFalse(src.loaded());
        assertTrue(src.hasChildren());
    }

    @Test
    void workspaceTree_loadsChildDirectoryByRoot(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(workspace.resolve("src/main/java/App.java"), "class App {}\n");

        WorkspaceTreeResponse response = new ConsoleWorkspaceService(workspace).tree("src/main", 1, false);

        assertEquals("src/main", response.root());
        assertEquals(List.of("src/main/java"), response.nodes().stream().map(WorkspaceTreeNode::path).toList());
        assertEquals(WorkspaceNodeType.DIRECTORY, response.nodes().get(0).type());
        assertFalse(response.nodes().get(0).loaded());
        assertTrue(response.nodes().get(0).hasChildren());
    }

    @Test
    void workspaceTree_rootFileReturnsErrorOrEmpty(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "readme\n");
        ConsoleWorkspaceService service = new ConsoleWorkspaceService(workspace);

        ConsoleWorkspaceException error = assertThrows(ConsoleWorkspaceException.class,
                () -> service.tree("README.md", 1, false));

        assertEquals("path_not_directory", error.code());
        assertEquals(400, error.status());
    }

    @Test
    void workspaceTree_rejectsPathTraversal(@TempDir Path workspace) {
        ConsoleWorkspaceService service = new ConsoleWorkspaceService(workspace);

        ConsoleWorkspaceException error = assertThrows(ConsoleWorkspaceException.class,
                () -> service.tree("../outside", 2, false));

        assertEquals("path_outside_workspace", error.code());
    }

    @Test
    void workspaceTree_rejectsTraversalRoot(@TempDir Path workspace) {
        ConsoleWorkspaceService service = new ConsoleWorkspaceService(workspace);

        ConsoleWorkspaceException error = assertThrows(ConsoleWorkspaceException.class,
                () -> service.tree("src/../../outside", 1, false));

        assertEquals("path_outside_workspace", error.code());
    }

    @Test
    void workspaceTree_skipsBlockedDirectoryOnLazyLoad(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src/target/generated"));
        Files.writeString(workspace.resolve("src/target/generated/App.java"), "class App {}\n");
        Files.createDirectories(workspace.resolve("src/main"));

        WorkspaceTreeResponse response = new ConsoleWorkspaceService(workspace).tree("src", 1, false);

        assertEquals(List.of("src/main"), response.nodes().stream().map(WorkspaceTreeNode::path).toList());
        ConsoleWorkspaceException blocked = assertThrows(ConsoleWorkspaceException.class,
                () -> new ConsoleWorkspaceService(workspace).tree("src/target", 1, false));
        assertEquals("path_blocked", blocked.code());
        assertEquals(403, blocked.status());
    }

    @Test
    void workspaceTree_marksHasChildrenForDirectories(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("empty"));
        Files.createDirectories(workspace.resolve("src/main"));
        Files.writeString(workspace.resolve("src/App.java"), "class App {}\n");

        WorkspaceTreeResponse response = new ConsoleWorkspaceService(workspace).tree("", 1, false);

        WorkspaceTreeNode empty = response.nodes().stream().filter(node -> node.path().equals("empty")).findFirst().orElseThrow();
        WorkspaceTreeNode src = response.nodes().stream().filter(node -> node.path().equals("src")).findFirst().orElseThrow();
        assertFalse(empty.hasChildren());
        assertTrue(empty.loaded());
        assertTrue(src.hasChildren());
        assertFalse(src.loaded());
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
    void workspaceSearch_returnsMatchesByFileName(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/ricbot/domain/agent"));
        Files.writeString(workspace.resolve("src/main/java/ricbot/domain/agent/AgentRunner.java"), "class AgentRunner {}\n");
        Files.writeString(workspace.resolve("README.md"), "AgentRunner mentioned only in content\n");

        WorkspaceSearchResponse response = new ConsoleWorkspaceService(workspace).search("AgentRunner", 50, false);

        assertEquals("AgentRunner", response.keyword());
        assertEquals(1, response.results().size());
        assertEquals("src/main/java/ricbot/domain/agent/AgentRunner.java", response.results().get(0).path());
        assertEquals(100, response.results().get(0).score());
    }

    @Test
    void workspaceSearch_returnsMatchesByPath(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/ricbot/domain/agent"));
        Files.writeString(workspace.resolve("src/main/java/ricbot/domain/agent/Runner.java"), "class Runner {}\n");
        Files.writeString(workspace.resolve("Runner.md"), "runner\n");

        WorkspaceSearchResponse response = new ConsoleWorkspaceService(workspace).search("domain/agent", 50, false);

        assertEquals(List.of("src/main/java/ricbot/domain/agent/Runner.java"),
                response.results().stream().map(WorkspaceSearchResult::path).toList());
    }

    @Test
    void workspaceSearch_returnsEmptyForBlankKeyword(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "readme\n");

        WorkspaceSearchResponse response = new ConsoleWorkspaceService(workspace).search(" ", 50, false);

        assertTrue(response.results().isEmpty());
    }

    @Test
    void workspaceSearch_respectsLimit(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("OneAgent.java"), "class OneAgent {}\n");
        Files.writeString(workspace.resolve("TwoAgent.java"), "class TwoAgent {}\n");
        Files.writeString(workspace.resolve("ThreeAgent.java"), "class ThreeAgent {}\n");

        WorkspaceSearchResponse response = new ConsoleWorkspaceService(workspace).search("Agent", 2, false);

        assertEquals(2, response.results().size());
    }

    @Test
    void workspaceSearch_skipsBlockedDirectories(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("node_modules/pkg"));
        Files.writeString(workspace.resolve("node_modules/pkg/AgentRunner.java"), "class AgentRunner {}\n");
        Files.writeString(workspace.resolve("AgentRoot.java"), "class AgentRoot {}\n");

        WorkspaceSearchResponse response = new ConsoleWorkspaceService(workspace).search("Agent", 50, false);

        assertEquals(List.of("AgentRoot.java"), response.results().stream().map(WorkspaceSearchResult::path).toList());
    }

    @Test
    void workspaceSearch_excludesHiddenByDefault(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve(".config"));
        Files.writeString(workspace.resolve(".config/AgentHidden.java"), "class AgentHidden {}\n");
        Files.writeString(workspace.resolve("AgentVisible.java"), "class AgentVisible {}\n");

        WorkspaceSearchResponse hiddenOff = new ConsoleWorkspaceService(workspace).search("Agent", 50, false);
        WorkspaceSearchResponse hiddenOn = new ConsoleWorkspaceService(workspace).search("Agent", 50, true);

        assertEquals(List.of("AgentVisible.java"), hiddenOff.results().stream().map(WorkspaceSearchResult::path).toList());
        assertTrue(hiddenOn.results().stream().anyMatch(result -> result.path().equals(".config/AgentHidden.java")));
    }

    @Test
    void workspaceSearch_doesNotReadFileContent(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "UniqueNeedleOnlyInContent\n");

        WorkspaceSearchResponse response = new ConsoleWorkspaceService(workspace).search("UniqueNeedleOnlyInContent", 50, false);

        assertTrue(response.results().isEmpty());
    }

    @Test
    void workspaceSearch_doesNotModifyFiles(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("AgentRunner.java");
        Files.writeString(file, "before\n");
        String before = Files.readString(file);

        new ConsoleWorkspaceService(workspace).search("AgentRunner", 50, false);

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
