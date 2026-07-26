package ricbot.domain.agent.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentGraphRuntimeV2Test {
    @TempDir Path workspace;

    @Test
    void fanOutMergeIsStableAcrossOneHundredCompletionOrders() {
        AgentGraphDefinition graph = AgentGraphDefinition.builder("bsp", "dispatch")
                .node("dispatch").node("a").node("b").terminalNode("done")
                .fanOutEdge("dispatch", "go", "a")
                .fanOutEdge("dispatch", "go", "b")
                .edge("a", "done", "done")
                .edge("b", "done", "done")
                .build();
        GraphStateSchema schema = GraphStateSchema.builder()
                .channel("items", StateReducers.orderedAppend()).build();

        for (int seed = 0; seed < 100; seed++) {
            Random random = new Random(seed);
            GraphNodeRegistry nodes = new GraphNodeRegistry()
                    .register("dispatch", (state, input) -> GraphNodeResult.next("go", Map.of()))
                    .register("a", (state, input) -> delayed(random.nextInt(4), "a"))
                    .register("b", (state, input) -> delayed(random.nextInt(4), "b"));
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try (AgentGraphRuntime runtime = new AgentGraphRuntime(graph, nodes, new GraphConditionRegistry(),
                    schema, new InMemoryGraphRuntimeStore(), executor,
                    GraphExecutionState.initial("bsp", "run-" + seed, "dispatch", Map.of("items", List.of())))) {
                runtime.executeOne(Map.of());
                GraphExecutionState result = runtime.executeOne(Map.of());
                assertEquals(List.of("a", "b"), result.channels().get("items"));
                assertEquals(GraphExecutionStatus.COMPLETED, result.status());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void strictSchemaRejectsUnknownChannel() {
        AgentGraphDefinition graph = AgentGraphDefinition.builder("strict", "writer")
                .node("writer").terminalNode("done").edge("writer", "done", "done").build();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (AgentGraphRuntime runtime = new AgentGraphRuntime(graph,
                new GraphNodeRegistry().register("writer", (state, input) ->
                        GraphNodeResult.next("done", Map.of("undeclared", true))),
                new GraphConditionRegistry(), GraphStateSchema.builder().build(),
                new InMemoryGraphRuntimeStore(), executor, null)) {
            assertThrows(IllegalStateException.class, () -> runtime.executeOne(Map.of()));
            assertEquals(GraphExecutionStatus.FAILED, runtime.state().status());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completedPendingWriteIsReusedAfterRestart() {
        AgentGraphDefinition graph = AgentGraphDefinition.builder("recover", "work")
                .node("work").terminalNode("done").edge("work", "done", "done").build();
        GraphStateSchema schema = GraphStateSchema.builder().channel("count", StateReducers.replace()).build();
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        GraphExecutionState initial = GraphExecutionState.initial("recover", "run-recover", "work", Map.of());
        NodeActivation activation = initial.activeNodes().get(0);
        store.commitCheckpoint(initial);
        store.savePending(new GraphPendingWrite(initial.runId(), 0, activation,
                GraphNodeResult.next("done", Map.of("count", 1)), null, java.time.Instant.now()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (AgentGraphRuntime runtime = new AgentGraphRuntime(graph,
                new GraphNodeRegistry().register("work", (state, input) -> {
                    throw new AssertionError("completed activation must not rerun");
                }), new GraphConditionRegistry(), schema, store, executor, initial)) {
            assertEquals(1, runtime.executeOne(Map.of()).channels().get("count"));
        } finally {
            executor.shutdownNow();
        }
    }

    private static GraphNodeResult delayed(int millis, String value) throws InterruptedException {
        Thread.sleep(millis);
        return GraphNodeResult.next("done", Map.of("items", new ArrayList<>(List.of(value))));
    }
}
