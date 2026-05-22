package ricbot.domain.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceLifecycleServiceTest {

    @Test
    void listStatusDiffAndDiscardManagedWorktree(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
        GitWorktreeWorkspaceBackend backend = new GitWorktreeWorkspaceBackend(workspace, store);
        WorkspaceSession created = backend.createSession(workspace, "task lifecycle", "team-task-lifecycle");
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(created.metadata());
        metadata.put("taskId", "teamtask_lifecycle");
        metadata.put("teamSessionId", "team_1");
        created = store.save(created.withMetadata(metadata));
        String createdId = created.id();
        Path worktree = Path.of(created.workspacePath());
        Files.writeString(worktree.resolve("README.md"), "initial\nchanged\n");

        WorkspaceLifecycleService service = new WorkspaceLifecycleService(workspace);

        assertTrue(service.activeWorktrees().stream().anyMatch(session -> session.id().equals(createdId)));
        WorkspaceLifecycleService.WorkspaceStatus status = service.status("teamtask_lifecycle");
        assertTrue(status.dirty());
        assertTrue(status.statusShort().contains("README.md"), status.statusShort());
        WorkspaceLifecycleService.WorkspaceDiff diff = service.diff(createdId);
        assertTrue(diff.changedFiles().contains("README.md"), diff.changedFiles().toString());
        assertTrue(diff.patch().contains("changed"), diff.patch());

        IllegalArgumentException noForce = assertThrows(IllegalArgumentException.class,
                () -> service.discard("teamtask_lifecycle", false));
        assertTrue(noForce.getMessage().contains("--force"), noForce.getMessage());
        WorkspaceSession discarded = service.discard("teamtask_lifecycle", true);
        assertEquals(WorkspaceSessionStatus.DISCARDED, discarded.status());
        assertFalse(Files.exists(worktree.resolve("README.md")));
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
    }

    @Test
    void rejectsUnmanagedAndEscapingWorktrees(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Path unmanagedPath = workspace.resolve(".workspaces").resolve("unmanaged");
        git(workspace, "worktree", "add", "-b", "ricbot/unmanaged", unmanagedPath.toString());
        WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
        store.save(new WorkspaceSession(
                "workspace_unmanaged",
                WorkspaceBackendType.GIT_WORKTREE,
                workspace.toString(),
                unmanagedPath.toString(),
                "ricbot/unmanaged",
                "unmanaged",
                WorkspaceSessionStatus.ACTIVE,
                null,
                null,
                Map.of("taskId", "teamtask_unmanaged")
        ));
        WorkspaceLifecycleService service = new WorkspaceLifecycleService(workspace);

        IllegalStateException unmanaged = assertThrows(IllegalStateException.class,
                () -> service.discard("teamtask_unmanaged", true));
        assertTrue(unmanaged.getMessage().contains("not managed"), unmanaged.getMessage());

        store.save(new WorkspaceSession(
                "workspace_escape",
                WorkspaceBackendType.GIT_WORKTREE,
                workspace.toString(),
                workspace.resolve("outside").toString(),
                "ricbot/escape",
                "escape",
                WorkspaceSessionStatus.ACTIVE,
                null,
                null,
                Map.of("taskId", "teamtask_escape", "managedBy", "ricbot")
        ));
        IllegalStateException escaping = assertThrows(IllegalStateException.class,
                () -> service.status("teamtask_escape"));
        assertTrue(escaping.getMessage().contains(".workspaces"), escaping.getMessage());
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
