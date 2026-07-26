package ricbot.application.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.graph.AgentGraphDefinition;
import ricbot.domain.agent.graph.AgentGraphRuntime;
import ricbot.domain.agent.graph.GraphConditionRegistry;
import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.agent.graph.GraphExecutionStatus;
import ricbot.domain.agent.graph.GraphFailurePolicy;
import ricbot.domain.agent.graph.GraphNodeRegistry;
import ricbot.domain.agent.graph.GraphNodeResult;
import ricbot.domain.agent.graph.GraphRetryPolicy;
import ricbot.domain.agent.graph.GraphStateSchema;
import ricbot.domain.runtime.RunRequest;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentRuntimeCancellationTest {
    @Test
    void durableCancellationInterruptsActiveNodeAndCannotBeOverwritten(@TempDir Path workspace) throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        var nodeExecutor = Executors.newSingleThreadExecutor();
        AgentGraphDefinition definition = AgentGraphDefinition.builder("cancel-graph", "work")
                .node("work", "work", Duration.ofMinutes(1), GraphRetryPolicy.none(), GraphFailurePolicy.FAIL_STOP)
                .terminalNode("done").edge("work", "done", "done").build();
        GraphNodeRegistry nodes = new GraphNodeRegistry().register("work", (state, input) -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return GraphNodeResult.next("done", Map.of());
            } catch (InterruptedException stop) {
                interrupted.countDown();
                throw stop;
            }
        });
        LocalAgentRuntime.GraphFactory factory = (request, checkpoint) -> new AgentGraphRuntime(definition,
                nodes, new GraphConditionRegistry(), GraphStateSchema.builder().build(), store, nodeExecutor,
                checkpoint != null ? checkpoint : GraphExecutionState.initial(
                        definition.graphId(), request.runId(), definition.entryNode(), Map.of()));
        try (LocalAgentRuntime runtime = new LocalAgentRuntime(store, new RuntimeDriver(), factory)) {
            RunRequest request = new RunRequest("cancel-running", "session", RunRequest.Mode.AGENT,
                    "wait", workspace, 8, Map.of());
            CompletableFuture<?> running = CompletableFuture.runAsync(() -> runtime.start(request));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            assertEquals(GraphExecutionStatus.CANCELLED, runtime.cancel(request.runId(), "test cancellation")
                    .state().status());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            try { running.get(5, TimeUnit.SECONDS); } catch (Exception expected) { /* cancellation wins the CAS */ }
            assertEquals(GraphExecutionStatus.CANCELLED,
                    store.loadCheckpoint(request.runId()).orElseThrow().status());
        } finally {
            nodeExecutor.shutdownNow();
        }
    }
}
