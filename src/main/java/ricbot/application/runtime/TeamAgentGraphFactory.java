package ricbot.application.runtime;

import ricbot.domain.agent.LocalTeamTaskExecutor;
import ricbot.domain.agent.graph.*;
import ricbot.domain.runtime.AgentRuntime;
import ricbot.domain.runtime.RunRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.task.*;
import ricbot.domain.verification.VerificationProfile;
import ricbot.domain.verification.VerificationReport;
import ricbot.domain.verification.WorkspaceVerificationService;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.integration.llm.api.LLMProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Production Team graph factory using the same runtime, store, activation protocol and child Run IDs. */
final class TeamAgentGraphFactory implements LocalAgentRuntime.GraphFactory, AutoCloseable {
    private final Path workspace;
    private final SqliteRuntimeStore store;
    private final LLMProvider provider;
    private final String model;
    private final ApprovalService approvals;
    private final TaskWorkerRunner workerRunner;
    private final Map<String, LocalTaskScheduler> schedulers = new ConcurrentHashMap<>();
    private final ExecutorService graphExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "ricbot-team-graph");
        thread.setDaemon(true);
        return thread;
    });
    private volatile AgentRuntime runtime;

    TeamAgentGraphFactory(Path workspace, SqliteRuntimeStore store, LLMProvider provider, String model,
                          ApprovalService approvals, TaskWorkerRunner workerRunner) {
        this.workspace = workspace;
        this.store = store;
        this.provider = provider;
        this.model = model;
        this.approvals = approvals;
        this.workerRunner = workerRunner;
    }

    void runtime(AgentRuntime runtime) { this.runtime = runtime; }

    @Override public AgentGraphRuntime open(RunRequest request, GraphExecutionState checkpoint) {
        LocalTaskScheduler scheduler = schedulers.computeIfAbsent(request.runId(), this::scheduler);
        GraphNodeRegistry nodes = new GraphNodeRegistry();
        TeamPlanModelPlanner planner = new TeamPlanModelPlanner(provider, model);
        BuiltinGraphExecutors.registerModel(nodes, (state, input) -> {
            int current = state.channels().get("revision") instanceof Number number ? number.intValue() : 0;
            int revision = DefaultTeamGraph.REVISION.equals(state.nodeId()) ? current + 1 : current;
            TeamPlan plan = planner.plan(state, revision);
            return GraphNodeResult.next("planned", Map.of("teamPlan", plan, "revision", plan.revision()));
        });
        BuiltinGraphExecutors.registerApproval(nodes, approvals);
        BuiltinGraphExecutors.registerWorker(nodes, scheduler);
        BuiltinGraphExecutors.registerJoin(nodes, 12_000);
        BuiltinGraphExecutors.registerApplyChangeSets(nodes, new TaskWorktreeManager(workspace),
                new PatchLedgerService(workspace, store));
        BuiltinGraphExecutors.registerVerifier(nodes, this::verify);
        GraphConditionRegistry conditions = new GraphConditionRegistry().register("revision-available", (state, facts) -> {
            Object revision = facts.get("revision");
            return !(revision instanceof Number number) || number.intValue() < 2;
        });
        GraphExecutionState seed = checkpoint != null ? checkpoint : GraphExecutionState.initial(
                DefaultTeamGraph.GRAPH_ID, request.runId(), DefaultTeamGraph.LEADER_PLAN,
                Map.of("goal", request.goal(), "workerResults", List.of(), "revision", 0,
                        "verificationProfileDigest", VerificationProfile.load(workspace).digest()));
        return new AgentGraphRuntime(DefaultTeamGraph.definition(), nodes, conditions,
                BuiltinGraphExecutors.standardSchema(), store, graphExecutor, seed);
    }

    private LocalTaskScheduler scheduler(String runId) {
        LocalTaskScheduler scheduler = new LocalTaskScheduler(workspace, store, store,
                LocalTaskSchedulerConfig.defaults(), Set.copyOf(ricbot.domain.agent.AgentTeamWorkerRunner.ALLOWED_TOOLS));
        LocalTeamTaskExecutor executor = new LocalTeamTaskExecutor(workspace, workerRunner);
        for (TaskRole role : TaskRole.values()) scheduler.register(role, executor);
        scheduler.recover();
        store.register(runId, ignored -> {
            AgentRuntime active = runtime;
            if (active != null) CompletableFuture.runAsync(() -> {
                try { active.resume(runId); }
                catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(TeamAgentGraphFactory.class)
                            .warn("team delivery wake failed for {}: {}", runId, failure.getMessage());
                }
            });
        });
        return scheduler;
    }

    private BuiltinGraphExecutors.VerificationDecision verify(GraphExecutionState state,
                                                               Map<String, Object> input) {
        String path = String.valueOf(state.channels().getOrDefault("integrationWorkspace", ""));
        if (path.isBlank()) return new BuiltinGraphExecutors.VerificationDecision("needs_human",
                Map.of("reason", "integration workspace is missing"));
        try {
            TeamPlan plan = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .convertValue(state.channels().get("teamPlan"), TeamPlan.class);
            List<TaskResult> results = store.listByParent(state.runId()).stream()
                    .map(task -> store.loadResult(task.spec().taskId()).orElse(null))
                    .filter(java.util.Objects::nonNull).toList();
            VerificationReport report = new WorkspaceVerificationService(workspace).verify(state.runId(),
                    Path.of(path), plan, results,
                    String.valueOf(state.channels().getOrDefault("verificationProfileDigest", "")));
            return new BuiltinGraphExecutors.VerificationDecision(report.outcome(), report.toMap());
        } catch (Exception failure) {
            return new BuiltinGraphExecutors.VerificationDecision("needs_human",
                    Map.of("reason", failure.getMessage() != null ? failure.getMessage() : "verification failed"));
        }
    }

    @Override public void close() {
        schedulers.values().forEach(LocalTaskScheduler::close);
        schedulers.clear();
        graphExecutor.shutdownNow();
        try { graphExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
