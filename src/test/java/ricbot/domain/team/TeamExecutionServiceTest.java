package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamExecutionServiceTest {

    @Test
    void runUserTaskCreatesIndependentWorktree(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace, "echo ok");
        TeamEngine engine = new TeamEngine(workspace);
        TeamExecutionService service = new TeamExecutionService(workspace, engine);

        TeamExecutionService.TeamExecutionResult result = service.runUserTask("", "Implement isolated change",
                new TeamExecutionService.TeamExecutionOptions(true, false));

        assertTrue(result.usedWorktree());
        assertTrue(result.workspacePath().contains(".workspaces"), result.workspacePath());
        assertTrue(Path.of(result.workspacePath()).getFileName().toString().startsWith("team-implement-isolated-change"), result.workspacePath());
        assertEquals(result.workspacePath(), result.workerResult().workspacePath());
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
        WorkspaceSession stored = new WorkspaceSessionStore(workspace).load(result.workspaceSessionId());
        assertNotNull(stored);
        assertEquals(result.taskId(), stored.metadata().get("taskId"));
        TeamExecutionService.TeamExecutionResult rerun = service.runTask(result.taskId(),
                new TeamExecutionService.TeamExecutionOptions(true, false));
        assertEquals(result.workspaceSessionId(), rerun.workspaceSessionId());
    }

    @Test
    void verifierRunsInsideSameWorktree(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace, "printf \"pwd:%s\\n\" \"$PWD\"");
        TeamEngine engine = new TeamEngine(workspace);
        TeamExecutionService service = new TeamExecutionService(workspace, engine);

        TeamExecutionService.TeamExecutionResult result = service.runUserTask("", "Verify worktree command",
                new TeamExecutionService.TeamExecutionOptions(true, true));

        assertEquals(VerificationResult.Status.PASS, result.verificationResult().status());
        assertTrue(result.verifierOutput().contains(result.workspacePath()), result.verifierOutput());
        assertEquals(TeamTaskHealth.HEALTHY, result.report().health());
    }

    @Test
    void failingVerifierKeepsWorktreeAndDoesNotPolluteBaseWorkspace(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace, "printf failure > verifier-output.txt\nexit 1");
        TeamEngine engine = new TeamEngine(workspace);
        TeamExecutionService service = new TeamExecutionService(workspace, engine);

        TeamExecutionService.TeamExecutionResult result = service.runUserTask("", "Failing verifier",
                new TeamExecutionService.TeamExecutionOptions(true, true));

        assertEquals(VerificationResult.Status.REJECT, result.verificationResult().status());
        assertTrue(Files.exists(Path.of(result.workspacePath()).resolve("verifier-output.txt")));
        assertFalse(Files.exists(workspace.resolve("verifier-output.txt")));
        assertTrue(Files.exists(Path.of(result.workspacePath())));
        assertEquals(TeamTaskHealth.CRITICAL, result.report().health());
    }

    private static void initGitRepo(Path workspace, String mvnwBody) throws Exception {
        git(workspace, "init");
        git(workspace, "config", "user.name", "Test");
        git(workspace, "config", "user.email", "test@example.com");
        Files.writeString(workspace.resolve("README.md"), "initial\n");
        Files.writeString(workspace.resolve("mvnw"), "#!/bin/sh\n" + mvnwBody + "\n");
        git(workspace, "add", "README.md", "mvnw");
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
