package ricbot.infra.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.agent.graph.GraphExecutionStatus;
import ricbot.domain.agent.graph.GraphPendingWrite;
import ricbot.domain.agent.graph.GraphRuntimeEvent;
import ricbot.domain.agent.graph.GraphRuntimeEventType;
import ricbot.domain.agent.graph.GraphRuntimeStore;
import ricbot.domain.agent.SideEffectClaim;
import ricbot.domain.agent.SideEffectRecord;
import ricbot.domain.agent.SideEffectStore;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalRequestStore;
import ricbot.domain.session.Session;
import ricbot.domain.task.DurableParentRunWaker;
import ricbot.domain.task.TaskDelivery;
import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TaskResult;
import ricbot.domain.task.TransactionalTaskStore;
import ricbot.domain.runtime.ReplayView;
import ricbot.domain.runtime.LegacyRuntimeSnapshot;
import ricbot.domain.runtime.RuntimeDigest;
import ricbot.domain.runtime.RuntimeEventEnvelope;
import ricbot.domain.runtime.RuntimeEventUpcasters;
import ricbot.domain.runtime.RuntimeFaultInjector;
import ricbot.domain.runtime.RuntimeFaultPoint;
import ricbot.domain.verification.VerificationReport;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.change.GitChangeSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Production runtime fact store. Every connection enables the safety pragmas and every mutation uses
 * BEGIN IMMEDIATE so event and projection writes have one crash boundary.
 */
public final class SqliteRuntimeStore implements GraphRuntimeStore, TransactionalTaskStore,
        DurableParentRunWaker, AutoCloseable {
    public static final String DATABASE_RELATIVE_PATH = ".ricbot/runtime.db";
    private static final String GRAPH_EVENT_PREFIX = "GRAPH:";
    private static final String GRAPH_PAYLOAD = "ricbot.graph-event.v1";
    private static final String CHECKPOINT_PAYLOAD = "ricbot.graph-checkpoint.v1";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final Path database;
    private final String jdbcUrl;
    private final RuntimeEventUpcasters upcasters;
    private final RuntimeFaultInjector faults;
    private final Map<String, Consumer<TaskDelivery>> deliveryListeners = new ConcurrentHashMap<>();

    public SqliteRuntimeStore(Path workspace) {
        this(workspace, new RuntimeEventUpcasters(List.of()), RuntimeFaultInjector.none());
    }

    public SqliteRuntimeStore(Path workspace, RuntimeEventUpcasters upcasters) {
        this(workspace, upcasters, RuntimeFaultInjector.none());
    }

    public SqliteRuntimeStore(Path workspace, RuntimeEventUpcasters upcasters, RuntimeFaultInjector faults) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        this.database = workspace.toAbsolutePath().normalize().resolve(DATABASE_RELATIVE_PATH);
        this.jdbcUrl = "jdbc:sqlite:" + database;
        this.upcasters = upcasters != null ? upcasters : new RuntimeEventUpcasters(List.of());
        this.faults = faults != null ? faults : RuntimeFaultInjector.none();
        try {
            Files.createDirectories(database.getParent());
            migrate();
            recoverUncertainSideEffects();
        } catch (IOException e) {
            throw new IllegalStateException("cannot initialize runtime database " + database, e);
        }
    }

    public Path database() { return database; }

    public VerificationReport saveVerificationReport(String runId, VerificationReport report) {
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO verifier_reports(report_id, run_id, status, diff_digest, report_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(report_id) DO UPDATE SET status=excluded.status,
                        diff_digest=excluded.diff_digest, report_json=excluded.report_json
                    """)) {
                statement.setString(1, report.reportId()); statement.setString(2, required(runId, "runId"));
                statement.setString(3, report.status().name()); statement.setString(4, report.diffDigest());
                statement.setString(5, writeJson(report)); statement.setString(6, report.createdAt().toString());
                statement.executeUpdate();
            }
            appendProjectionEvent(connection, runId, "VERIFIER_REPORTED", "ricbot.verifier-report.v1",
                    MAPPER.valueToTree(report), "verifier:" + report.reportId(), "");
            return report;
        });
    }

    public List<VerificationReport> verificationReports() {
        return read(connection -> {
            List<VerificationReport> reports = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT report_json FROM verifier_reports ORDER BY created_at");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) reports.add(readJson(result.getString(1), VerificationReport.class));
            }
            return List.copyOf(reports);
        });
    }

    public TraceEvent saveTraceEvent(TraceEvent event) {
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO trace_events(event_id, trace_id, session_id, event_type, event_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(event_id) DO NOTHING
                    """)) {
                statement.setString(1, event.eventId()); statement.setString(2, event.traceId());
                statement.setString(3, event.sessionId()); statement.setString(4, event.type().name());
                statement.setString(5, writeJson(event)); statement.setString(6, event.createdAt());
                statement.executeUpdate();
            }
            String stream = !event.sessionId().isBlank() ? "session:" + event.sessionId() : "trace:" + event.traceId();
            appendProjectionEvent(connection, stream, "TRACE_RECORDED", "ricbot.trace-event.v1",
                    MAPPER.valueToTree(event), "trace:" + event.eventId(), "");
            return event;
        });
    }

    public List<TraceEvent> traceEvents(String traceId) {
        return read(connection -> {
            List<TraceEvent> events = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT event_json FROM trace_events WHERE trace_id = ? ORDER BY created_at, event_id")) {
                statement.setString(1, required(traceId, "traceId"));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) events.add(readJson(result.getString(1), TraceEvent.class));
                }
            }
            return List.copyOf(events);
        });
    }

    public List<String> traceIds() {
        return read(connection -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT trace_id, MAX(created_at) AS latest FROM trace_events
                    GROUP BY trace_id ORDER BY latest DESC, trace_id
                    """); ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getString(1));
            }
            return List.copyOf(ids);
        });
    }

    public GitChangeSet saveChangeSet(GitChangeSet changeSet) {
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO change_sets(change_set_id, session_id, status, change_set_json, updated_at)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT(change_set_id) DO UPDATE SET
                    status=excluded.status, change_set_json=excluded.change_set_json, updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, changeSet.id()); statement.setString(2, changeSet.sessionId());
                statement.setString(3, changeSet.status().name()); statement.setString(4, writeJson(changeSet));
                statement.setString(5, changeSet.updatedAt()); statement.executeUpdate();
            }
            String stream = !changeSet.sessionId().isBlank() ? "session:" + changeSet.sessionId()
                    : "changeset:" + changeSet.id();
            appendProjectionEvent(connection, stream, "CHANGE_SET_UPDATED", "ricbot.change-set.v1",
                    MAPPER.valueToTree(changeSet), "changeset:" + changeSet.id() + ":" + changeSet.updatedAt(), "");
            return changeSet;
        });
    }

    public Optional<GitChangeSet> changeSet(String id) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT change_set_json FROM change_sets WHERE change_set_id = ?")) {
                statement.setString(1, required(id, "changeSetId"));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readJson(result.getString(1), GitChangeSet.class)) : Optional.empty();
                }
            }
        });
    }

    public List<GitChangeSet> changeSets() {
        return read(connection -> {
            List<GitChangeSet> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT change_set_json FROM change_sets ORDER BY updated_at DESC");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(readJson(result.getString(1), GitChangeSet.class));
            }
            return List.copyOf(values);
        });
    }

    public boolean importLegacy(LegacyRuntimeSnapshot snapshot) {
        return mutate(connection -> {
            try (PreparedStatement existing = connection.prepareStatement(
                    "SELECT 1 FROM legacy_imports WHERE migration_id = ?")) {
                existing.setString(1, snapshot.migrationId());
                try (ResultSet result = existing.executeQuery()) { if (result.next()) return false; }
            }
            for (GraphExecutionState state : snapshot.activeRuns()) {
                commitState(connection, state, "LEGACY_STATE_IMPORTED",
                        Map.of("migrationId", snapshot.migrationId(), "resumable", true),
                        "legacy-import:" + snapshot.migrationId(), List.of(), List.of());
            }
            for (TaskRecord task : snapshot.tasks()) {
                if (loadTask(connection, task.spec().taskId()).isEmpty()) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO tasks(task_id, parent_run_id, status, version, record_json, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """)) {
                        bindTask(insert, task);
                        insert.executeUpdate();
                    }
                    appendProjectionEvent(connection, task.spec().parentRunId(), "LEGACY_STATE_IMPORTED",
                            "ricbot.task.v1", MAPPER.valueToTree(task),
                            "legacy-task:" + snapshot.migrationId() + ":" + task.spec().taskId(), task.spec().taskId());
                }
            }
            for (TaskResult result : snapshot.taskResults()) saveTaskResult(connection, result);
            for (TaskDelivery delivery : snapshot.deliveries()) saveDelivery(connection, delivery);
            for (ApprovalRequest approval : snapshot.approvals()) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO approvals(request_id, run_id, status, request_json, updated_at)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT(request_id) DO NOTHING
                        """)) {
                    statement.setString(1, approval.requestId());
                    statement.setString(2, approvalRunId(approval));
                    statement.setString(3, approval.status().name());
                    statement.setString(4, writeJson(approval));
                    statement.setString(5, Instant.now().toString());
                    statement.executeUpdate();
                }
            }
            for (SideEffectRecord sideEffect : snapshot.sideEffects()) saveSideEffect(connection, sideEffect);
            if (count(connection, "runtime_runs") < snapshot.activeRuns().size()
                    || count(connection, "tasks") < snapshot.tasks().size()
                    || count(connection, "task_results") < snapshot.taskResults().size()
                    || count(connection, "deliveries") < snapshot.deliveries().size()
                    || count(connection, "approvals") < snapshot.approvals().size()
                    || count(connection, "side_effects") < snapshot.sideEffects().size()) {
                throw new IllegalStateException("legacy migration count validation failed");
            }
            for (GraphExecutionState state : snapshot.activeRuns()) {
                String digest = projectedDigest(connection, state.runId()).orElse("");
                if (!RuntimeDigest.sha256(state).equals(digest)) {
                    throw new IllegalStateException("legacy replay digest validation failed: " + state.runId());
                }
            }
            try (PreparedStatement record = connection.prepareStatement("""
                    INSERT INTO legacy_imports(migration_id, imported_at, source_digest, archived_v1_records)
                    VALUES (?, ?, ?, ?)
                    """)) {
                record.setString(1, snapshot.migrationId());
                record.setString(2, Instant.now().toString());
                record.setString(3, RuntimeDigest.sha256(snapshot));
                record.setInt(4, snapshot.archivedV1Records());
                record.executeUpdate();
            }
            appendProjectionEvent(connection, "migration:" + snapshot.migrationId(), "LEGACY_MIGRATION_COMPLETED",
                    "ricbot.migration.v1", MAPPER.valueToTree(Map.of("runs", snapshot.activeRuns().size(),
                            "tasks", snapshot.tasks().size(), "approvals", snapshot.approvals().size(),
                            "sideEffects", snapshot.sideEffects().size(), "archivedV1", snapshot.archivedV1Records())),
                    "migration-complete:" + snapshot.migrationId(), "");
            return true;
        });
    }

    public ApprovalRequestStore approvalStore() {
        return new ApprovalRequestStore() {
            @Override public ApprovalRequest save(ApprovalRequest request) { return saveApproval(request); }
            @Override public Optional<ApprovalRequest> load(String requestId) { return loadApproval(requestId); }
            @Override public List<ApprovalRequest> list() { return listApprovals(); }
        };
    }

    public SideEffectStore sideEffectStore() {
        return new SideEffectStore() {
            @Override public Optional<SideEffectRecord> load(String key) { return loadSideEffectRecord(key); }
            @Override public SideEffectClaim claim(SideEffectRecord reservation) { return claimSideEffect(reservation); }
            @Override public SideEffectRecord save(SideEffectRecord record) { return saveSideEffectRecord(record); }
        };
    }

    public Session saveSession(Session session) {
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sessions(session_key, session_json, session_digest, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(session_key) DO UPDATE SET session_json=excluded.session_json,
                        session_digest=excluded.session_digest, updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, required(session.getKey(), "sessionKey"));
                statement.setString(2, writeJson(session));
                statement.setString(3, RuntimeDigest.sha256(session));
                statement.setString(4, session.getUpdatedAt().toString());
                statement.executeUpdate();
            }
            appendProjectionEvent(connection, "session:" + session.getKey(), "SESSION_UPDATED",
                    "ricbot.session.v1", MAPPER.valueToTree(session),
                    "session:" + session.getKey() + ":" + session.getUpdatedAt(), "");
            return session;
        });
    }

    public Optional<Session> loadSession(String sessionKey) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT session_json FROM sessions WHERE session_key = ?")) {
                statement.setString(1, required(sessionKey, "sessionKey"));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readJson(result.getString(1), Session.class)) : Optional.empty();
                }
            }
        });
    }

    public List<Session> listSessions() {
        return read(connection -> {
            List<Session> sessions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT session_json FROM sessions ORDER BY updated_at DESC, session_key")) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) sessions.add(readJson(result.getString(1), Session.class));
                }
            }
            return List.copyOf(sessions);
        });
    }

    public void deleteSession(String sessionKey) {
        mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE session_key = ?")) {
                statement.setString(1, required(sessionKey, "sessionKey"));
                statement.executeUpdate();
            }
            appendProjectionEvent(connection, "session:" + sessionKey, "SESSION_DELETED", "ricbot.session-delete.v1",
                    MAPPER.valueToTree(Map.of("sessionKey", sessionKey)), "session-delete:" + sessionKey, "");
            return null;
        });
    }

    @Override
    public Optional<GraphExecutionState> loadCheckpoint(String runId) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT state_json FROM runtime_runs WHERE run_id = ?")) {
                statement.setString(1, required(runId, "runId"));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readJson(result.getString(1), GraphExecutionState.class))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public void savePending(GraphPendingWrite write) {
        mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO graph_pending(run_id, superstep, activation_id, write_json, completed_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(run_id, superstep, activation_id) DO NOTHING
                    """)) {
                statement.setString(1, write.runId());
                statement.setLong(2, write.superstep());
                statement.setString(3, write.activation().activationId());
                statement.setString(4, writeJson(write));
                statement.setString(5, write.completedAt().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<GraphPendingWrite> pending(String runId, long superstep) {
        return read(connection -> {
            List<GraphPendingWrite> writes = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT write_json FROM graph_pending
                    WHERE run_id = ? AND superstep = ? ORDER BY activation_id
                    """)) {
                statement.setString(1, required(runId, "runId"));
                statement.setLong(2, superstep);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) writes.add(readJson(result.getString(1), GraphPendingWrite.class));
                }
            }
            return List.copyOf(writes);
        });
    }

    @Override
    public void commitCheckpoint(GraphExecutionState state) {
        commitState(state, "CHECKPOINT_COMMITTED", Map.of(), "checkpoint:" + state.transition());
    }

    @Override
    public GraphRuntimeEvent commit(GraphExecutionState state, GraphRuntimeEventType type,
                                    Map<String, Object> data, String deduplicationId) {
        RuntimeEventEnvelope envelope = commitState(state, GRAPH_EVENT_PREFIX + type.name(), data,
                normalizedDedupe(deduplicationId));
        return toGraphEvent(envelope);
    }

    @Override
    public GraphRuntimeEvent commit(GraphExecutionState state, GraphRuntimeEventType type,
                                    Map<String, Object> data, String deduplicationId,
                                    List<String> acknowledgedDeliveryIds, List<String> consumedSignalIds) {
        RuntimeEventEnvelope envelope = commitState(state, GRAPH_EVENT_PREFIX + type.name(), data,
                normalizedDedupe(deduplicationId), acknowledgedDeliveryIds, consumedSignalIds);
        return toGraphEvent(envelope);
    }

    @Override
    public GraphRuntimeEvent commit(GraphExecutionState state, GraphRuntimeEventType type,
                                    Map<String, Object> data, String deduplicationId,
                                    List<String> acknowledgedDeliveryIds) {
        RuntimeEventEnvelope envelope = commitState(state, GRAPH_EVENT_PREFIX + type.name(), data,
                normalizedDedupe(deduplicationId), acknowledgedDeliveryIds);
        return toGraphEvent(envelope);
    }

    @Override
    public GraphRuntimeEvent append(String runId, long superstep, GraphRuntimeEventType type,
                                    Map<String, Object> data, String deduplicationId) {
        RuntimeEventEnvelope envelope = appendEvent(runId, GRAPH_EVENT_PREFIX + type.name(), GRAPH_PAYLOAD,
                graphPayload(superstep, data, null), normalizedDedupe(deduplicationId), "", "", "", "", "", "");
        return toGraphEvent(envelope);
    }

    @Override
    public List<GraphRuntimeEvent> events(String runId) {
        return runtimeEvents(runId, Long.MAX_VALUE).stream()
                .filter(event -> event.eventType().startsWith(GRAPH_EVENT_PREFIX))
                .map(this::toGraphEvent)
                .toList();
    }

    public List<GraphExecutionState> listCheckpoints() {
        return read(connection -> {
            List<GraphExecutionState> states = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT state_json FROM runtime_runs ORDER BY updated_at DESC, run_id")) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) states.add(readJson(result.getString(1), GraphExecutionState.class));
                }
            }
            return List.copyOf(states);
        });
    }

    public RuntimeEventEnvelope appendEvent(String runId, String eventType, String payloadType, JsonNode payload,
                                            String deduplicationId, String sessionId, String taskId,
                                            String activationId, String causationId, String correlationId,
                                            String traceParent) {
        return mutate(connection -> insertEvent(connection, required(runId, "runId"), eventType, payloadType,
                payload, deduplicationId, sessionId, taskId, activationId, causationId, correlationId, traceParent));
    }

    public List<RuntimeEventEnvelope> runtimeEvents(String runId, long throughGlobalSequence) {
        return read(connection -> loadEvents(connection, required(runId, "runId"), throughGlobalSequence));
    }

    /** Rebuilds the graph state exclusively from committed event payloads and verifies its projection digest. */
    public ReplayView replay(String runId, long throughGlobalSequence) {
        String cleanRunId = required(runId, "runId");
        return read(connection -> {
            List<RuntimeEventEnvelope> events = loadEvents(connection, cleanRunId, throughGlobalSequence);
            RuntimeEventEnvelope committed = null;
            GraphExecutionState replayed = null;
            String recordedDigest = "";
            for (RuntimeEventEnvelope event : events) {
                JsonNode state = event.payload().get("state");
                if (state != null && !state.isNull()) {
                    replayed = treeValue(state, GraphExecutionState.class);
                    recordedDigest = event.payload().path("stateDigest").asText();
                    committed = event;
                }
            }
            if (replayed == null || committed == null) {
                throw new IllegalStateException("run has no committed checkpoint event: " + cleanRunId);
            }
            String rebuiltDigest = RuntimeDigest.sha256(replayed);
            String projectedDigest = projectedDigest(connection, cleanRunId).orElse("");
            boolean atHead = events.isEmpty() || latestGlobalSequence(connection, cleanRunId) ==
                    events.get(events.size() - 1).globalSequence();
            boolean matches = rebuiltDigest.equals(recordedDigest)
                    && (!atHead || rebuiltDigest.equals(projectedDigest));
            return new ReplayView(cleanRunId, throughGlobalSequence, committed.globalSequence(), replayed,
                    RuntimeDigest.sha256(events), projectedDigest, matches, events);
        });
    }

    /** Forks from the last committed superstep not newer than the requested event sequence. */
    public ReplayView fork(String sourceRunId, long throughGlobalSequence, String newRunId) {
        ReplayView source = replay(sourceRunId, throughGlobalSequence);
        String target = required(newRunId, "newRunId");
        if (loadCheckpoint(target).isPresent()) throw new IllegalArgumentException("run already exists: " + target);
        GraphExecutionState old = source.state();
        GraphExecutionState forked = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, old.graphId(), target,
                old.superstep(), old.activeNodes(), old.channels(), List.of(), old.failures(), GraphExecutionStatus.READY,
                old.lastNodeId(), old.transition() + 1, Instant.now());
        Map<String, Object> data = Map.of("sourceRunId", sourceRunId,
                "sourceEventSequence", source.committedSequence(), "requiresExplicitResume", true);
        RuntimeEventEnvelope event = commitState(forked, "RUN_FORKED", data, "fork-created");
        return new ReplayView(target, event.globalSequence(), event.globalSequence(), forked,
                RuntimeDigest.sha256(List.of(event)), RuntimeDigest.sha256(forked), true, List.of(event));
    }

    private RuntimeEventEnvelope commitState(GraphExecutionState state, String eventType,
                                             Map<String, Object> data, String deduplicationId) {
        return commitState(state, eventType, data, deduplicationId, List.of());
    }

    private RuntimeEventEnvelope commitState(GraphExecutionState state, String eventType,
                                             Map<String, Object> data, String deduplicationId,
                                             List<String> acknowledgedDeliveryIds) {
        return commitState(state, eventType, data, deduplicationId, acknowledgedDeliveryIds, List.of());
    }

    private RuntimeEventEnvelope commitState(GraphExecutionState state, String eventType,
                                             Map<String, Object> data, String deduplicationId,
                                             List<String> acknowledgedDeliveryIds, List<String> consumedSignalIds) {
        return mutate(connection -> commitState(connection, state, eventType, data, deduplicationId,
                acknowledgedDeliveryIds, consumedSignalIds));
    }

    private RuntimeEventEnvelope commitState(Connection connection, GraphExecutionState state, String eventType,
                                             Map<String, Object> data, String deduplicationId,
                                             List<String> acknowledgedDeliveryIds, List<String> consumedSignalIds)
            throws SQLException {
        JsonNode payload = graphPayload(state.superstep(), data, state);
        RuntimeEventEnvelope event = insertEvent(connection, state.runId(), eventType,
                CHECKPOINT_PAYLOAD, payload, deduplicationId, "", "",
                firstActivation(state), "", state.runId(), "");
        faults.check(RuntimeFaultPoint.AFTER_EVENT_BEFORE_PROJECTION);
        try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO runtime_runs(run_id, graph_id, state_json, state_digest, status, superstep,
                                             transition, updated_at, last_event_sequence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(run_id) DO UPDATE SET graph_id=excluded.graph_id, state_json=excluded.state_json,
                        state_digest=excluded.state_digest, status=excluded.status, superstep=excluded.superstep,
                        transition=excluded.transition, updated_at=excluded.updated_at,
                        last_event_sequence=excluded.last_event_sequence
                    """)) {
                statement.setString(1, state.runId());
                statement.setString(2, state.graphId());
                statement.setString(3, writeJson(state));
                statement.setString(4, RuntimeDigest.sha256(state));
                statement.setString(5, state.status().name());
                statement.setLong(6, state.superstep());
                statement.setLong(7, state.transition());
                statement.setString(8, state.updatedAt().toString());
                statement.setLong(9, event.globalSequence());
                statement.executeUpdate();
            }
        acknowledgeDeliveries(connection, state.runId(), acknowledgedDeliveryIds);
        consumeSignals(connection, state.runId(), consumedSignalIds);
        faults.check(RuntimeFaultPoint.AFTER_PROJECTION_BEFORE_COMMIT);
        return event;
    }

    private RuntimeEventEnvelope insertEvent(Connection connection, String runId, String eventType,
                                             String payloadType, JsonNode payload, String deduplicationId,
                                             String sessionId, String taskId, String activationId,
                                             String causationId, String correlationId, String traceParent)
            throws SQLException {
        String streamId = "run:" + runId;
        if (deduplicationId != null && !deduplicationId.isBlank()) {
            Optional<RuntimeEventEnvelope> duplicate = findByDedupe(connection, streamId, deduplicationId);
            if (duplicate.isPresent()) return duplicate.get();
        }
        long streamSequence = nextStreamSequence(connection, streamId);
        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO runtime_events(event_id, stream_id, stream_sequence, schema_version, event_type,
                    payload_type, run_id, session_id, task_id, activation_id, causation_id, correlation_id,
                    trace_parent, occurred_at, payload_json, deduplication_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, eventId);
            statement.setString(2, streamId);
            statement.setLong(3, streamSequence);
            statement.setInt(4, RuntimeEventEnvelope.CURRENT_SCHEMA_VERSION);
            statement.setString(5, required(eventType, "eventType"));
            statement.setString(6, required(payloadType, "payloadType"));
            statement.setString(7, runId);
            statement.setString(8, clean(sessionId));
            statement.setString(9, clean(taskId));
            statement.setString(10, clean(activationId));
            statement.setString(11, clean(causationId));
            statement.setString(12, clean(correlationId));
            statement.setString(13, clean(traceParent));
            statement.setString(14, occurredAt.toString());
            statement.setString(15, writeJson(payload));
            statement.setString(16, clean(deduplicationId));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT global_sequence FROM runtime_events WHERE event_id = ?")) {
            statement.setString(1, eventId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("inserted event is missing");
                return new RuntimeEventEnvelope(RuntimeEventEnvelope.CURRENT_SCHEMA_VERSION, result.getLong(1),
                        streamSequence, eventId, streamId, eventType, payloadType, runId, clean(sessionId),
                        clean(taskId), clean(activationId), clean(causationId), clean(correlationId),
                        clean(traceParent), occurredAt, payload);
            }
        }
    }

    private List<RuntimeEventEnvelope> loadEvents(Connection connection, String runId, long through) throws SQLException {
        List<RuntimeEventEnvelope> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT global_sequence, stream_sequence, event_id, stream_id, schema_version, event_type,
                       payload_type, run_id, session_id, task_id, activation_id, causation_id, correlation_id,
                       trace_parent, occurred_at, payload_json
                FROM runtime_events WHERE run_id = ? AND global_sequence <= ? ORDER BY global_sequence
                """)) {
            statement.setString(1, runId);
            statement.setLong(2, through < 0 ? Long.MAX_VALUE : through);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) events.add(readEnvelope(result));
            }
        }
        return List.copyOf(events);
    }

    private Optional<RuntimeEventEnvelope> findByDedupe(Connection connection, String streamId, String dedupe)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT global_sequence, stream_sequence, event_id, stream_id, schema_version, event_type,
                       payload_type, run_id, session_id, task_id, activation_id, causation_id, correlation_id,
                       trace_parent, occurred_at, payload_json
                FROM runtime_events WHERE stream_id = ? AND deduplication_id = ?
                """)) {
            statement.setString(1, streamId);
            statement.setString(2, dedupe);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readEnvelope(result)) : Optional.empty();
            }
        }
    }

    private RuntimeEventEnvelope readEnvelope(ResultSet result) throws SQLException {
        int version = result.getInt("schema_version");
        String eventType = result.getString("event_type");
        String payloadType = result.getString("payload_type");
        JsonNode payload = readTree(result.getString("payload_json"));
        JsonNode current = upcasters.upcast(version, eventType, payloadType, payload);
        return new RuntimeEventEnvelope(RuntimeEventEnvelope.CURRENT_SCHEMA_VERSION,
                result.getLong("global_sequence"), result.getLong("stream_sequence"),
                result.getString("event_id"), result.getString("stream_id"), eventType, payloadType,
                result.getString("run_id"), result.getString("session_id"), result.getString("task_id"),
                result.getString("activation_id"), result.getString("causation_id"),
                result.getString("correlation_id"), result.getString("trace_parent"),
                Instant.parse(result.getString("occurred_at")), current);
    }

    private GraphRuntimeEvent toGraphEvent(RuntimeEventEnvelope envelope) {
        String typeName = envelope.eventType().substring(GRAPH_EVENT_PREFIX.length());
        GraphRuntimeEventType type = GraphRuntimeEventType.valueOf(typeName);
        JsonNode dataNode = envelope.payload().path("data");
        Map<String, Object> data = dataNode.isObject() ? MAPPER.convertValue(dataNode, OBJECT_MAP) : Map.of();
        return new GraphRuntimeEvent(envelope.eventId(), envelope.runId(), envelope.streamSequence(),
                envelope.payload().path("superstep").asLong(), type, data, envelope.occurredAt());
    }

    private static JsonNode graphPayload(long superstep, Map<String, Object> data, GraphExecutionState state) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("superstep", superstep);
        payload.set("data", MAPPER.valueToTree(new LinkedHashMap<>(data != null ? data : Map.of())));
        if (state != null) {
            payload.set("state", MAPPER.valueToTree(state));
            payload.put("stateDigest", RuntimeDigest.sha256(state));
        }
        return payload;
    }

    @Override
    public TaskRecord create(TaskRecord record) {
        return mutate(connection -> {
            Optional<TaskRecord> existing = loadTask(connection, record.spec().taskId());
            if (existing.isPresent()) {
                if (!existing.orElseThrow().spec().equals(record.spec())) {
                    throw new IllegalStateException("task id collision: " + record.spec().taskId());
                }
                return existing.orElseThrow();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tasks(task_id, parent_run_id, status, version, record_json, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                bindTask(statement, record);
                statement.executeUpdate();
            }
            appendProjectionEvent(connection, record.spec().parentRunId(), "TASK_CREATED", "ricbot.task.v1",
                    MAPPER.valueToTree(record), "task-created:" + record.spec().taskId(), record.spec().taskId());
            return record;
        });
    }

    @Override
    public Optional<TaskRecord> load(String taskId) {
        return read(connection -> loadTask(connection, taskId));
    }

    @Override
    public TaskRecord save(TaskRecord record, long expectedVersion) {
        return mutate(connection -> {
            updateTask(connection, record, expectedVersion);
            appendProjectionEvent(connection, record.spec().parentRunId(), "TASK_UPDATED", "ricbot.task.v1",
                    MAPPER.valueToTree(record), "task-version:" + record.spec().taskId() + ":" + record.version(),
                    record.spec().taskId());
            return record;
        });
    }

    @Override
    public List<TaskRecord> listByParent(String parentRunId) {
        return listTasks("SELECT record_json FROM tasks WHERE parent_run_id = ? ORDER BY task_id", parentRunId);
    }

    @Override
    public List<TaskRecord> listAll() {
        return listTasks("SELECT record_json FROM tasks ORDER BY parent_run_id, task_id", null);
    }

    @Override
    public TaskResult saveResult(TaskResult result) {
        return mutate(connection -> {
            saveTaskResult(connection, result);
            appendProjectionEvent(connection, result.parentRunId(), "TASK_RESULT_RECORDED", "ricbot.task-result.v1",
                    MAPPER.valueToTree(result), "task-result:" + result.taskId(), result.taskId());
            return result;
        });
    }

    @Override
    public Optional<TaskResult> loadResult(String taskId) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT result_json FROM task_results WHERE task_id = ?")) {
                statement.setString(1, required(taskId, "taskId"));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    TaskResult loaded = readJson(result.getString(1), TaskResult.class);
                    Optional<TaskRecord> task = loadTask(connection, taskId);
                    return task.isPresent() && task.orElseThrow().attempt() == loaded.attempt()
                            ? Optional.of(loaded) : Optional.empty();
                }
            }
        });
    }

    @Override
    public TaskRecord settleAndDeliver(TaskRecord current, TaskResult result) {
        TaskDelivery delivery = delivery(result);
        TaskRecord settled = mutate(connection -> {
            TaskRecord actual = loadTask(connection, current.spec().taskId()).orElseThrow(() ->
                    new IllegalArgumentException("task not found: " + current.spec().taskId()));
            if (actual.version() != current.version()) throw new IllegalStateException("task version conflict");
            saveTaskResult(connection, result);
            faults.check(RuntimeFaultPoint.AFTER_TASK_RESULT_BEFORE_DELIVERY);
            TaskRecord next = actual.transition(result.status(), result.childRunId(),
                    result.error().isBlank() ? result.summary() : result.error());
            updateTask(connection, next, actual.version());
            saveDelivery(connection, delivery);
            faults.check(RuntimeFaultPoint.AFTER_DELIVERY_BEFORE_COMMIT);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.set("task", MAPPER.valueToTree(next));
            payload.set("result", MAPPER.valueToTree(result));
            payload.set("delivery", MAPPER.valueToTree(delivery));
            appendProjectionEvent(connection, result.parentRunId(), "TASK_COMPLETED_AND_DELIVERED",
                    "ricbot.task-delivery.v1", payload, "task-settle:" + result.taskId(), result.taskId());
            return next;
        });
        notifyDelivery(delivery);
        return settled;
    }

    @Override
    public String deliver(TaskResult result) {
        TaskDelivery delivery = delivery(result);
        mutate(connection -> {
            saveDelivery(connection, delivery);
            appendProjectionEvent(connection, result.parentRunId(), "DELIVERY_ENQUEUED", "ricbot.task-delivery.v1",
                    MAPPER.valueToTree(delivery), "delivery:" + delivery.deliveryId(), result.taskId());
            return null;
        });
        notifyDelivery(delivery);
        return delivery.deliveryId();
    }

    @Override
    public List<TaskDelivery> pending(String parentRunId) {
        return read(connection -> {
            List<TaskDelivery> deliveries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT delivery_json FROM deliveries
                    WHERE parent_run_id = ? AND acknowledged = 0 ORDER BY plan_order, task_id
                    """)) {
                statement.setString(1, required(parentRunId, "parentRunId"));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) deliveries.add(readJson(result.getString(1), TaskDelivery.class));
                }
            }
            return List.copyOf(deliveries);
        });
    }

    @Override
    public void acknowledge(String parentRunId, String deliveryId) {
        mutate(connection -> {
            acknowledgeDeliveries(connection, required(parentRunId, "parentRunId"), List.of(deliveryId));
            appendProjectionEvent(connection, parentRunId, "DELIVERY_ACKNOWLEDGED", "ricbot.delivery-ack.v1",
                    MAPPER.valueToTree(Map.of("deliveryId", deliveryId)), "delivery-ack:" + deliveryId, "");
            return null;
        });
    }

    @Override public void register(String parentRunId, Consumer<TaskDelivery> listener) {
        if (listener == null) deliveryListeners.remove(parentRunId); else deliveryListeners.put(parentRunId, listener);
    }

    @Override public void unregister(String parentRunId) { deliveryListeners.remove(parentRunId); }

    public ApprovalRequest saveApproval(ApprovalRequest request) {
        String runId = approvalRunId(request);
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO approvals(request_id, run_id, status, request_json, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(request_id) DO UPDATE SET run_id=excluded.run_id, status=excluded.status,
                        request_json=excluded.request_json, updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, request.requestId());
                statement.setString(2, runId);
                statement.setString(3, request.status().name());
                statement.setString(4, writeJson(request));
                statement.setString(5, Instant.now().toString());
                statement.executeUpdate();
            }
            appendProjectionEvent(connection, runId, "APPROVAL_" + request.status().name(), "ricbot.approval.v1",
                    MAPPER.valueToTree(request), "approval:" + request.requestId() + ":" + request.status(), "");
            return request;
        });
    }

    /** Atomically records an approval decision as a graph signal; it never executes the bound action. */
    public ApprovalRequest decideApprovalAndSignal(String requestId, boolean approved) {
        return mutate(connection -> {
            ApprovalRequest current;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT request_json FROM approvals WHERE request_id = ?")) {
                query.setString(1, required(requestId, "requestId"));
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) throw new IllegalArgumentException("approval not found: " + requestId);
                    current = readJson(result.getString(1), ApprovalRequest.class);
                }
            }
            if (current.binding() == null || !current.binding().bound()) {
                throw new IllegalStateException("approval is not bound to a graph activation");
            }
            ApprovalRequest.ApprovalStatus target = approved ? ApprovalRequest.ApprovalStatus.APPROVED
                    : ApprovalRequest.ApprovalStatus.REJECTED;
            if (current.status() != ApprovalRequest.ApprovalStatus.PENDING && current.status() != target) {
                throw new IllegalStateException("approval already settled: " + current.status());
            }
            ApprovalRequest updated = current.status() == target ? current : current.withStatus(target);
            if (!approved) updated = updated.withPendingToolCall(null).withPendingChangeAction(null);
            String runId = current.binding().runId();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE approvals SET status = ?, request_json = ?, updated_at = ? WHERE request_id = ?
                    """)) {
                update.setString(1, updated.status().name());
                update.setString(2, writeJson(updated));
                update.setString(3, Instant.now().toString());
                update.setString(4, requestId);
                update.executeUpdate();
            }
            ObjectNode signal = MAPPER.createObjectNode();
            signal.put("signalId", "approval:" + requestId);
            signal.put("signalType", approved ? "APPROVED" : "REJECTED");
            signal.put("requestId", requestId);
            signal.put("activationId", current.binding().activationId());
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO runtime_signals(signal_id, run_id, signal_type, payload_json, consumed, created_at)
                    VALUES (?, ?, ?, ?, 0, ?) ON CONFLICT(signal_id) DO NOTHING
                    """)) {
                insert.setString(1, "approval:" + requestId);
                insert.setString(2, runId);
                insert.setString(3, approved ? "APPROVED" : "REJECTED");
                insert.setString(4, writeJson(signal));
                insert.setString(5, Instant.now().toString());
                insert.executeUpdate();
            }
            appendProjectionEvent(connection, runId, "GRAPH_SIGNAL_APPENDED", "ricbot.graph-signal.v1",
                    signal, "signal:approval:" + requestId, "");
            faults.check(RuntimeFaultPoint.AFTER_SIGNAL_BEFORE_COMMIT);
            return updated;
        });
    }

    /** Atomically appends an arbitrary external graph signal. Duplicate signal IDs are idempotent. */
    public void appendSignal(String runId, String signalId, String signalType, Map<String, Object> payload) {
        mutate(connection -> {
            ObjectNode signal = MAPPER.createObjectNode();
            signal.put("signalId", required(signalId, "signalId"));
            signal.put("signalType", required(signalType, "signalType"));
            if (payload != null) signal.set("payload", MAPPER.valueToTree(payload));
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO runtime_signals(signal_id, run_id, signal_type, payload_json, consumed, created_at)
                    VALUES (?, ?, ?, ?, 0, ?) ON CONFLICT(signal_id) DO NOTHING
                    """)) {
                insert.setString(1, signalId);
                insert.setString(2, required(runId, "runId"));
                insert.setString(3, signalType);
                insert.setString(4, writeJson(signal));
                insert.setString(5, Instant.now().toString());
                if (insert.executeUpdate() > 0) {
                    appendProjectionEvent(connection, runId, "GRAPH_SIGNAL_APPENDED", "ricbot.graph-signal.v1",
                            signal, "signal:" + signalId, "");
                }
            }
            faults.check(RuntimeFaultPoint.AFTER_SIGNAL_BEFORE_COMMIT);
            return null;
        });
    }

    public List<Map<String, Object>> pendingSignals(String runId) {
        return read(connection -> {
            List<Map<String, Object>> signals = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT payload_json FROM runtime_signals WHERE run_id = ? AND consumed = 0 ORDER BY created_at
                    """)) {
                statement.setString(1, required(runId, "runId"));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) signals.add(MAPPER.convertValue(readTree(result.getString(1)), OBJECT_MAP));
                }
            }
            return List.copyOf(signals);
        });
    }

    public Optional<ApprovalRequest> loadApproval(String requestId) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT request_json FROM approvals WHERE request_id = ?")) {
                statement.setString(1, required(requestId, "requestId"));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readJson(result.getString(1), ApprovalRequest.class)) : Optional.empty();
                }
            }
        });
    }

    public List<ApprovalRequest> listApprovals() {
        return read(connection -> {
            List<ApprovalRequest> approvals = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT request_json FROM approvals ORDER BY updated_at DESC, request_id")) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) approvals.add(readJson(result.getString(1), ApprovalRequest.class));
                }
            }
            return List.copyOf(approvals);
        });
    }

    public Optional<SideEffectRecord> loadSideEffectRecord(String idempotencyKey) {
        return read(connection -> loadSideEffect(connection, idempotencyKey));
    }

    public SideEffectClaim claimSideEffect(SideEffectRecord reservation) {
        return mutate(connection -> {
            Optional<SideEffectRecord> existing = loadSideEffect(connection, reservation.idempotencyKey());
            if (existing.isPresent()) return new SideEffectClaim(existing.get(), false);
            saveSideEffect(connection, reservation);
            appendProjectionEvent(connection, sideEffectRunId(reservation), "SIDE_EFFECT_RESERVED",
                    "ricbot.side-effect.v1", MAPPER.valueToTree(reservation),
                    "side-effect-reserved:" + reservation.idempotencyKey(), "");
            return new SideEffectClaim(reservation, true);
        });
    }

    public SideEffectRecord saveSideEffectRecord(SideEffectRecord record) {
        return mutate(connection -> {
            if (loadSideEffect(connection, record.idempotencyKey()).isEmpty()) {
                throw new IllegalArgumentException("side effect reservation not found: " + record.idempotencyKey());
            }
            saveSideEffect(connection, record);
            appendProjectionEvent(connection, sideEffectRunId(record), "SIDE_EFFECT_" + record.status().name(),
                    "ricbot.side-effect.v1", MAPPER.valueToTree(record),
                    "side-effect:" + record.idempotencyKey() + ":" + record.status() + ":" + record.updatedAt(), "");
            return record;
        });
    }

    private List<TaskRecord> listTasks(String sql, String parentRunId) {
        return read(connection -> {
            List<TaskRecord> tasks = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (parentRunId != null) statement.setString(1, required(parentRunId, "parentRunId"));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) tasks.add(readJson(result.getString(1), TaskRecord.class));
                }
            }
            return List.copyOf(tasks);
        });
    }

    private static Optional<TaskRecord> loadTask(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT record_json FROM tasks WHERE task_id = ?")) {
            statement.setString(1, required(taskId, "taskId"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readJson(result.getString(1), TaskRecord.class)) : Optional.empty();
            }
        }
    }

    private static void bindTask(PreparedStatement statement, TaskRecord record) throws SQLException {
        statement.setString(1, record.spec().taskId());
        statement.setString(2, record.spec().parentRunId());
        statement.setString(3, record.status().name());
        statement.setLong(4, record.version());
        statement.setString(5, writeJson(record));
        statement.setString(6, record.updatedAt().toString());
    }

    private static void updateTask(Connection connection, TaskRecord record, long expectedVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tasks SET status = ?, version = ?, record_json = ?, updated_at = ?
                WHERE task_id = ? AND version = ?
                """)) {
            statement.setString(1, record.status().name());
            statement.setLong(2, record.version());
            statement.setString(3, writeJson(record));
            statement.setString(4, record.updatedAt().toString());
            statement.setString(5, record.spec().taskId());
            statement.setLong(6, expectedVersion);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("task version conflict: " + record.spec().taskId());
        }
    }

    private static void saveTaskResult(Connection connection, TaskResult result) throws SQLException {
        try (PreparedStatement current = connection.prepareStatement(
                "SELECT result_json FROM task_results WHERE task_id = ?")) {
            current.setString(1, result.taskId());
            try (ResultSet rows = current.executeQuery()) {
                if (rows.next()) {
                    TaskResult existing = readJson(rows.getString(1), TaskResult.class);
                    if (existing.attempt() == result.attempt() && !existing.equals(result)) {
                        throw new IllegalStateException("task result is immutable for attempt "
                                + result.taskId() + ":" + result.attempt());
                    }
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO task_results(task_id, parent_run_id, result_json, completed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET result_json=excluded.result_json,
                    parent_run_id=excluded.parent_run_id, completed_at=excluded.completed_at
                """)) {
            statement.setString(1, result.taskId());
            statement.setString(2, result.parentRunId());
            statement.setString(3, writeJson(result));
            statement.setString(4, result.completedAt().toString());
            statement.executeUpdate();
        }
    }

    private static void saveDelivery(Connection connection, TaskDelivery delivery) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO deliveries(delivery_id, parent_run_id, task_id, plan_order, delivery_json,
                                       acknowledged, delivered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(delivery_id) DO NOTHING
                """)) {
            statement.setString(1, delivery.deliveryId());
            statement.setString(2, delivery.parentRunId());
            statement.setString(3, delivery.taskId());
            statement.setInt(4, delivery.result().planOrder());
            statement.setString(5, writeJson(delivery));
            statement.setInt(6, delivery.acknowledged() ? 1 : 0);
            statement.setString(7, delivery.deliveredAt().toString());
            statement.executeUpdate();
        }
    }

    private static void acknowledgeDeliveries(Connection connection, String parentRunId, List<String> deliveryIds)
            throws SQLException {
        if (deliveryIds == null || deliveryIds.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE deliveries SET acknowledged = 1 WHERE parent_run_id = ? AND delivery_id = ?
                """)) {
            for (String deliveryId : deliveryIds) {
                statement.setString(1, parentRunId);
                statement.setString(2, required(deliveryId, "deliveryId"));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void consumeSignals(Connection connection, String runId, List<String> signalIds) throws SQLException {
        if (signalIds == null || signalIds.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE runtime_signals SET consumed = 1 WHERE run_id = ? AND signal_id = ?")) {
            for (String signalId : signalIds) {
                statement.setString(1, runId);
                statement.setString(2, required(signalId, "signalId"));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Optional<SideEffectRecord> loadSideEffect(Connection connection, String idempotencyKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT record_json FROM side_effects WHERE idempotency_key = ?")) {
            statement.setString(1, required(idempotencyKey, "idempotencyKey"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readJson(result.getString(1), SideEffectRecord.class)) : Optional.empty();
            }
        }
    }

    private static void saveSideEffect(Connection connection, SideEffectRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO side_effects(idempotency_key, session_key, tool_name, arguments_digest, status,
                                         record_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(idempotency_key) DO UPDATE SET status=excluded.status,
                    record_json=excluded.record_json, updated_at=excluded.updated_at
                """)) {
            statement.setString(1, record.idempotencyKey());
            statement.setString(2, record.sessionKey());
            statement.setString(3, record.toolName());
            statement.setString(4, record.argumentsDigest());
            statement.setString(5, record.status().name());
            statement.setString(6, writeJson(record));
            statement.setString(7, record.updatedAt().toString());
            statement.executeUpdate();
        }
    }

    private RuntimeEventEnvelope appendProjectionEvent(Connection connection, String runId, String eventType,
                                                       String payloadType, JsonNode payload, String dedupe,
                                                       String taskId) throws SQLException {
        return insertEvent(connection, runId, eventType, payloadType, payload, dedupe,
                "", taskId, "", "", runId, "");
    }

    private static TaskDelivery delivery(TaskResult result) {
        String digest = RuntimeDigest.sha256(result.taskId() + "\u0000attempt:" + result.attempt());
        return new TaskDelivery("delivery-" + digest.substring(0, 24), result.parentRunId(), result.taskId(),
                result, Instant.now(), false);
    }

    private void notifyDelivery(TaskDelivery delivery) {
        Consumer<TaskDelivery> listener = deliveryListeners.get(delivery.parentRunId());
        if (listener != null) listener.accept(delivery);
    }

    private static String approvalRunId(ApprovalRequest request) {
        return request.binding() != null && request.binding().bound()
                ? request.binding().runId() : "approval:" + request.requestId();
    }

    private static String sideEffectRunId(SideEffectRecord record) {
        return "session:" + record.sessionKey();
    }

    private void migrate() {
        mutate(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS schema_migrations(
                            version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL, digest TEXT NOT NULL)
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS runtime_events(
                            global_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                            event_id TEXT NOT NULL UNIQUE,
                            stream_id TEXT NOT NULL,
                            stream_sequence INTEGER NOT NULL,
                            schema_version INTEGER NOT NULL,
                            event_type TEXT NOT NULL,
                            payload_type TEXT NOT NULL,
                            run_id TEXT NOT NULL,
                            session_id TEXT NOT NULL DEFAULT '',
                            task_id TEXT NOT NULL DEFAULT '',
                            activation_id TEXT NOT NULL DEFAULT '',
                            causation_id TEXT NOT NULL DEFAULT '',
                            correlation_id TEXT NOT NULL DEFAULT '',
                            trace_parent TEXT NOT NULL DEFAULT '',
                            occurred_at TEXT NOT NULL,
                            payload_json TEXT NOT NULL,
                            deduplication_id TEXT NOT NULL DEFAULT '',
                            UNIQUE(stream_id, stream_sequence)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS runtime_runs(
                            run_id TEXT PRIMARY KEY,
                            graph_id TEXT NOT NULL,
                            state_json TEXT NOT NULL,
                            state_digest TEXT NOT NULL,
                            status TEXT NOT NULL,
                            superstep INTEGER NOT NULL,
                            transition INTEGER NOT NULL,
                            updated_at TEXT NOT NULL,
                            last_event_sequence INTEGER NOT NULL,
                            FOREIGN KEY(last_event_sequence) REFERENCES runtime_events(global_sequence)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS graph_pending(
                            run_id TEXT NOT NULL,
                            superstep INTEGER NOT NULL,
                            activation_id TEXT NOT NULL,
                            write_json TEXT NOT NULL,
                            completed_at TEXT NOT NULL,
                            PRIMARY KEY(run_id, superstep, activation_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS tasks(
                            task_id TEXT PRIMARY KEY,
                            parent_run_id TEXT NOT NULL,
                            status TEXT NOT NULL,
                            version INTEGER NOT NULL,
                            record_json TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS task_results(
                            task_id TEXT PRIMARY KEY,
                            parent_run_id TEXT NOT NULL,
                            result_json TEXT NOT NULL,
                            completed_at TEXT NOT NULL,
                            FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS deliveries(
                            delivery_id TEXT PRIMARY KEY,
                            parent_run_id TEXT NOT NULL,
                            task_id TEXT NOT NULL,
                            plan_order INTEGER NOT NULL,
                            delivery_json TEXT NOT NULL,
                            acknowledged INTEGER NOT NULL DEFAULT 0 CHECK(acknowledged IN (0, 1)),
                            delivered_at TEXT NOT NULL,
                            FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS approvals(
                            request_id TEXT PRIMARY KEY,
                            run_id TEXT NOT NULL,
                            status TEXT NOT NULL,
                            request_json TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS side_effects(
                            idempotency_key TEXT PRIMARY KEY,
                            session_key TEXT NOT NULL,
                            tool_name TEXT NOT NULL,
                            arguments_digest TEXT NOT NULL,
                            status TEXT NOT NULL,
                            record_json TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS runtime_signals(
                            signal_id TEXT PRIMARY KEY,
                            run_id TEXT NOT NULL,
                            signal_type TEXT NOT NULL,
                            payload_json TEXT NOT NULL,
                            consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed IN (0, 1)),
                            created_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS sessions(
                            session_key TEXT PRIMARY KEY,
                            session_json TEXT NOT NULL,
                            session_digest TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS verifier_reports(
                            report_id TEXT PRIMARY KEY,
                            run_id TEXT NOT NULL,
                            status TEXT NOT NULL,
                            diff_digest TEXT NOT NULL,
                            report_json TEXT NOT NULL,
                            created_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS trace_events(
                            event_id TEXT PRIMARY KEY,
                            trace_id TEXT NOT NULL,
                            session_id TEXT NOT NULL,
                            event_type TEXT NOT NULL,
                            event_json TEXT NOT NULL,
                            created_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS change_sets(
                            change_set_id TEXT PRIMARY KEY,
                            session_id TEXT NOT NULL,
                            status TEXT NOT NULL,
                            change_set_json TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS trace_events_trace_idx ON trace_events(trace_id, created_at)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS legacy_imports(
                            migration_id TEXT PRIMARY KEY,
                            imported_at TEXT NOT NULL,
                            source_digest TEXT NOT NULL,
                            archived_v1_records INTEGER NOT NULL
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS runtime_events_run_idx ON runtime_events(run_id, global_sequence)");
                statement.execute("CREATE INDEX IF NOT EXISTS tasks_parent_idx ON tasks(parent_run_id, task_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS deliveries_pending_idx ON deliveries(parent_run_id, acknowledged, plan_order)");
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS runtime_events_dedupe_idx
                        ON runtime_events(stream_id, deduplication_id) WHERE deduplication_id <> ''
                        """);
                try (PreparedStatement migration = connection.prepareStatement("""
                        INSERT INTO schema_migrations(version, applied_at, digest) VALUES (1, ?, ?)
                        ON CONFLICT(version) DO NOTHING
                        """)) {
                    migration.setString(1, Instant.now().toString());
                    migration.setString(2, RuntimeDigest.sha256("runtime-schema-v1"));
                    migration.executeUpdate();
                }
            }
            return null;
        });
    }

    private void recoverUncertainSideEffects() {
        mutate(connection -> {
            List<SideEffectRecord> executing = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT record_json FROM side_effects WHERE status = 'EXECUTING'");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) executing.add(readJson(result.getString(1), SideEffectRecord.class));
            }
            for (SideEffectRecord record : executing) {
                SideEffectRecord unknown = record.withStatus(ricbot.domain.agent.SideEffectStatus.UNKNOWN,
                        Map.of("reason", "runtime restarted after external execution began"), record.confirmationId());
                saveSideEffect(connection, unknown);
                appendProjectionEvent(connection, sideEffectRunId(record), "SIDE_EFFECT_UNKNOWN",
                        "ricbot.side-effect.v1", MAPPER.valueToTree(unknown),
                        "side-effect-recovered:" + record.idempotencyKey(), "");
            }
            return null;
        });
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private <T> T read(SqlWork<T> work) {
        try (Connection connection = connection()) { return work.apply(connection); }
        catch (SQLException e) { throw new IllegalStateException("runtime database read failed", e); }
    }

    private <T> T mutate(SqlWork<T> work) {
        try (Connection connection = connection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                T result = work.apply(connection);
                transaction.execute("COMMIT");
                return result;
            } catch (Exception failure) {
                try { transaction.execute("ROLLBACK"); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof SQLException sql) throw sql;
                throw new SQLException("runtime transaction failed", failure);
            }
        } catch (SQLException e) { throw new IllegalStateException("runtime database transaction failed", e); }
    }

    private static long nextStreamSequence(Connection connection, String streamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(stream_sequence), 0) + 1 FROM runtime_events WHERE stream_id = ?")) {
            statement.setString(1, streamId);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 1; }
        }
    }

    private static long latestGlobalSequence(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(global_sequence), 0) FROM runtime_events WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0; }
        }
    }

    private static Optional<String> projectedDigest(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state_digest FROM runtime_runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        if (!java.util.Set.of("runtime_runs", "tasks", "task_results", "deliveries", "approvals", "side_effects").contains(table)) {
            throw new IllegalArgumentException("unsupported count table");
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    private static String firstActivation(GraphExecutionState state) {
        return state.activeNodes().isEmpty() ? "" : state.activeNodes().get(0).activationId();
    }

    private static String normalizedDedupe(String value) {
        String clean = clean(value);
        return clean.isBlank() ? "" : clean;
    }

    private static String writeJson(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalStateException("cannot serialize runtime value", e); }
    }

    private static <T> T readJson(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (IOException e) { throw new IllegalStateException("cannot deserialize runtime value", e); }
    }

    private static JsonNode readTree(String json) {
        try { return MAPPER.readTree(json); }
        catch (IOException e) { throw new IllegalStateException("cannot deserialize runtime event", e); }
    }

    private static <T> T treeValue(JsonNode node, Class<T> type) {
        try { return MAPPER.treeToValue(node, type); }
        catch (IOException e) { throw new IllegalStateException("cannot deserialize runtime event payload", e); }
    }

    private static String required(String value, String field) {
        String result = clean(value);
        if (result.isBlank()) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static String clean(String value) { return value != null ? value.trim() : ""; }

    @Override public void close() { /* Connections are operation-scoped. */ }

    @FunctionalInterface private interface SqlWork<T> { T apply(Connection connection) throws SQLException; }
}
