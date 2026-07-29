package ricbot.domain.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.workspace.dto.WorkspaceSession;
import ricbot.domain.workspace.enump.WorkspaceBackendType;
import ricbot.domain.workspace.enump.WorkspaceSessionStatus;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkspaceBackendTest {

    @Test
    void createStatusDiffAndCleanup(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        LocalWorkspaceBackend backend = new LocalWorkspaceBackend(workspace);

        WorkspaceSession session = backend.createSession(workspace, "local edit");
        Files.writeString(workspace.resolve("README.md"), "initial\nchanged\n");

        assertEquals(WorkspaceBackendType.LOCAL, session.type());
        assertEquals(workspace.toAbsolutePath().normalize(), backend.getWorkspacePath(session.id()));
        assertEquals(WorkspaceSessionStatus.ACTIVE, backend.status(session.id()));
        assertTrue(backend.diff(session.id()).contains("changed"), backend.diff(session.id()));
        assertEquals(WorkspaceSessionStatus.CLEANED, backend.cleanup(session.id()).status());
    }

    private static void initGitRepo(Path workspace) throws Exception {
        git(workspace, "init");
        git(workspace, "config", "user.name", "Test");
        git(workspace, "config", "user.email", "test@example.com");
        Files.writeString(workspace.resolve("README.md"), "initial\n");
        git(workspace, "add", "README.md");
        git(workspace, "commit", "-m", "init");
    }

    private static String git(Path workspace, String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).directory(workspace.toFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        int code = process.waitFor();
        if (code != 0) {
            throw new AssertionError("git failed: " + String.join(" ", command) + "\n" + stderr + stdout);
        }
        return stdout;
    }
}
