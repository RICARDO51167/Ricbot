package ricbot.domain.agent.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.security.ApprovalService;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.domain.task.LocalTaskScheduler;
import ricbot.domain.task.LocalTaskSchedulerConfig;
import ricbot.domain.task.TaskResult;
import ricbot.domain.task.TaskStatus;
import ricbot.domain.task.TeamPlanModelPlanner;
import ricbot.domain.task.TaskRole;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalTeamGraphServiceTest {
    @TempDir Path repository;

    @Test
    void verifierRejectionCreatesOneRevisionThenCompletes() throws Exception {
        initializeRepository();
        SqliteRuntimeStore graphStore = new SqliteRuntimeStore(repository);
        SqliteRuntimeStore waker = graphStore;
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();
        try (LocalTaskScheduler scheduler = new LocalTaskScheduler(repository, graphStore, waker,
                LocalTaskSchedulerConfig.defaults(), Set.of())) {
            scheduler.register(TaskRole.EXPLORER, context -> {
                executions.incrementAndGet();
                return new TaskResult(2, context.task().spec().taskId(), context.task().spec().parentRunId(),
                        context.task().childRunId(), TaskStatus.SUCCEEDED, context.task().spec().planOrder(),
                        "explored", Map.of("report", "artifact.json"), "", List.of(), List.of("read evidence"), "", Instant.now());
            });
            TeamPlanModelPlanner planner = new TeamPlanModelPlanner(plannerProvider(), "test");
            try (LocalTeamGraphService service = new LocalTeamGraphService(repository, scheduler, planner,
                    (state, input) -> new BuiltinGraphExecutors.VerificationDecision(
                            verifications.incrementAndGet() == 1 ? "reject" : "pass", Map.of("checked", true)),
                    new ApprovalService(repository));
                 GraphRunCoordinator coordinator = service.start("inspect project")) {
                GraphExecutionState result = coordinator.awaitTerminalOrHumanPause(Duration.ofSeconds(10));
                assertEquals(GraphExecutionStatus.COMPLETED, result.status(), result + " tasks="
                        + scheduler.tasks(result.runId()) + " deliveries=" + waker.pending(result.runId()));
                assertEquals(DefaultTeamGraph.COMPLETE, result.nodeId());
                assertEquals(2, executions.get());
                assertEquals(2, verifications.get());
                assertEquals(1, result.channels().get("revision"));
            }
        }
    }

    private LLMProvider plannerProvider() {
        AtomicInteger plans = new AtomicInteger();
        return new LLMProvider("key", "local") {
            @Override
            public LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, String model,
                                    Integer maxTokens, Double temperature, String reasoningEffort, Object toolChoice) {
                int plan = plans.getAndIncrement();
                Map<String, Object> task = Map.of("id", "explore-" + plan, "role", "EXPLORER",
                        "goal", "inspect", "dependsOn", List.of(), "allowedTools", List.of(),
                        "workspaceMode", "SHARED_READ", "failurePolicy", "TOLERATE",
                        "allowFailedDependencies", false);
                return new LLMResponse().setToolCalls(List.of(new ToolCallRequest("call-" + plan,
                        "submit_team_plan", Map.of("tasks", List.of(task)))));
            }
        };
    }

    private void initializeRepository() throws Exception {
        git("init"); git("config", "user.email", "test@example.com"); git("config", "user.name", "Test");
        Files.writeString(repository.resolve("README.md"), "base\n");
        git("add", "README.md"); git("commit", "-m", "base");
    }
    private void git(String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(); command.add("git"); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repository.toFile()).start();
        String error = new String(process.getErrorStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(error);
    }
}
