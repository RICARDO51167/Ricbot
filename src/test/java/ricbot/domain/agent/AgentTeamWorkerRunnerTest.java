package ricbot.domain.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamRole;
import ricbot.domain.team.TeamTask;
import ricbot.domain.team.TeamWorkerResult;
import ricbot.domain.team.TeamWorkerStatus;
import ricbot.domain.workspace.GitWorktreeWorkspaceBackend;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentTeamWorkerRunnerTest {

    @Test
    void runBuildsRestrictedAgentSpecAndAppliesWorktreeChange(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        TeamEngine engine = new TeamEngine(workspace);
        TeamTask task = engine.createTask(engine.createSession("team worker").id(), TeamRole.DEVELOPER, "Update README.md");
        WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
        WorkspaceSession session = new GitWorktreeWorkspaceBackend(workspace, store)
                .createSession(workspace, "team worker", "team-worker-test");
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(session.metadata());
        metadata.put("managedBy", "ricbot");
        metadata.put("taskId", task.id());
        metadata.put("teamSessionId", task.sessionId());
        session = store.save(session.withMetadata(metadata));
        WorkspaceSession capturedSession = session;
        Path worktree = Path.of(session.workspacePath());
        AtomicReference<AgentRunSpec> captured = new AtomicReference<>();
        AgentRunner fake = new AgentRunner(fakeProvider()) {
            @Override
            public AgentRunResult run(AgentRunSpec spec) throws Exception {
                captured.set(spec);
                assertEquals(worktree.toAbsolutePath().normalize(), spec.getWorkspace());
                assertEquals("team-worker-" + task.id(), spec.getSessionKey());
                assertEquals("team-worker", spec.getMetadata().get("mode"));
                assertEquals(task.id(), spec.getMetadata().get("taskId"));
                assertEquals(capturedSession.id(), spec.getMetadata().get("workspaceSessionId"));
                assertEquals(AgentTeamWorkerRunner.ALLOWED_TOOLS, spec.getAllowedTools());
                assertFalse(spec.getTools().toolNames().contains("exec"));
                assertFalse(spec.getTools().toolNames().contains("spawn"));
                assertFalse(spec.getTools().toolNames().contains("web_fetch"));
                Object blocked = spec.getTools().execute("write_file", Map.of("path", "notes/index.json", "content", "{}"));
                assertTrue(String.valueOf(blocked).contains("runtime artifact"), String.valueOf(blocked));
                spec.getTools().execute("write_file", Map.of("path", "README.md", "content", "initial\nworker change\n"));
                return new AgentRunResult().setFinalContent("updated README").setRunId("run_worker_1");
            }
        };
        AgentTeamWorkerRunner runner = new AgentTeamWorkerRunner(workspace, fake, "model");

        TeamWorkerResult result = runner.run(task, capturedSession, worktree);

        assertNotNull(captured.get());
        assertEquals(TeamWorkerStatus.APPLIED, result.status());
        assertEquals(List.of("README.md"), result.changedFiles());
        assertTrue(Files.readString(worktree.resolve("README.md")).contains("worker change"));
        assertEquals("initial\n", Files.readString(workspace.resolve("README.md")));
    }

    @Test
    void runRejectsWorkspaceRootThatDoesNotMatchSession(@TempDir Path workspace) throws Exception {
        initGitRepo(workspace);
        TeamEngine engine = new TeamEngine(workspace);
        TeamTask task = engine.createTask(engine.createSession("team worker").id(), TeamRole.DEVELOPER, "Update README.md");
        WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
        WorkspaceSession session = new GitWorktreeWorkspaceBackend(workspace, store)
                .createSession(workspace, "team worker", "team-worker-test");
        Path otherWorktree = workspace.resolve(".workspaces").resolve("other").toAbsolutePath().normalize();
        Files.createDirectories(otherWorktree);
        AgentTeamWorkerRunner runner = new AgentTeamWorkerRunner(workspace, new AgentRunner(fakeProvider()), "model");

        TeamWorkerResult result = runner.run(task, session, otherWorktree);

        assertEquals(TeamWorkerStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("workspace session path"), result.errorMessage());
    }

    private static LLMProvider fakeProvider() {
        return new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, String model,
                                    Integer maxTokens, Double temperature, String reasoningEffort, Object toolChoice) {
                return new LLMResponse().setContent("unused").setFinishReason("stop");
            }
        };
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
