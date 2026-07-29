package ricbot.domain.change;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.verification.VerificationReport;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.ApprovalBinding;
import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.security.RiskAssessment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Instant;

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
        assertEquals(workspace.resolve(".ricbot/runtime.db").toAbsolutePath().normalize(), service.changeSetDir(changeSet.id()));
        assertFalse(Files.exists(workspace.resolve(".changesets")));
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
    void createFromWorkspaceIgnoresRuntimeArtifacts(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Path worktree = workspace.resolve(".workspaces").resolve("workspace_runtime");
        git(workspace, "worktree", "add", "-b", "ricbot/workspace_runtime", worktree.toString());
        Files.createDirectories(worktree.resolve("notes"));
        Files.writeString(worktree.resolve("notes/index.json"), "{}\n");
        Files.writeString(worktree.resolve("session.json"), "{}\n");
        ChangeSetService service = new ChangeSetService(workspace);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createFromWorkspace("workspace_runtime", worktree, "cli:direct", "", ""));

        assertEquals("no user changes found", error.getMessage());
        assertFalse(Files.exists(workspace.resolve(".changesets")));
    }

    @Test
    void createFromWorkspaceIncludesUserChangesWhenRuntimeArtifactsExist(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Path worktree = workspace.resolve(".workspaces").resolve("workspace_mixed");
        git(workspace, "worktree", "add", "-b", "ricbot/workspace_mixed", worktree.toString());
        Files.createDirectories(worktree.resolve("notes"));
        Files.writeString(worktree.resolve("notes/index.json"), "{}\n");
        Files.writeString(worktree.resolve("README.md"), "initial\nuser change\n");
        ChangeSetService service = new ChangeSetService(workspace);

        GitChangeSet changeSet = service.createFromWorkspace("workspace_mixed", worktree, "cli:direct", "", "");

        assertEquals(List.of("README.md"), changeSet.changedFiles());
        assertTrue(changeSet.diffPatch().contains("user change"), changeSet.diffPatch());
        assertFalse(changeSet.diffPatch().contains("notes/index.json"), changeSet.diffPatch());
    }

    @Test
    void attachVerifierAndApprovePersistStatus(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "initial\nchanged\n");
        ChangeSetService service = new ChangeSetService(workspace);
        GitChangeSet changeSet = service.createFromWorkingTree("cli:direct", "team_1", "task_1");

        GitChangeSet verified = service.attachVerifierDecision(changeSet.id(), VerificationReport.Status.PASS,
                List.of("targeted tests passed"));
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

        service.attachVerificationReport(changeSet.id(), passingReport(service, changeSet));
        service.markApproved(changeSet.id());
        String message = "Update README through changeset";
        GitChangeSet committed = service.commit(changeSet.id(), message,
                authorization(workspace, changeSet.id(), PendingChangeAction.ActionType.COMMIT, message));

        assertEquals(GitChangeSetStatus.COMMITTED, committed.status());
        assertFalse(committed.commitHash().isBlank());
        assertFalse(before.equals(committed.commitHash()));
        assertEquals(committed.commitHash(), service.load(changeSet.id()).commitHash());
        assertTrue(git(workspace, "show", "--name-only", "--format=", committed.commitHash()).contains("README.md"));
    }

    @Test
    void repeatedCommitAuthorizationReusesLedgerResult(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "initial\nchanged\n");
        ChangeSetService service = new ChangeSetService(workspace);
        GitChangeSet changeSet = service.createFromWorkingTree("cli:direct", "team_1", "task_1");
        service.attachVerificationReport(changeSet.id(), passingReport(service, changeSet));
        service.markApproved(changeSet.id());
        String message = "Idempotent commit";
        ChangeSetActionAuthorization authorization = authorization(workspace, changeSet.id(),
                PendingChangeAction.ActionType.COMMIT, message);

        GitChangeSet first = service.commit(changeSet.id(), message, authorization);
        GitChangeSet second = service.commit(changeSet.id(), message, authorization);

        assertEquals(first.commitHash(), second.commitHash());
        assertEquals("2", git(workspace, "rev-list", "--count", "HEAD").trim());
    }

    @Test
    void commitRejectsMissingApprovalOrVerification(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "initial\nchanged\n");
        ChangeSetService service = new ChangeSetService(workspace);
        GitChangeSet changeSet = service.createFromWorkingTree("cli:direct", "team_1", "task_1");
        ChangeSetActionAuthorization authorization = authorization(workspace, changeSet.id(),
                PendingChangeAction.ActionType.COMMIT, "must not commit");

        IllegalStateException missingApproval = assertThrows(IllegalStateException.class,
                () -> service.commit(changeSet.id(), "must not commit", authorization));
        assertTrue(missingApproval.getMessage().contains("APPROVED"));

        service.markApproved(changeSet.id());
        IllegalStateException missingVerification = assertThrows(IllegalStateException.class,
                () -> service.commit(changeSet.id(), "must not commit", authorization));
        assertTrue(missingVerification.getMessage().contains("verifierStatus=PASS"));
    }

    @Test
    void rollbackExecutesCommandsAndMarksRolledBack(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        Files.writeString(workspace.resolve("README.md"), "initial\nchanged\n");
        Files.writeString(workspace.resolve("new.txt"), "new file\n");
        ChangeSetService service = new ChangeSetService(workspace);
        GitChangeSet changeSet = service.createFromWorkingTree("cli:direct", "team_1", "task_1");

        GitChangeSet rolledBack = service.rollback(changeSet.id(),
                authorization(workspace, changeSet.id(), PendingChangeAction.ActionType.ROLLBACK, ""));

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

    private static VerificationReport passingReport(ChangeSetService service, GitChangeSet changeSet) {
        return new VerificationReport("report-test", VerificationReport.Status.PASS, "", "profile",
                service.currentDiffDigest(changeSet), List.of(), List.of(), "artifact", Instant.now());
    }

    private static ChangeSetActionAuthorization authorization(Path workspace, String changeSetId,
                                                               PendingChangeAction.ActionType type, String message) {
        ApprovalService approvals = new ApprovalService(new ricbot.infra.runtime.SqliteRuntimeStore(workspace).approvalStore());
        RiskAssessment risk = RiskAssessment.of(CommandRiskLevel.HIGH, List.of("test"), type.name(),
                "change_action", List.of());
        PendingChangeAction action = PendingChangeAction.create(null, type, changeSetId, List.of(), message, risk);
        ApprovalBinding binding = new ApprovalBinding("run-test-" + java.util.UUID.randomUUID(), "approval",
                "CHANGE_" + type.name(), "action-test-" + java.util.UUID.randomUUID(), changeSetId,
                ChangeSetActionAuthorization.digest(type, changeSetId, message));
        var request = approvals.createChangeActionRequest(risk, action, binding);
        approvals.approve(request.requestId());
        return ChangeSetActionAuthorization.claimed(approvals.claim(request.requestId()));
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
