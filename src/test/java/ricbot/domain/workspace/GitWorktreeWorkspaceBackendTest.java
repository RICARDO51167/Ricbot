package ricbot.domain.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.workspace.dto.WorkspaceSession;
import ricbot.domain.workspace.enump.WorkspaceBackendType;
import ricbot.domain.workspace.enump.WorkspaceSessionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitWorktreeWorkspaceBackendTest {

    @Test
    void nonGitRepositoryReturnsClearError(@TempDir Path workspace) {
        GitWorktreeWorkspaceBackend backend = new GitWorktreeWorkspaceBackend(workspace);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> backend.createSession(workspace, "worktree goal")
        );

        assertTrue(error.getMessage().contains("not a git repository"), error.getMessage());
    }

    @Test
    void createSessionCreatesGitWorktree(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        GitWorktreeWorkspaceBackend backend = new GitWorktreeWorkspaceBackend(workspace);

        WorkspaceSession session = backend.createSession(workspace, "isolated edit");

        assertEquals(WorkspaceBackendType.GIT_WORKTREE, session.type());
        assertTrue(session.branchName().startsWith("ricbot/"), session.branchName());
        assertTrue(Files.exists(Path.of(session.workspacePath()).resolve("README.md")));
        assertTrue(Files.exists(workspace.resolve(".workspaces").resolve(session.id()).resolve("session.json")));
        assertEquals(WorkspaceSessionStatus.ACTIVE, backend.status(session.id()));
    }

    @Test
    void diffCapturesWorktreeModification(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        GitWorktreeWorkspaceBackend backend = new GitWorktreeWorkspaceBackend(workspace);
        WorkspaceSession session = backend.createSession(workspace, "diff edit");
        Path worktree = Path.of(session.workspacePath());

        Files.writeString(worktree.resolve("README.md"), "initial\nchanged in worktree\n");

        String diff = backend.diff(session.id());
        assertTrue(diff.contains("changed in worktree"), diff);
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
    }

    @Test
    void cleanupRequiresSafeWorkspacesPath(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
        WorkspaceSession unsafe = store.save(new WorkspaceSession(
                "workspace_unsafe",
                WorkspaceBackendType.GIT_WORKTREE,
                workspace.toString(),
                workspace.resolve("outside").toString(),
                "ricbot/workspace_unsafe",
                "unsafe",
                WorkspaceSessionStatus.ACTIVE,
                null,
                null,
                Map.of()
        ));
        GitWorktreeWorkspaceBackend backend = new GitWorktreeWorkspaceBackend(workspace, store);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> backend.cleanup(unsafe.id()));

        assertTrue(error.getMessage().contains(".workspaces"), error.getMessage());
    }

    @Test
    void cleanupRemovesCleanWorktreeButNotBaseWorkspace(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        GitWorktreeWorkspaceBackend backend = new GitWorktreeWorkspaceBackend(workspace);
        WorkspaceSession session = backend.createSession(workspace, "cleanup");
        Path worktree = Path.of(session.workspacePath());

        WorkspaceSession cleaned = backend.cleanup(session.id());

        assertEquals(WorkspaceSessionStatus.CLEANED, cleaned.status());
        assertFalse(Files.exists(worktree.resolve("README.md")));
        assertTrue(Files.exists(workspace.resolve("README.md")));
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
