package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertEquals(TeamTaskHealth.WARNING, result.report().health());
        assertTrue(result.report().warnings().contains("no user changes produced"), result.report().warnings().toString());
    }

    @Test
    void appliedWorkerProducesHealthyReportAndImplementationSteps(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace, "echo ok");
        TeamEngine engine = new TeamEngine(workspace);
        TeamExecutionService service = new TeamExecutionService(workspace, engine, (task, session, root) -> {
            try {
                Files.writeString(root.resolve("README.md"), "initial\nworker applied\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return new TeamWorkerResult(TeamWorkerStatus.APPLIED, List.of("README.md"),
                    "Updated README.md", List.of("APPLY_CHANGE README.md"), "", 12, "run_fake", "");
        });

        TeamExecutionService.TeamExecutionResult result = service.runUserTask("", "Update README.md",
                new TeamExecutionService.TeamExecutionOptions(true, true));

        assertEquals("APPLIED", result.workerResult().status());
        assertEquals(VerificationResult.Status.PASS, result.verificationResult().status());
        assertEquals(TeamTaskHealth.HEALTHY, result.report().health());
        assertFalse(result.report().warnings().contains("no user changes produced"), result.report().warnings().toString());
        assertTrue(result.report().totalSteps() > 0, result.report().toString());
        assertTrue(result.report().suggestedNextActions().toString().contains("/change create"), result.report().suggestedNextActions().toString());
        assertTrue(result.diff().contains("worker applied"), result.diff());
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
    }

    @Test
    void appliedWorkerWithHighRiskDiffNeedsHumanAndWarningReport(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace, "echo ok");
        TeamEngine engine = new TeamEngine(workspace);
        TeamExecutionService service = new TeamExecutionService(workspace, engine, (task, session, root) -> {
            try {
                Path target = root.resolve("src/main/java/ricbot/domain/policy/PolicyEngine.java");
                Files.createDirectories(target.getParent());
                Files.writeString(target, "package ricbot.domain.policy;\nclass PolicyEngine {}\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return new TeamWorkerResult(TeamWorkerStatus.APPLIED, List.of("src/main/java/ricbot/domain/policy/PolicyEngine.java"),
                    "Updated policy code", List.of("APPLY_CHANGE src/main/java/ricbot/domain/policy/PolicyEngine.java"), "", 12, "run_fake", "");
        });

        TeamExecutionService.TeamExecutionResult result = service.runUserTask("", "Update policy code",
                new TeamExecutionService.TeamExecutionOptions(true, true));

        assertEquals(VerificationResult.Status.NEEDS_HUMAN, result.verificationResult().status());
        assertEquals(TeamTaskHealth.WARNING, result.report().health());
        assertTrue(result.verificationResult().reason().contains("high risk diff evidence"), result.verificationResult().reason());
    }

    @Test
    void noChangesWorkerProducesWarningReport(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace, "echo ok");
        TeamEngine engine = new TeamEngine(workspace);
        TeamExecutionService service = new TeamExecutionService(workspace, engine,
                (task, session, root) -> new TeamWorkerResult(TeamWorkerStatus.NO_CHANGES, List.of(),
                        "No edits were needed.", List.of(), "", 10, "run_fake", ""));

        TeamExecutionService.TeamExecutionResult result = service.runUserTask("", "No-op task",
                new TeamExecutionService.TeamExecutionOptions(true, true));

        assertEquals("NO_CHANGES", result.workerResult().status());
        assertEquals(TeamTaskHealth.WARNING, result.report().health());
        assertTrue(result.report().warnings().contains("no user changes produced"), result.report().warnings().toString());
    }

    @Test
    void appliedWorkerWithoutVerifierEvidenceDoesNotProduceHealthyReport(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace, "echo ok");
        TeamEngine engine = new TeamEngine(workspace);
        TeamExecutionService service = new TeamExecutionService(workspace, engine, (task, session, root) -> {
            try {
                Files.writeString(root.resolve("README.md"), "initial\nworker applied\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return new TeamWorkerResult(TeamWorkerStatus.APPLIED, List.of("README.md"),
                    "Updated README.md", List.of("APPLY_CHANGE README.md"), "", 12, "run_fake", "");
        });

        TeamExecutionService.TeamExecutionResult result = service.runUserTask("", "Update README.md without verifier",
                new TeamExecutionService.TeamExecutionOptions(true, false));

        assertEquals(null, result.verificationResult());
        assertEquals(TeamTaskHealth.WARNING, result.report().health());
        assertTrue(result.report().warnings().contains("verifier evidence missing"), result.report().warnings().toString());
    }

    @Test
    void failedWorkerSkipsVerifierAndKeepsWarningOrCriticalReport(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace, "echo ok");
        TeamEngine engine = new TeamEngine(workspace);
        TeamExecutionService service = new TeamExecutionService(workspace, engine,
                (task, session, root) -> TeamWorkerResult.failed("fake worker failure", 10));

        TeamExecutionService.TeamExecutionResult result = service.runUserTask("", "Fail worker",
                new TeamExecutionService.TeamExecutionOptions(true, true));

        assertEquals("FAILED", result.workerResult().status());
        assertEquals(null, result.verificationResult());
        assertTrue(result.report().health() == TeamTaskHealth.WARNING || result.report().health() == TeamTaskHealth.CRITICAL,
                result.report().toString());
    }

    @Test
    void failingVerifierKeepsWorktreeAndDoesNotPolluteBaseWorkspace(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace, "printf failure > verifier-output.txt\nexit 1");
        TeamEngine engine = new TeamEngine(workspace);
        TeamExecutionService service = new TeamExecutionService(workspace, engine);

        TeamExecutionService.TeamExecutionResult result = service.runUserTask("", "Failing verifier",
                new TeamExecutionService.TeamExecutionOptions(true, true));

        assertEquals(VerificationResult.Status.REJECT, result.verificationResult().status());
        assertTrue(result.verificationResult().reason().contains("failed structured test evidence"), result.verificationResult().reason());
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
