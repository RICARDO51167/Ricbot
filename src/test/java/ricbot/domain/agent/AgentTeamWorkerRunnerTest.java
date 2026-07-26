package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.task.TaskRole;
import ricbot.domain.task.TaskWorkerRequest;
import ricbot.domain.task.TaskWorkerStatus;
import ricbot.domain.workspace.GitWorktreeWorkspaceBackend;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentTeamWorkerRunnerTest {
    @Test
    void childRunIsRestrictedToManagedWorktree(@TempDir Path workspace) throws Exception {
        git(workspace, "init"); git(workspace, "config", "user.name", "Test");
        git(workspace, "config", "user.email", "test@example.com");
        Files.writeString(workspace.resolve("README.md"), "base\n");
        git(workspace, "add", "README.md"); git(workspace, "commit", "-m", "base");
        var sessions = new WorkspaceSessionStore(workspace);
        var worktree = new GitWorktreeWorkspaceBackend(workspace, sessions)
                .createSession(workspace, "task", "worker-test");
        Path root = Path.of(worktree.workspacePath());
        GraphRunService fake = new GraphRunService(provider()) {
            @Override public AgentRunResult run(AgentRunSpec spec) {
                assertEquals(root, spec.getWorkspace());
                assertFalse(spec.getTools().toolNames().contains("exec"));
                spec.getTools().execute("write_file", Map.of("path", "README.md", "content", "base\nchanged\n"));
                return new AgentRunResult().setFinalContent("done").setRunId("child-run");
            }
        };
        var request = new TaskWorkerRequest("task-1", "parent-1", TaskRole.DEVELOPER, "update readme");
        var result = new AgentTeamWorkerRunner(workspace, fake, "model").run(request, worktree, root);
        assertEquals(TaskWorkerStatus.APPLIED, result.status());
        assertEquals(List.of("README.md"), result.changedFiles());
        assertEquals("base\n", Files.readString(workspace.resolve("README.md")));
    }

    private static LLMProvider provider() {
        return new LLMProvider("k", "local") {
            @Override public LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                    String model, Integer maxTokens, Double temperature, String reasoningEffort, Object toolChoice) {
                return new LLMResponse().setContent("done").setFinishReason("stop");
            }
        };
    }
    private static void git(Path root, String... args) throws Exception {
        var command = new java.util.ArrayList<String>(); command.add("git"); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(root.toFile()).start();
        String error = new String(process.getErrorStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(error);
    }
}
