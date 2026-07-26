package ricbot.infra.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.agent.graph.GraphExecutionStatus;
import ricbot.domain.agent.graph.GraphRuntimeEventType;
import ricbot.domain.agent.SideEffectRecord;
import ricbot.domain.runtime.UnknownRuntimeEventVersionException;
import ricbot.domain.runtime.RuntimeEventUpcasters;
import ricbot.domain.runtime.RuntimeFaultPoint;
import ricbot.domain.task.TaskFailurePolicy;
import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TaskResult;
import ricbot.domain.task.TaskSpec;
import ricbot.domain.task.TaskStatus;
import ricbot.domain.task.TaskWorkspaceMode;
import ricbot.domain.task.TaskRole;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteRuntimeStoreTest {
    @TempDir Path workspace;

    @Test
    void commitsEventAndProjectionAtomicallyAndReplaysFromFacts() {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        GraphExecutionState state = GraphExecutionState.initial("test-graph", "run-1", "model", Map.of("value", 1));
        store.commit(state, GraphRuntimeEventType.RUN_STARTED, Map.of("source", "test"), "start");

        var replay = store.replay("run-1", Long.MAX_VALUE);

        assertEquals(state, replay.state());
        assertTrue(replay.projectionMatches());
        assertEquals(1, store.events("run-1").size());
        assertTrue(store.database().toFile().isFile());
    }

    @Test
    void deduplicatesConcurrentEventAppendAcrossRuntimeInstances() throws Exception {
        SqliteRuntimeStore first = new SqliteRuntimeStore(workspace);
        SqliteRuntimeStore second = new SqliteRuntimeStore(workspace);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> appendFirst = () -> first.append("run-2", 0, GraphRuntimeEventType.RUN_STARTED,
                    Map.of(), "same-command").eventId();
            Callable<String> appendSecond = () -> second.append("run-2", 0, GraphRuntimeEventType.RUN_STARTED,
                    Map.of(), "same-command").eventId();
            var results = pool.invokeAll(List.of(appendFirst, appendSecond));
            assertEquals(results.get(0).get(), results.get(1).get());
            assertEquals(1, first.events("run-2").size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRuntimeInstancesReserveOneSideEffectOnly() throws Exception {
        SqliteRuntimeStore first = new SqliteRuntimeStore(workspace);
        SqliteRuntimeStore second = new SqliteRuntimeStore(workspace);
        SideEffectRecord reservation = SideEffectRecord.reserved("effect-1", "session", "write_file", "digest");
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<ricbot.domain.agent.SideEffectClaim>> requests = List.of(
                    () -> first.sideEffectStore().claim(reservation),
                    () -> second.sideEffectStore().claim(reservation));
            var claims = pool.invokeAll(requests);
            long created = claims.stream().map(future -> {
                try { return future.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).filter(ricbot.domain.agent.SideEffectClaim::created).count();
            assertEquals(1, created);
            assertEquals("effect-1", first.sideEffectStore().load("effect-1").orElseThrow().idempotencyKey());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void forkUsesLastSafeCommittedStateAndRequiresResume() {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        GraphExecutionState first = GraphExecutionState.initial("test-graph", "source", "model", Map.of("v", 1));
        var start = store.commit(first, GraphRuntimeEventType.RUN_STARTED, Map.of(), "start");
        GraphExecutionState second = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, first.graphId(),
                first.runId(), 1, first.activeNodes(), Map.of("v", 2), List.of(), List.of(),
                GraphExecutionStatus.PAUSED, first.lastNodeId(), 1, Instant.now());
        store.commit(second, GraphRuntimeEventType.SUPERSTEP_COMMITTED, Map.of(), "commit-1");

        var fork = store.fork("source", start.sequence(), "forked");

        assertEquals(Map.of("v", 1), fork.state().channels());
        assertEquals(GraphExecutionStatus.READY, fork.state().status());
        assertFalse(fork.state().runId().equals(first.runId()));
        assertTrue(store.replay("forked", Long.MAX_VALUE).projectionMatches());
    }

    @Test
    void unknownEventVersionStopsReplay() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.database());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO runtime_events(event_id, stream_id, stream_sequence, schema_version, event_type,
                      payload_type, run_id, occurred_at, payload_json, deduplication_id)
                    VALUES ('future', 'run:future', 1, 99, 'FUTURE', 'future.v99', 'future',
                      '2026-01-01T00:00:00Z', '{}', '')
                    """);
        }
        assertThrows(UnknownRuntimeEventVersionException.class,
                () -> store.runtimeEvents("future", Long.MAX_VALUE));
    }

    @Test
    void taskSettlementAndDeliveryAreAtomicAndRetryKeepsLogicalTaskId() {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        TaskSpec spec = new TaskSpec("task-1", "parent-1", "activation-1", 0, 0, TaskRole.EXPLORER,
                "inspect", List.of(), List.of(), TaskWorkspaceMode.SHARED_READ, TaskFailurePolicy.TOLERATE, false);
        TaskRecord planned = store.create(TaskRecord.planned(spec));
        TaskRecord ready = store.save(planned.transition(TaskStatus.READY, "", "ready"), planned.version());
        TaskRecord running = store.save(ready.transition(TaskStatus.RUNNING, "child-1", "running"), ready.version());
        TaskResult result = new TaskResult(2, spec.taskId(), spec.parentRunId(), "child-1", running.attempt(),
                TaskStatus.SUCCEEDED, 0, "done", Map.of(), "", List.of(), List.of("ok"), "", Instant.now());

        TaskRecord settled = store.settleAndDeliver(running, result);

        assertEquals(TaskStatus.SUCCEEDED, settled.status());
        assertEquals(1, store.pending("parent-1").size());
        assertEquals(result, store.loadResult("task-1").orElseThrow());
        TaskRecord retry = store.save(settled.retry(), settled.version());
        assertEquals("task-1", retry.spec().taskId());
        assertEquals(2, retry.attempt());
        assertTrue(store.loadResult("task-1").isEmpty());
    }

    @Test
    void injectedCrashBetweenEventAndProjectionRollsBackBoth() {
        SqliteRuntimeStore crashing = new SqliteRuntimeStore(workspace, new RuntimeEventUpcasters(List.of()), point -> {
            if (point == RuntimeFaultPoint.AFTER_EVENT_BEFORE_PROJECTION) throw new IllegalStateException("crash");
        });
        GraphExecutionState state = GraphExecutionState.initial("graph", "atomic-run", "node", Map.of());

        assertThrows(IllegalStateException.class, () -> crashing.commit(state,
                GraphRuntimeEventType.RUN_STARTED, Map.of(), "start"));

        SqliteRuntimeStore restarted = new SqliteRuntimeStore(workspace);
        assertTrue(restarted.loadCheckpoint("atomic-run").isEmpty());
        assertTrue(restarted.runtimeEvents("atomic-run", Long.MAX_VALUE).isEmpty());
    }
}
