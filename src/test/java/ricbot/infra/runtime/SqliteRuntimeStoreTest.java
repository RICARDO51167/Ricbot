package ricbot.infra.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.dto.SideEffectClaim;
import ricbot.domain.agent.eump.SideEffectStatus;
import ricbot.domain.agent.graph.dto.GraphExecutionState;
import ricbot.domain.agent.graph.enump.GraphExecutionStatus;
import ricbot.domain.agent.graph.enump.GraphRuntimeEventType;
import ricbot.domain.agent.dto.SideEffectRecord;
import ricbot.domain.runtime.UnknownRuntimeEventVersionException;
import ricbot.domain.runtime.dto.RuntimeInstanceRecord;
import ricbot.domain.runtime.enump.RuntimeInstanceStatus;
import ricbot.domain.task.TaskFailurePolicy;
import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TaskResult;
import ricbot.domain.task.TaskSpec;
import ricbot.domain.task.TaskStatus;
import ricbot.domain.task.TaskWorkspaceMode;
import ricbot.domain.task.TaskRole;

import java.nio.file.Path;
import java.nio.file.Files;
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
    void acceptsEmptyDatabaseAndReloadsSchemaV2() {
        SqliteRuntimeStore first = new SqliteRuntimeStore(workspace);
        assertTrue(Files.isRegularFile(first.database()));

        SqliteRuntimeStore second = new SqliteRuntimeStore(workspace);
        assertEquals(first.database(), second.database());
    }

    @Test
    void rejectsSchemaV1WithoutChangingOrArchivingIt() throws Exception {
        Path database = workspace.resolve(SqliteRuntimeStore.DATABASE_RELATIVE_PATH);
        Files.createDirectories(database.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT, digest TEXT)");
            statement.execute("INSERT INTO schema_migrations VALUES (1, 'old', 'old')");
        }
        byte[] before = Files.readAllBytes(database);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new SqliteRuntimeStore(workspace));

        assertTrue(failure.getMessage().contains("schema v1"), failure.getMessage());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(database)));
        assertFalse(Files.exists(workspace.resolve(".ricbot/archive")));
    }

    @Test
    void rejectsUnversionedLegacyDatabaseWithoutChangingIt() throws Exception {
        Path database = workspace.resolve(SqliteRuntimeStore.DATABASE_RELATIVE_PATH);
        Files.createDirectories(database.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE legacy_runs(id TEXT PRIMARY KEY)");
            statement.execute("INSERT INTO legacy_runs VALUES ('old-run')");
        }
        byte[] before = Files.readAllBytes(database);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new SqliteRuntimeStore(workspace));

        assertTrue(failure.getMessage().contains("schema unknown"), failure.getMessage());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(database)));
    }

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
            List<Callable<SideEffectClaim>> requests = List.of(
                    () -> first.sideEffectStore().claim(reservation),
                    () -> second.sideEffectStore().claim(reservation));
            var claims = pool.invokeAll(requests);
            long created = claims.stream().map(future -> {
                try { return future.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).filter(SideEffectClaim::created).count();
            assertEquals(1, created);
            assertEquals("effect-1", first.sideEffectStore().load("effect-1").orElseThrow().idempotencyKey());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void activeOwnerSideEffectCannotBeRecoveredByAnotherRuntime() {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        Instant now = Instant.now();
        RuntimeInstanceRecord owner = new RuntimeInstanceRecord("owner-active", "host", 1001,
                now.minusSeconds(10), now, now.plusSeconds(30), RuntimeInstanceStatus.ACTIVE, 0);
        store.registerRuntimeInstance(owner);
        SideEffectRecord reserved = SideEffectRecord.reserved("active-effect", "run-active", "session",
                "", "activation", "write_file", "digest", Map.of("path", "a.txt"));
        store.claimSideEffect(reserved);
        SideEffectRecord executing = reserved.claimExecution(owner.instanceId(), now.minusSeconds(1));
        store.transitionSideEffectRecord(executing, reserved.version(),
                java.util.Set.of(SideEffectStatus.RESERVED));

        assertThrows(IllegalStateException.class,
                () -> store.recoverExpiredSideEffects(owner.instanceId(), now));
        assertEquals(SideEffectStatus.EXECUTING,
                store.loadSideEffectRecord(reserved.idempotencyKey()).orElseThrow().status());
    }

    @Test
    void expiredDeadOwnerSideEffectIsRecoveredExactlyOnce() {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        Instant now = Instant.now();
        RuntimeInstanceRecord owner = new RuntimeInstanceRecord("owner-dead", "host", 1002,
                now.minusSeconds(60), now.minusSeconds(40), now.minusSeconds(30),
                RuntimeInstanceStatus.ACTIVE, 0);
        store.registerRuntimeInstance(owner);
        RuntimeInstanceRecord expired = store.saveRuntimeInstance(owner.expire(now.minusSeconds(20)),
                owner.version(), RuntimeInstanceStatus.ACTIVE);
        SideEffectRecord reserved = SideEffectRecord.reserved("dead-effect", "run-dead", "session",
                "", "activation", "write_file", "digest", Map.of("path", "b.txt"));
        store.claimSideEffect(reserved);
        SideEffectRecord executing = reserved.claimExecution(owner.instanceId(), now.minusSeconds(1));
        store.transitionSideEffectRecord(executing, reserved.version(),
                java.util.Set.of(SideEffectStatus.RESERVED));

        assertEquals(1, store.recoverExpiredSideEffects(expired.instanceId(), now));
        assertEquals(0, store.recoverExpiredSideEffects(expired.instanceId(), now));
        assertEquals(SideEffectStatus.UNKNOWN,
                store.loadSideEffectRecord(reserved.idempotencyKey()).orElseThrow().status());
    }

    @Test
    void readyActivationCanBeClaimedByOnlyOneRuntime() {
        SqliteRuntimeStore first = new SqliteRuntimeStore(workspace);
        SqliteRuntimeStore second = new SqliteRuntimeStore(workspace);
        GraphExecutionState state = GraphExecutionState.initial("graph", "activation-run", "model", Map.of());
        first.commit(state, GraphRuntimeEventType.RUN_STARTED, Map.of(), "start");
        Instant now = Instant.now();

        assertTrue(first.claimReadyActivations(state.runId(), "instance-a", now, now.plusSeconds(30)));
        assertFalse(second.claimReadyActivations(state.runId(), "instance-b", now, now.plusSeconds(30)));
        first.releaseActivationClaims(state.runId(), "instance-a");
        assertTrue(second.claimReadyActivations(state.runId(), "instance-b", now.plusSeconds(1),
                now.plusSeconds(31)));
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
    void historicalReplayUsesSequenceBoundProjectionDigest() {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        GraphExecutionState first = GraphExecutionState.initial("test-graph", "history-run", "model", Map.of());
        store.commit(first, GraphRuntimeEventType.RUN_STARTED, Map.of(), "start");
        TaskSpec spec = new TaskSpec("history-task", first.runId(), "activation", 0, 0,
                TaskRole.EXPLORER, "inspect", List.of(), List.of(), TaskWorkspaceMode.SHARED_READ,
                TaskFailurePolicy.TOLERATE, false);
        store.create(TaskRecord.planned(spec));
        long taskSequence = store.runtimeEvents(first.runId(), Long.MAX_VALUE).get(1).globalSequence();
        GraphExecutionState later = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, first.graphId(),
                first.runId(), 1, first.activeNodes(), Map.of("later", true), List.of(), List.of(),
                GraphExecutionStatus.PAUSED, first.lastNodeId(), 1, Instant.now());
        store.commit(later, GraphRuntimeEventType.SUPERSTEP_COMMITTED, Map.of(), "later");

        var replay = store.replay(first.runId(), taskSequence);

        assertTrue(replay.projectionMatches());
        assertEquals(first, replay.state());
        assertEquals(taskSequence, replay.events().get(replay.events().size() - 1).globalSequence());
    }

    @Test
    void historicalReplayFailsClosedWhenEventFactIsTampered() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        GraphExecutionState state = GraphExecutionState.initial("test-graph", "tampered-run", "model", Map.of());
        store.commit(state, GraphRuntimeEventType.RUN_STARTED, Map.of(), "start");
        TaskSpec spec = new TaskSpec("tampered-task", state.runId(), "activation", 0, 0,
                TaskRole.EXPLORER, "inspect", List.of(), List.of(), TaskWorkspaceMode.SHARED_READ,
                TaskFailurePolicy.TOLERATE, false);
        store.create(TaskRecord.planned(spec));
        long taskSequence = store.runtimeEvents(state.runId(), Long.MAX_VALUE).get(1).globalSequence();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.database());
             var statement = connection.prepareStatement("""
                     UPDATE runtime_events SET payload_json = replace(payload_json, 'inspect', 'tampered')
                     WHERE run_id = ? AND global_sequence = ?
                     """)) {
            statement.setString(1, state.runId());
            statement.setLong(2, taskSequence);
            statement.executeUpdate();
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> store.replay(state.runId(), taskSequence));
        assertTrue(failure.getMessage().contains("projection digest mismatch"), failure.getMessage());
    }

    @Test
    void forkExcludesFactsAfterLastCommittedSuperstep() {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        GraphExecutionState state = GraphExecutionState.initial("test-graph", "safe-source", "model", Map.of());
        store.commit(state, GraphRuntimeEventType.RUN_STARTED, Map.of(), "start");
        TaskSpec unsafe = new TaskSpec("unsafe-task", state.runId(), "activation", 0, 0,
                TaskRole.EXPLORER, "not committed", List.of(), List.of(), TaskWorkspaceMode.SHARED_READ,
                TaskFailurePolicy.TOLERATE, false);
        store.create(TaskRecord.planned(unsafe));

        store.fork(state.runId(), Long.MAX_VALUE, "safe-fork");

        assertTrue(store.listByParent("safe-fork").isEmpty());
        assertTrue(store.replay("safe-fork", Long.MAX_VALUE).projectionMatches());
    }

    @Test
    void headReplayDetectsProjectionTampering() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        GraphExecutionState state = GraphExecutionState.initial("test-graph", "projection-run", "model", Map.of());
        store.commit(state, GraphRuntimeEventType.RUN_STARTED, Map.of(), "start");
        TaskSpec spec = new TaskSpec("projection-task", state.runId(), "activation", 0, 0,
                TaskRole.EXPLORER, "inspect", List.of(), List.of(), TaskWorkspaceMode.SHARED_READ,
                TaskFailurePolicy.TOLERATE, false);
        store.create(TaskRecord.planned(spec));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.database());
             var statement = connection.prepareStatement(
                     "UPDATE tasks SET status = 'FAILED' WHERE task_id = ?")) {
            statement.setString(1, spec.taskId());
            statement.executeUpdate();
        }

        assertThrows(IllegalStateException.class, () -> store.replay(state.runId(), Long.MAX_VALUE));
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
    void staleRunTransitionCannotOverwriteCancellation() {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        GraphExecutionState initial = GraphExecutionState.initial("graph", "cancel-cas", "node", Map.of());
        store.commit(initial, GraphRuntimeEventType.RUN_STARTED, Map.of(), "start");
        GraphExecutionState cancelled = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION,
                initial.graphId(), initial.runId(), initial.superstep(), List.of(), initial.channels(), List.of(),
                initial.failures(), GraphExecutionStatus.CANCELLED, initial.lastNodeId(), 1, Instant.now());
        store.commit(cancelled, GraphRuntimeEventType.RUN_CANCELLED, Map.of(), "cancel");
        GraphExecutionState staleCompletion = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION,
                initial.graphId(), initial.runId(), 1, List.of(), initial.channels(), List.of(),
                initial.failures(), GraphExecutionStatus.COMPLETED, "done", 1, Instant.now());

        assertThrows(IllegalStateException.class, () -> store.commit(staleCompletion,
                GraphRuntimeEventType.RUN_COMPLETED, Map.of(), "stale-complete"));
        assertEquals(GraphExecutionStatus.CANCELLED, store.loadCheckpoint(initial.runId()).orElseThrow().status());
    }
}
