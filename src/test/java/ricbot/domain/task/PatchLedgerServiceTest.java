package ricbot.domain.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.graph.InMemoryGraphRuntimeStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchLedgerServiceTest {
    @TempDir Path repository;

    @Test
    void appliesInOrderDeduplicatesAndLeavesMainWorkspaceUntouchedOnConflict() throws Exception {
        git(repository, "init");
        git(repository, "config", "user.email", "test@example.com");
        git(repository, "config", "user.name", "Test");
        Files.writeString(repository.resolve("file.txt"), "base\n");
        git(repository, "add", "file.txt");
        git(repository, "commit", "-m", "base");

        TaskWorktreeManager manager = new TaskWorktreeManager(repository);
        TaskWorkspaceLease first = manager.worker(spec("first", 0));
        TaskWorkspaceLease second = manager.worker(spec("second", 1));
        Files.writeString(first.path().resolve("file.txt"), "first\n");
        Files.writeString(second.path().resolve("file.txt"), "second\n");
        TaskResult firstResult = manager.attachPatch(success("first", 0), first);
        TaskResult secondResult = manager.attachPatch(success("second", 1), second);
        TaskWorkspaceLease integration = manager.integration("run");
        PatchLedgerService ledger = new PatchLedgerService(repository, new InMemoryGraphRuntimeStore());

        assertEquals(PatchApplyResult.Status.APPLIED, ledger.apply("run", integration.path(), firstResult).status());
        assertEquals(PatchApplyResult.Status.ALREADY_APPLIED, ledger.apply("run", integration.path(), firstResult).status());
        assertEquals(PatchApplyResult.Status.CONFLICT, ledger.apply("run", integration.path(), secondResult).status());
        assertEquals("first\n", Files.readString(integration.path().resolve("file.txt")));
        assertEquals("base\n", Files.readString(repository.resolve("file.txt")));
        assertTrue(git(repository, "diff", "--", "file.txt").isBlank());
    }

    private static TaskSpec spec(String id, int order) {
        return new TaskSpec(id, "run", "activation", order, 0, ricbot.domain.task.TaskRole.DEVELOPER, id,
                List.of(), List.of(), TaskWorkspaceMode.ISOLATED_WORKTREE, TaskFailurePolicy.FAIL_FAST, false);
    }
    private static TaskResult success(String id, int order) {
        return new TaskResult(2, id, "run", "child-" + id, TaskStatus.SUCCEEDED, order, id, Map.of(), "",
                List.of(), List.of(), "", Instant.now());
    }
    private static String git(Path directory, String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git"); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        int code = process.waitFor();
        if (code != 0) throw new IllegalStateException(stderr);
        return stdout;
    }
}
