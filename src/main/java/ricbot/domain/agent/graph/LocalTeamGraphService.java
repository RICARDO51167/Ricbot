package ricbot.domain.agent.graph;

import ricbot.domain.security.ApprovalService;
import ricbot.domain.task.DurableParentRunWaker;
import ricbot.domain.task.LocalTaskScheduler;
import ricbot.domain.task.PatchLedgerService;
import ricbot.domain.task.TaskWorktreeManager;
import ricbot.domain.task.TeamPlan;
import ricbot.domain.task.TeamPlanModelPlanner;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ricbot.domain.verification.VerificationProfile;
import ricbot.infra.runtime.SqliteRuntimeStore;

/** Production assembly for the default local team graph. */
public final class LocalTeamGraphService implements AutoCloseable {
    private final Path workspace;
    private final LocalTaskScheduler scheduler;
    private final SqliteRuntimeStore graphStore;
    private final DurableParentRunWaker waker;
    private final TeamPlanModelPlanner planner;
    private final BuiltinGraphExecutors.Verifier verifier;
    private final ApprovalService approvals;
    private final ExecutorService graphExecutor = Executors.newFixedThreadPool(4);

    public LocalTeamGraphService(Path workspace, LocalTaskScheduler scheduler, TeamPlanModelPlanner planner,
                                 BuiltinGraphExecutors.Verifier verifier, ApprovalService approvals) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.scheduler = scheduler;
        this.planner = planner;
        this.verifier = verifier;
        this.approvals = approvals;
        graphStore = new SqliteRuntimeStore(this.workspace);
        waker = scheduler.parentRunWaker() instanceof DurableParentRunWaker durable ? durable : graphStore;
    }

    public GraphRunCoordinator start(String goal) {
        return open("run-" + UUID.randomUUID(), goal);
    }

    public GraphRunCoordinator open(String runId, String goal) {
        GraphNodeRegistry nodes = new GraphNodeRegistry();
        BuiltinGraphExecutors.registerModel(nodes, (state, input) -> {
            int current = state.channels().get("revision") instanceof Number number ? number.intValue() : 0;
            int revision = DefaultTeamGraph.REVISION.equals(state.nodeId()) ? current + 1 : current;
            TeamPlan plan = planner.plan(state, revision);
            return GraphNodeResult.next("planned", Map.of("teamPlan", plan, "revision", plan.revision()));
        });
        BuiltinGraphExecutors.registerApproval(nodes, approvals);
        BuiltinGraphExecutors.registerWorker(nodes, scheduler);
        BuiltinGraphExecutors.registerJoin(nodes, 12_000);
        TaskWorktreeManager worktrees = new TaskWorktreeManager(workspace);
        BuiltinGraphExecutors.registerApplyChangeSets(nodes, worktrees, new PatchLedgerService(workspace, graphStore));
        BuiltinGraphExecutors.registerVerifier(nodes, verifier);
        GraphConditionRegistry conditions = new GraphConditionRegistry().register("revision-available", (state, facts) -> {
            Object revision = facts.get("revision");
            return !(revision instanceof Number number) || number.intValue() < 2;
        });
        GraphExecutionState initial = GraphExecutionState.initial(DefaultTeamGraph.GRAPH_ID, runId,
                DefaultTeamGraph.LEADER_PLAN, Map.of("goal", goal, "workerResults", java.util.List.of(), "revision", 0,
                        "verificationProfileDigest", VerificationProfile.load(workspace).digest()));
        AgentGraphRuntime runtime = new AgentGraphRuntime(DefaultTeamGraph.definition(), nodes, conditions,
                BuiltinGraphExecutors.standardSchema(), graphStore, graphExecutor, initial);
        return new GraphRunCoordinator(runtime, waker, Map::of);
    }

    @Override public void close() { graphExecutor.shutdownNow(); }
}
