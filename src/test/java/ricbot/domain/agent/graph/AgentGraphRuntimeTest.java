package ricbot.domain.agent.graph;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class AgentGraphRuntimeTest {
    @Test
    void executesRegisteredNodesAndConditionalEdges() {
        AgentGraphDefinition graph = AgentGraphDefinition.builder("review-flow", "draft")
                .node("draft").node("review").node("revise").terminalNode("done")
                .edge("draft", "next", "review")
                .edge("review", "decision", "done", "approved", 10)
                .edge("review", "decision", "revise", "always", 0)
                .edge("revise", "next", "review")
                .build();
        GraphNodeRegistry nodes = new GraphNodeRegistry()
                .register("draft", (state, input) -> GraphNodeResult.next("next", Map.of("draft", "v1")))
                .register("review", (state, input) -> GraphNodeResult.next(
                        "decision", Map.of("approved", input.getOrDefault("approve", false))))
                .register("revise", (state, input) -> GraphNodeResult.next("next", Map.of("draft", "v2")));
        GraphConditionRegistry conditions = new GraphConditionRegistry()
                .register("approved", (state, facts) -> Boolean.TRUE.equals(facts.get("approved")));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (AgentGraphRuntime runtime = new AgentGraphRuntime(graph, nodes, conditions,
                GraphStateSchema.builder().channel("draft", StateReducers.replace())
                        .channel("approved", StateReducers.replace()).build(),
                new InMemoryGraphRuntimeStore(), executor, null)) {
            assertEquals("review", runtime.executeOne(Map.of()).nodeId());
            assertEquals("revise", runtime.executeOne(Map.of("approve", false)).nodeId());
            assertEquals("review", runtime.executeOne(Map.of()).nodeId());
            GraphExecutionState finished = runtime.executeOne(Map.of("approve", true));

            assertEquals("done", finished.nodeId());
            assertEquals(GraphExecutionStatus.COMPLETED, finished.status());
            assertEquals("v2", finished.variables().get("draft"));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void pausesSerializesAndResumesAtTheSameNode() {
        AgentGraphDefinition graph = AgentGraphDefinition.builder("approval", "approval")
                .node("approval").terminalNode("done")
                .edge("approval", "approved", "done")
                .build();
        GraphNodeRegistry nodes = new GraphNodeRegistry().register("approval", (state, input) ->
                Boolean.TRUE.equals(input.get("approved"))
                        ? GraphNodeResult.next("approved", Map.of("approval_id", input.get("approval_id")))
                        : GraphNodeResult.waitFor("human approval required",
                        GraphWait.external(state.runId() + ":approval-wait",
                                state.activeNodes().get(0).activationId(), "approval",
                                "human approval required", Map.of()), Map.of()));
        GraphStateSchema schema = GraphStateSchema.builder()
                .channel("approval_id", StateReducers.replace())
                .channel("resumed", StateReducers.replace()).build();
        InMemoryGraphRuntimeStore store = new InMemoryGraphRuntimeStore();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            GraphExecutionState paused;
            try (AgentGraphRuntime first = new AgentGraphRuntime(graph, nodes, new GraphConditionRegistry(),
                    schema, store, executor, null)) {
                paused = first.executeOne(Map.of());
                assertEquals(GraphExecutionStatus.PAUSED, paused.status());
            }
            try (AgentGraphRuntime restored = new AgentGraphRuntime(graph, nodes, new GraphConditionRegistry(),
                    schema, store, executor, paused)) {
                restored.resume(Map.of("resumed", true));
                GraphExecutionState completed = restored.executeOne(
                        Map.of("approved", true, "approval_id", "a-1"));

                assertEquals(GraphExecutionStatus.COMPLETED, completed.status());
                assertEquals("a-1", completed.variables().get("approval_id"));
                assertTrue((Boolean) completed.variables().get("resumed"));
            }
        } finally { executor.shutdownNow(); }
    }

    @Test
    void failsClosedWhenNoRouteMatches() {
        AgentGraphDefinition graph = AgentGraphDefinition.builder("broken", "node")
                .node("node").terminalNode("done").edge("node", "expected", "done").build();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (AgentGraphRuntime runtime = new AgentGraphRuntime(graph,
                new GraphNodeRegistry().register("node", (state, input) -> GraphNodeResult.next("other", Map.of())),
                new GraphConditionRegistry(), GraphStateSchema.builder().build(),
                new InMemoryGraphRuntimeStore(), executor, null)) {
            assertThrows(IllegalStateException.class, () -> runtime.executeOne(Map.of()));
            assertEquals(GraphExecutionStatus.FAILED, runtime.state().status());
        } finally { executor.shutdownNow(); }
    }
}
