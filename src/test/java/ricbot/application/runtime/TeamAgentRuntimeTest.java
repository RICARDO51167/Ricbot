package ricbot.application.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.AgentLoop;
import ricbot.domain.agent.dto.AgentRuntimeCore;
import ricbot.domain.agent.AgentRuntimeCoreFactory;
import ricbot.domain.agent.graph.dto.GraphExecutionState;
import ricbot.domain.agent.graph.enump.GraphExecutionStatus;
import ricbot.domain.message.MessageBus;
import ricbot.domain.runtime.dto.RunRequest;
import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TaskResult;
import ricbot.infra.config.Config;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Golden proof that Team mode is reached only through the production AgentRuntime. */
class TeamAgentRuntimeTest {
    @Test
    void teamTaskUsesRealChildRunIdentityAndDurableAttempt(@TempDir Path workspace) throws Exception {
        initializeRepository(workspace);
        LLMProvider provider = provider();
        Config.ExecToolConfig exec = new Config.ExecToolConfig();
        exec.setEnable(false);
        AgentRuntimeCore core = AgentRuntimeCoreFactory.create(provider, workspace, "test-model",
                32_000, 8_000, exec, true, null, "UTC", 0);
        try (AgentLoop loop = new AgentLoop(new MessageBus(), provider, workspace, "test-model",
                8, 32_000, 32, 8_000, "none", exec, true, null,
                "UTC", false, 0, core)) {
            String runId = "team-runtime-golden";
            core.agentRuntime().start(new RunRequest(runId, "team-session", RunRequest.Mode.TEAM,
                    "inspect the repository", workspace, 64, Map.of("golden", true)));

            SqliteRuntimeStore store = ricbot.app.bootstrap.RuntimeStoreRegistry.shared(workspace);
            GraphExecutionState settled = awaitSettled(store, runId, Duration.ofSeconds(15));
            assertTrue(settled.status() == GraphExecutionStatus.PAUSED
                            || settled.status() == GraphExecutionStatus.COMPLETED,
                    () -> "unexpected team status: " + settled.status());

            List<TaskRecord> tasks = store.listByParent(runId);
            assertEquals(1, tasks.size());
            TaskRecord task = tasks.get(0);
            assertFalse(task.childRunId().isBlank());
            List<TaskResult> history = store.loadResultHistory(task.spec().taskId());
            assertEquals(1, history.size());
            assertEquals(1, history.get(0).attempt());
            assertEquals(task.childRunId(), history.get(0).childRunId());
            assertTrue(store.loadCheckpoint(task.childRunId()).isPresent(), "childRunId must be the real graph Run ID");
        }
    }

    private static GraphExecutionState awaitSettled(SqliteRuntimeStore store, String runId,
                                                     Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        GraphExecutionState state = store.loadCheckpoint(runId).orElseThrow();
        while (Instant.now().isBefore(deadline)) {
            state = store.loadCheckpoint(runId).orElseThrow();
            if ((state.status() == GraphExecutionStatus.PAUSED || state.status().terminal())
                    && !store.listByParent(runId).isEmpty()
                    && store.loadResult(store.listByParent(runId).get(0).spec().taskId()).isPresent()) return state;
            Thread.sleep(50);
        }
        fail("team runtime did not settle: " + state.status());
        return state;
    }

    private static LLMProvider provider() {
        return new LLMProvider("test", "local") {
            { setDefaultModel("test-model"); }

            @Override public LLMResponse chat(List<Map<String, Object>> messages,
                                               List<Map<String, Object>> tools, String model,
                                               Integer maxTokens, Double temperature,
                                               String reasoningEffort, Object toolChoice) {
                boolean planning = messages.stream().anyMatch(message ->
                        String.valueOf(message.get("content")).contains("Create a local multi-agent task DAG"));
                if (planning) {
                    Map<String, Object> task = Map.ofEntries(
                            Map.entry("id", "inspect"), Map.entry("role", "EXPLORER"),
                            Map.entry("goal", "inspect repository"), Map.entry("dependsOn", List.of()),
                            Map.entry("allowedTools", List.of()), Map.entry("workspaceMode", "SHARED_READ"),
                            Map.entry("failurePolicy", "TOLERATE"), Map.entry("allowFailedDependencies", false),
                            Map.entry("requiredCheckIds", List.of()), Map.entry("acceptanceCriteria", List.of()));
                    return new LLMResponse().setToolCalls(List.of(new ToolCallRequest("plan", "submit_team_plan",
                            Map.of("tasks", List.of(task))))).setFinishReason("tool_calls");
                }
                return new LLMResponse("repository inspected").setFinishReason("stop");
            }
        };
    }

    private static void initializeRepository(Path workspace) throws Exception {
        git(workspace, "init");
        git(workspace, "config", "user.email", "test@example.com");
        git(workspace, "config", "user.name", "Test");
        Files.writeString(workspace.resolve("README.md"), "base\n");
        git(workspace, "add", "README.md");
        git(workspace, "commit", "-m", "base");
    }

    private static void git(Path workspace, String... args) throws Exception {
        ArrayList<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(workspace.toFile()).start();
        String error = new String(process.getErrorStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(error);
    }
}
