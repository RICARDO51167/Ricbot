package ricbot.domain.change;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.team.VerificationResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeSetServiceTest {

    @Test
    void nonGitRepositoryReturnsClearError(@TempDir Path workspace) {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new ChangeSetService(workspace).createFromWorkingTree("cli:direct", "", "")
        );

        assertTrue(error.getMessage().contains("not a git repository"), error.getMessage());
    }

    @Test
    void createFromWorkingTreeCapturesChangedFilesPatchRollbackAndCommitMessage(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "initial\nchanged\n");
        Files.createDirectories(workspace.resolve("src/main/java/demo"));
        Files.writeString(workspace.resolve("src/main/java/demo/NewFile.java"), "class NewFile {}\n");
        ChangeSetService service = new ChangeSetService(workspace);

        GitChangeSet changeSet = service.createFromWorkingTree("cli:direct", "team_1", "task_1");

        assertEquals("team_1", changeSet.teamSessionId());
        assertEquals("task_1", changeSet.taskId());
        assertTrue(changeSet.changedFiles().contains("README.md"), changeSet.changedFiles().toString());
        assertTrue(changeSet.changedFiles().contains("src/main/java/demo/NewFile.java"), changeSet.changedFiles().toString());
        assertTrue(changeSet.diffSummary().contains("Changed files: 2"), changeSet.diffSummary());
        assertTrue(changeSet.diffPatch().contains("README.md"), changeSet.diffPatch());
        assertTrue(changeSet.diffPatch().contains("NewFile.java"), changeSet.diffPatch());
        assertTrue(Files.exists(service.changeSetDir(changeSet.id()).resolve("changeset.json")));
        assertTrue(Files.exists(service.changeSetDir(changeSet.id()).resolve("diff.patch")));
        assertTrue(changeSet.rollbackCommands().contains("git restore -- README.md"), changeSet.rollbackCommands().toString());
        assertTrue(changeSet.rollbackCommands().contains("rm src/main/java/demo/NewFile.java"), changeSet.rollbackCommands().toString());
        assertTrue(changeSet.commitMessage().contains("Update"), changeSet.commitMessage());
        assertEquals(changeSet.id(), service.latest().id());
    }

    @Test
    void createFromWorkspaceCapturesWorktreeDiffAndWorkspaceMetadata(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Path worktree = workspace.resolve(".workspaces").resolve("workspace_demo");
        git(workspace, "worktree", "add", "-b", "ricbot/workspace_demo", worktree.toString());
        Files.writeString(worktree.resolve("README.md"), "initial\nworktree change\n");
        ChangeSetService service = new ChangeSetService(workspace);

        GitChangeSet changeSet = service.createFromWorkspace("workspace_demo", worktree, "cli:direct", "", "");

        assertEquals("workspace_demo", changeSet.workspaceSessionId());
        assertEquals(worktree.toAbsolutePath().normalize().toString(), changeSet.workspacePath());
        assertTrue(changeSet.changedFiles().contains("README.md"), changeSet.changedFiles().toString());
        assertTrue(changeSet.diffPatch().contains("worktree change"), changeSet.diffPatch());
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
    }

    @Test
    void attachVerifierAndApprovePersistStatus(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "initial\nchanged\n");
        ChangeSetService service = new ChangeSetService(workspace);
        GitChangeSet changeSet = service.createFromWorkingTree("cli:direct", "team_1", "task_1");

        GitChangeSet verified = service.attachVerifierResult(changeSet.id(), VerificationResult.pass("targeted tests passed"));
        GitChangeSet approved = service.markApproved(changeSet.id());

        assertEquals("PASS", verified.verifierStatus());
        assertTrue(verified.verifierReasons().contains("targeted tests passed"), verified.verifierReasons().toString());
        assertEquals(GitChangeSetStatus.APPROVED, approved.status());
        assertEquals(GitChangeSetStatus.APPROVED, service.load(changeSet.id()).status());
    }

    @Test
    void commitPersistsCommitHashAndCommittedStatus(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        String before = git(workspace, "rev-parse", "HEAD").trim();
        Files.writeString(workspace.resolve("README.md"), "initial\nchanged\n");
        ChangeSetService service = new ChangeSetService(workspace);
        GitChangeSet changeSet = service.createFromWorkingTree("cli:direct", "team_1", "task_1");

        GitChangeSet committed = service.commit(changeSet.id(), "Update README through changeset");

        assertEquals(GitChangeSetStatus.COMMITTED, committed.status());
        assertFalse(committed.commitHash().isBlank());
        assertFalse(before.equals(committed.commitHash()));
        assertEquals(committed.commitHash(), service.load(changeSet.id()).commitHash());
        assertTrue(git(workspace, "show", "--name-only", "--format=", committed.commitHash()).contains("README.md"));
    }

    @Test
    void rollbackExecutesCommandsAndMarksRolledBack(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "initial\nchanged\n");
        Files.writeString(workspace.resolve("new.txt"), "new file\n");
        ChangeSetService service = new ChangeSetService(workspace);
        GitChangeSet changeSet = service.createFromWorkingTree("cli:direct", "team_1", "task_1");

        GitChangeSet rolledBack = service.rollback(changeSet.id());

        assertEquals(GitChangeSetStatus.ROLLED_BACK, rolledBack.status());
        assertTrue(rolledBack.rollbackStatus().contains("executed"), rolledBack.rollbackStatus());
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
        assertTrue(Files.notExists(workspace.resolve("new.txt")));
        assertEquals(GitChangeSetStatus.ROLLED_BACK, service.load(changeSet.id()).status());
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
        command.addAll(List.of(args));
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
