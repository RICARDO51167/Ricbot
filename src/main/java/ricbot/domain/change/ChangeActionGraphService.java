package ricbot.domain.change;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.agent.graph.AgentGraphDefinition;
import ricbot.domain.agent.graph.AgentGraphRuntime;
import ricbot.domain.agent.graph.GraphRuntimeStore;
import ricbot.infra.runtime.SqliteRuntimeStore;
import ricbot.domain.agent.graph.GraphConditionRegistry;
import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.agent.graph.GraphExecutionStatus;
import ricbot.domain.agent.graph.GraphFailurePolicy;
import ricbot.domain.agent.graph.GraphNodeRegistry;
import ricbot.domain.agent.graph.GraphNodeResult;
import ricbot.domain.agent.graph.GraphRetryPolicy;
import ricbot.domain.agent.graph.GraphStateSchema;
import ricbot.domain.agent.graph.GraphWait;
import ricbot.domain.agent.graph.StateReducers;
import ricbot.domain.security.ApprovalBinding;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.RiskAssessment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Durable short graph for approval-gated ChangeSet commit and rollback actions. */
public final class ChangeActionGraphService {
    public static final String GRAPH_ID = "changeset-action-v1";
    private static final String GATE = "change-gate";
    private static final String APPROVAL = "approval";
    private static final String ACTION = "change-action";
    private static final String COMPLETE = "complete";
    private static final String REJECTED = "rejected";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "changeset-graph");
        thread.setDaemon(true);
        return thread;
    });

    private final Path workspace;
    private final ApprovalService approvals;
    private final ChangeSetService changes;
    private final GraphRuntimeStore store;

    public ChangeActionGraphService(Path workspace, ApprovalService approvals) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.approvals = approvals;
        this.changes = new ChangeSetService(this.workspace);
        this.store = new SqliteRuntimeStore(this.workspace);
    }

    public Result start(PendingChangeAction action, RiskAssessment risk) {
        String runId = "change-run-" + UUID.randomUUID();
        GraphExecutionState initial = GraphExecutionState.initial(GRAPH_ID, runId, GATE,
                Map.of("changeAction", action));
        GraphExecutionState state = drive(runtime(initial), false);
        return new Result(runId, String.valueOf(state.channels().getOrDefault("approvalRequestId", "")), state);
    }

    public Result resume(String requestId) {
        ApprovalRequest request = approvals.find(requestId);
        if (request == null || request.binding() == null || !request.binding().bound()) {
            throw new IllegalArgumentException("approval is not bound to a graph run: " + requestId);
        }
        GraphExecutionState checkpoint = store.loadCheckpoint(request.binding().runId()).orElseThrow(() ->
                new IllegalArgumentException("change action graph not found: " + request.binding().runId()));
        AgentGraphRuntime runtime = runtime(checkpoint);
        if (runtime.state().status() == GraphExecutionStatus.PAUSED
                || runtime.state().status() == GraphExecutionStatus.WAITING
                || runtime.state().status() == GraphExecutionStatus.RECOVERING) {
            runtime.resume(Map.of("approvalSignal", request.status().name()));
        }
        GraphExecutionState state = drive(runtime, true);
        return new Result(state.runId(), requestId, state);
    }

    private AgentGraphRuntime runtime(GraphExecutionState seed) {
        GraphNodeRegistry nodes = new GraphNodeRegistry()
                .register(GATE, (state, input) -> {
                    PendingChangeAction action = action(state);
                    GitChangeSet change = changes.load(action.changeSetId());
                    if (change == null) throw new IllegalArgumentException("changeset not found: " + action.changeSetId());
                    if (action.actionType() == PendingChangeAction.ActionType.COMMIT) changes.requireCommitEligible(change);
                    String digest = ChangeSetActionAuthorization.digest(action.actionType(), action.changeSetId(),
                            action.commitMessage());
                    String activation = state.runId() + ":0:" + GATE + "/" + APPROVAL + ":edge";
                    ApprovalBinding binding = new ApprovalBinding(state.runId(), activation,
                            "CHANGE_" + action.actionType().name(), state.runId() + ":" + action.actionType(),
                            action.changeSetId(), digest);
                    ApprovalRequest request = approvals.createChangeActionRequest(action.riskAssessment(), action, binding);
                    return GraphNodeResult.next("ready", Map.of("approvalRequestId", request.requestId()));
                })
                .register(APPROVAL, (state, input) -> {
                    String requestId = String.valueOf(state.channels().get("approvalRequestId"));
                    ApprovalRequest request = approvals.find(requestId);
                    if (request == null) throw new IllegalArgumentException("approval not found: " + requestId);
                    validateApprovalBinding(state, request, action(state));
                    return switch (request.status()) {
                        case PENDING -> GraphNodeResult.waitFor("change action approval required",
                                GraphWait.external(requestId, state.activeNodes().get(0).activationId(), "approval",
                                        "change action approval required", Map.of("requestId", requestId)), Map.of());
                        case REJECTED -> GraphNodeResult.next("rejected", Map.of("approvalDecision", "REJECTED"));
                        case APPROVED, CLAIMED, CONSUMED -> {
                            approvals.claim(requestId);
                            yield GraphNodeResult.next("approved", Map.of("approvalDecision", "APPROVED"));
                        }
                    };
                })
                .register(ACTION, (state, input) -> {
                    PendingChangeAction action = action(state);
                    ApprovalRequest request = approvals.find(String.valueOf(state.channels().get("approvalRequestId")));
                    if (request != null && request.consumed()) {
                        GitChangeSet recovered = changes.load(action.changeSetId());
                        boolean completed = recovered != null && (action.actionType() == PendingChangeAction.ActionType.COMMIT
                                ? recovered.status() == GitChangeSetStatus.COMMITTED
                                : recovered.status() == GitChangeSetStatus.ROLLED_BACK);
                        if (!completed) throw new IllegalStateException(
                                "approval was consumed but change action result is unavailable; human confirmation required");
                        return GraphNodeResult.next("done", Map.of("changeActionResult", recovered.toMap()));
                    }
                    ChangeSetActionAuthorization authorization = ChangeSetActionAuthorization.claimed(request);
                    GitChangeSet result = action.actionType() == PendingChangeAction.ActionType.COMMIT
                            ? changes.commit(action.changeSetId(), action.commitMessage(), authorization)
                            : changes.rollback(action.changeSetId(), authorization);
                    approvals.completeClaim(request.requestId());
                    return GraphNodeResult.next("done", Map.of("changeActionResult", result.toMap()));
                });
        return new AgentGraphRuntime(definition(), nodes, new GraphConditionRegistry(), schema(), store, EXECUTOR, seed);
    }

    private static GraphExecutionState drive(AgentGraphRuntime runtime, boolean close) {
        try {
            while (!runtime.state().status().terminal() && runtime.state().status() == GraphExecutionStatus.READY) {
                runtime.executeOne(Map.of());
            }
            return runtime.state();
        } finally { if (close || runtime.state().status() == GraphExecutionStatus.PAUSED) runtime.close(); }
    }

    private static PendingChangeAction action(GraphExecutionState state) {
        Object raw = state.channels().get("changeAction");
        return raw instanceof PendingChangeAction action ? action : MAPPER.convertValue(raw, PendingChangeAction.class);
    }
    private static void validateApprovalBinding(GraphExecutionState state, ApprovalRequest request,
                                                PendingChangeAction action) {
        ApprovalBinding binding = request.binding();
        String activationId = state.activeNodes().isEmpty() ? "" : state.activeNodes().get(0).activationId();
        if (binding == null || !binding.bound() || !state.runId().equals(binding.runId())
                || !activationId.equals(binding.activationId())
                || !("CHANGE_" + action.actionType().name()).equals(binding.actionType())
                || !action.changeSetId().equals(binding.targetId())
                || !ChangeSetActionAuthorization.digest(action.actionType(), action.changeSetId(), action.commitMessage())
                    .equals(binding.actionDigest())) {
            throw new IllegalStateException("approval binding does not match graph action");
        }
        if (request.isExpired(java.time.Instant.now())) {
            throw new IllegalStateException("approval request expired: " + request.requestId());
        }
    }
    private static AgentGraphDefinition definition() {
        return AgentGraphDefinition.builder(GRAPH_ID, GATE)
                .node(GATE, GATE, Duration.ofMinutes(1), GraphRetryPolicy.none(), GraphFailurePolicy.FAIL_STOP)
                .node(APPROVAL, APPROVAL, Duration.ofMinutes(1), GraphRetryPolicy.none(), GraphFailurePolicy.FAIL_STOP)
                .node(ACTION, ACTION, Duration.ofMinutes(5), GraphRetryPolicy.sideEffecting(), GraphFailurePolicy.FAIL_STOP)
                .terminalNode(COMPLETE).terminalNode(REJECTED)
                .edge(GATE, "ready", APPROVAL).edge(APPROVAL, "approved", ACTION)
                .edge(APPROVAL, "rejected", REJECTED).edge(ACTION, "done", COMPLETE).maxSupersteps(8).build();
    }
    private static GraphStateSchema schema() {
        return GraphStateSchema.builder().channel("changeAction", StateReducers.replaceOnce())
                .channel("approvalRequestId", StateReducers.replaceOnce())
                .channel("approvalSignal", StateReducers.replace())
                .channel("approvalDecision", StateReducers.replace())
                .channel("changeActionResult", StateReducers.replace()).build();
    }
    public record Result(String runId, String requestId, GraphExecutionState state) { }
}
