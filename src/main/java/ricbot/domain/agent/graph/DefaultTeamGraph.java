package ricbot.domain.agent.graph;

import ricbot.domain.agent.graph.dto.GraphRetryPolicy;
import ricbot.domain.agent.graph.enump.GraphFailurePolicy;

import java.time.Duration;

/** LeaderPlan -> workers -> join -> patch integration -> verifier, with at most two revision loops. */
public final class DefaultTeamGraph {
    public static final String GRAPH_ID = "ricbot-team-runtime-v3";
    public static final String LEADER_PLAN = "leader-plan";
    public static final String WORKERS = "worker-tasks";
    public static final String JOIN = "join";
    public static final String APPLY = "apply-change-sets";
    public static final String VERIFY = "verifier";
    public static final String REVISION = "revision-plan";
    public static final String HUMAN = "human";
    public static final String COMPLETE = "complete";

    private DefaultTeamGraph() {}

    public static AgentGraphDefinition definition() {
        return AgentGraphDefinition.builder(GRAPH_ID, LEADER_PLAN)
                .node(LEADER_PLAN, BuiltinGraphExecutors.MODEL, Duration.ofMinutes(5),
                        GraphRetryPolicy.readOnly(2, Duration.ofMillis(100)), GraphFailurePolicy.FAIL_STOP)
                .node(WORKERS, BuiltinGraphExecutors.WORKER, Duration.ofHours(2),
                        GraphRetryPolicy.none(), GraphFailurePolicy.FAIL_STOP)
                .node(JOIN, BuiltinGraphExecutors.JOIN, Duration.ofMinutes(1),
                        GraphRetryPolicy.none(), GraphFailurePolicy.FAIL_STOP)
                .node(APPLY, BuiltinGraphExecutors.APPLY_CHANGE_SET, Duration.ofMinutes(5),
                        GraphRetryPolicy.sideEffecting(), GraphFailurePolicy.FAIL_STOP)
                .node(VERIFY, BuiltinGraphExecutors.VERIFIER, Duration.ofMinutes(30),
                        GraphRetryPolicy.none(), GraphFailurePolicy.FAIL_STOP)
                .node(REVISION, BuiltinGraphExecutors.MODEL, Duration.ofMinutes(5),
                        GraphRetryPolicy.readOnly(2, Duration.ofMillis(100)), GraphFailurePolicy.FAIL_STOP)
                .terminalNode(HUMAN).terminalNode(COMPLETE)
                .edge(LEADER_PLAN, "planned", WORKERS)
                .edge(WORKERS, "complete", JOIN)
                .edge(WORKERS, "failed", HUMAN)
                .edge(JOIN, "joined", APPLY)
                .edge(APPLY, "applied", VERIFY)
                .edge(VERIFY, "pass", COMPLETE)
                .edge(VERIFY, "needs_human", HUMAN)
                .edge(VERIFY, "reject", REVISION, "revision-available", 10)
                .edge(VERIFY, "reject", HUMAN, "always", 0)
                .edge(REVISION, "planned", WORKERS)
                .maxSupersteps(64)
                .build();
    }
}
