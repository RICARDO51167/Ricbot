package ricbot.application.runtime;

import ricbot.domain.agent.LocalTeamTaskExecutor;
import ricbot.domain.agent.graph.*;
import ricbot.domain.agent.graph.dto.GraphExecutionState;
import ricbot.domain.agent.graph.GraphNodeRegistry;
import ricbot.domain.agent.graph.dto.GraphNodeResult;
import ricbot.domain.runtime.AgentRuntime;
import ricbot.domain.runtime.dto.RunRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.task.*;
import ricbot.domain.verification.VerificationProfile;
import ricbot.domain.verification.VerificationReport;
import ricbot.domain.verification.WorkspaceVerificationService;
import ricbot.domain.agent.budget.BudgetPolicy;
import ricbot.domain.agent.budget.BudgetSnapshot;
import ricbot.domain.agent.budget.BudgetCoordinator;
import ricbot.domain.agent.usage.UsageLedger;
import ricbot.domain.agent.usage.UsageDelta;
import ricbot.domain.config.ModelCard;
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
    private final BudgetPolicy budgetPolicy;
    private final ModelCard.Pricing pricing;

    TeamAgentGraphFactory(Path workspace, SqliteRuntimeStore store, LLMProvider provider, String model,
                          ApprovalService approvals, TaskWorkerRunner workerRunner) {
        this(workspace, store, provider, model, approvals, workerRunner, BudgetPolicy.unlimited(), null);
    }

    TeamAgentGraphFactory(Path workspace, SqliteRuntimeStore store, LLMProvider provider, String model,
                          ApprovalService approvals, TaskWorkerRunner workerRunner,
                          BudgetPolicy budgetPolicy, ModelCard.Pricing pricing) {
        this.workspace = workspace;
        this.store = store;
        this.provider = provider;
        this.model = model;
        this.approvals = approvals;
        this.workerRunner = workerRunner;
        this.budgetPolicy = budgetPolicy != null ? budgetPolicy : BudgetPolicy.unlimited();
        this.pricing = pricing;
    }

    void runtime(AgentRuntime runtime) { this.runtime = runtime; }

    @Override public AgentGraphRuntime open(RunRequest request, GraphExecutionState checkpoint) {
        // 获取或创建与当前运行ID关联的任务调度器
        LocalTaskScheduler scheduler = schedulers.computeIfAbsent(request.runId(), this::scheduler);
        
        // 初始化图节点注册表
        GraphNodeRegistry nodes = new GraphNodeRegistry();
        TeamPlanModelPlanner planner = new TeamPlanModelPlanner(provider, model, pricing);
        BudgetCoordinator budgets = new BudgetCoordinator(store);
        
        // 注册模型规划节点：根据当前版本生成新的团队计划
        BuiltinGraphExecutors.registerModel(nodes, (state, input) -> {
            int current = state.channels().get("revision") instanceof Number number ? number.intValue() : 0;
            int revision = DefaultTeamGraph.REVISION.equals(state.nodeId()) ? current + 1 : current;
            UsageLedger before = UsageLedger.from(state.channels().get("usageLedger"));
            BudgetSnapshot snapshot = BudgetSnapshot.evaluate(budgetPolicy, before, false);
            if (snapshot.exhausted()) throw new BudgetCoordinator.BudgetExhaustedException(snapshot.reason());
            String reservationId = state.runId() + ":leader-plan:" + state.superstep() + ":" + revision;
            long inputEstimate = Math.max(1, String.valueOf(state.channels().getOrDefault("goal", "")).length() / 4L);
            var reservation = budgets.reserve(state.runId(), state.runId(), "", reservationId,
                    budgetPolicy, inputEstimate + 2048, 0, 0, 0);
            TeamPlan plan = planner.plan(state, revision);
            UsageLedger observed = planner.lastUsage();
            UsageDelta actual = new UsageDelta(observed.inputTokens(), observed.outputTokens(),
                    observed.totalTokens(), observed.modelCalls(), 0, observed.repairCalls(), 0,
                    observed.activeMillis(), observed.costMicrousd(), model, observed.costKnown());
            budgets.settle(reservation, actual);
            UsageLedger next = before.plus(observed);
            return GraphNodeResult.next("planned", Map.of("teamPlan", plan, "revision", plan.revision(),
                    "usageLedger", observed, "budgetState", BudgetSnapshot.evaluate(budgetPolicy, next, false)));
        });
        
        // 注册其他内置执行器：审批、工作执行、合并点、变更集应用和验证
        BuiltinGraphExecutors.registerApproval(nodes, approvals);
        BuiltinGraphExecutors.registerWorker(nodes, scheduler);
        BuiltinGraphExecutors.registerJoin(nodes, 12_000);
        BuiltinGraphExecutors.registerApplyChangeSets(nodes, new TaskWorktreeManager(workspace),
                new PatchLedgerService(workspace, store));
        BuiltinGraphExecutors.registerVerifier(nodes, this::verify);
        
        // 注册条件逻辑：确保版本号小于2时才允许进行某些操作
        GraphConditionRegistry conditions = new GraphConditionRegistry().register("revision-available", (state, facts) -> {
            Object revision = facts.get("revision");
            return !(revision instanceof Number number) || number.intValue() < 2;
        });
        
        // 初始化执行状态：使用检查点或创建初始状态
        GraphExecutionState seed = checkpoint != null ? checkpoint : GraphExecutionState.initial(
                DefaultTeamGraph.GRAPH_ID, request.runId(), DefaultTeamGraph.LEADER_PLAN,
                Map.of("goal", request.goal(), "workerResults", List.of(), "revision", 0,
                        "verificationProfileDigest", VerificationProfile.load(workspace).digest(),
                        "usageLedger", UsageLedger.empty(),
                        "budgetState", BudgetSnapshot.evaluate(budgetPolicy, UsageLedger.empty(), false)));
        
        // 返回配置好的运行时实例
        return new AgentGraphRuntime(DefaultTeamGraph.definition(), nodes, conditions,
                BuiltinGraphExecutors.standardSchema(), store, graphExecutor, seed);
    }

    /**
     * 为指定的运行ID创建并配置任务调度器。
     * 包含角色注册、恢复机制以及运行时唤醒回调。
     */
    private LocalTaskScheduler scheduler(String runId) {
        // 创建调度器实例，注册所有任务角色
        LocalTaskScheduler scheduler = new LocalTaskScheduler(workspace, store, store,
                LocalTaskSchedulerConfig.defaults(), Set.copyOf(ricbot.domain.agent.AgentTeamWorkerRunner.ALLOWED_TOOLS));
        LocalTeamTaskExecutor executor = new LocalTeamTaskExecutor(workspace, workerRunner);
        for (TaskRole role : TaskRole.values()) scheduler.register(role, executor);
        scheduler.recover();
        
        // 注册运行时唤醒钩子：当任务完成时尝试恢复运行时
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

    /**
     * 执行集成工作区的验证逻辑。
     * 检查工作区是否存在，加载团队计划和任务结果，并调用验证服务。
     */
    private BuiltinGraphExecutors.VerificationDecision verify(GraphExecutionState state,
                                                               Map<String, Object> input) {
        String path = String.valueOf(state.channels().getOrDefault("integrationWorkspace", ""));
        if (path.isBlank()) return new BuiltinGraphExecutors.VerificationDecision("needs_human",
                Map.of("reason", "integration workspace is missing"));
        try {
            // 反序列化团队计划
            TeamPlan plan = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .convertValue(state.channels().get("teamPlan"), TeamPlan.class);
            
            // 加载所有子任务的结果
            List<TaskResult> results = store.listByParent(state.runId()).stream()
                    .map(task -> store.loadResult(task.spec().taskId()).orElse(null))
                    .filter(java.util.Objects::nonNull).toList();
            
            // 执行验证服务
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
        // 关闭所有调度器并清空缓存
        schedulers.values().forEach(LocalTaskScheduler::close);
        schedulers.clear();
        
        // 优雅关闭线程池
        graphExecutor.shutdownNow();
        try { graphExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
