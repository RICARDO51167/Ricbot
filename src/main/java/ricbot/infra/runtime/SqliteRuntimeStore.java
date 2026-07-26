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
import ricbot.domain.agent.graph.NodeActivation;
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
import ricbot.domain.runtime.RuntimeDigest;
import ricbot.domain.runtime.RuntimeEventEnvelope;
import ricbot.domain.runtime.RuntimeEventUpcasters;
import ricbot.domain.runtime.RuntimeFaultInjector;
import ricbot.domain.runtime.RuntimeFaultPoint;
import ricbot.domain.runtime.RuntimeInstanceRecord;
import ricbot.domain.runtime.RuntimeInstanceStatus;
import ricbot.domain.runtime.RuntimeAggregate;
import ricbot.domain.runtime.RuntimeReducer;
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
import java.util.Set;
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
            @Override public List<SideEffectRecord> list() { return listSideEffectRecords(); }
            @Override public SideEffectClaim claim(SideEffectRecord reservation) { return claimSideEffect(reservation); }
            @Override public SideEffectRecord transition(SideEffectRecord record, long expectedVersion,
                                                         Set<ricbot.domain.agent.SideEffectStatus> allowedSources) {
                return transitionSideEffectRecord(record, expectedVersion, allowedSources);
            }
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
        return read(connection -> loadCheckpoint(connection, required(runId, "runId")));
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
    public void scheduleRetries(GraphExecutionState state, List<ricbot.domain.agent.graph.GraphRetrySchedule> retries) {
        if (retries == null || retries.isEmpty()) throw new IllegalArgumentException("retries are required");
        mutate(connection -> {
            List<Map<String, Object>> facts = retries.stream().map(retry -> Map.<String, Object>of(
                    "activationId", retry.activation().activationId(),
                    "nodeId", retry.activation().nodeId(),
                    "completedAttempt", retry.activation().attempt(),
                    "nextAttempt", retry.activation().attempt() + 1,
                    "availableAt", retry.availableAt().toString(),
                    "failureKind", retry.failureKind(),
                    "message", retry.message())).toList();
            commitState(connection, state, GRAPH_EVENT_PREFIX + GraphRuntimeEventType.NODE_RETRY_SCHEDULED.name(),
                    Map.of("retries", facts), "retry:" + state.transition(), List.of(), List.of());
            for (ricbot.domain.agent.graph.GraphRetrySchedule retry : retries) {
                NodeActivation next = retry.activation().nextAttempt();
                upsertActivation(connection, state.runId(), state.superstep(), next, "RETRY_WAIT",
                        retry.availableAt());
            }
            // commitState initially projects the graph-level RETRY_WAIT state. Persist the
            // node-specific due times before the transaction exposes its final digest.
            updateProjectionDigests(connection, state.runId(), latestGlobalSequence(connection, state.runId()));
            return null;
        });
    }

    @Override
    public Optional<Instant> nextRetryAt(String runId) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT MAX(available_at) FROM runtime_activations
                    WHERE run_id = ? AND status = 'RETRY_WAIT'
                    """)) {
                statement.setString(1, required(runId, "runId"));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getString(1) == null) return Optional.empty();
                    return Optional.of(Instant.parse(result.getString(1)));
                }
            }
        });
    }

    @Override
    public GraphExecutionState activateDueRetries(String runId, Instant now) {
        String cleanRunId = required(runId, "runId");
        Instant currentTime = now != null ? now : Instant.now();
        return mutate(connection -> {
            GraphExecutionState current = loadCheckpoint(connection, cleanRunId).orElseThrow(() ->
                    new IllegalArgumentException("run not found: " + cleanRunId));
            if (current.status() != GraphExecutionStatus.RETRY_WAIT) return current;
            Instant due;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT MAX(available_at) FROM runtime_activations
                    WHERE run_id = ? AND status = 'RETRY_WAIT'
                    """)) {
                statement.setString(1, cleanRunId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getString(1) == null) {
                        throw new IllegalStateException("retry-wait run has no persisted retry activation");
                    }
                    due = Instant.parse(result.getString(1));
                }
            }
            if (due.isAfter(currentTime)) return current;
            GraphExecutionState ready = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION,
                    current.graphId(), current.runId(), current.superstep(), current.activeNodes(),
                    current.channels(), current.waits(), current.failures(), GraphExecutionStatus.READY,
                    current.lastNodeId(), current.transition() + 1, currentTime);
            commitState(connection, ready, GRAPH_EVENT_PREFIX + GraphRuntimeEventType.NODE_RETRY_DUE.name(),
                    Map.of("availableAt", due.toString()), "retry-due:" + ready.transition(), List.of(), List.of());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE runtime_activations SET status = 'READY', available_at = ?, version = version + 1,
                        updated_at = ? WHERE run_id = ? AND status = 'RETRY_WAIT'
                    """)) {
                statement.setString(1, currentTime.toString());
                statement.setString(2, currentTime.toString());
                statement.setString(3, cleanRunId);
                statement.executeUpdate();
            }
            updateProjectionDigests(connection, cleanRunId, latestGlobalSequence(connection, cleanRunId));
            return ready;
        });
    }

    /** Atomically leases every activation in the current superstep to one live runtime instance. */
    public boolean claimReadyActivations(String runId, String instanceId, Instant now, Instant expiresAt) {
        String cleanRunId = required(runId, "runId");
        String owner = required(instanceId, "instanceId");
        Instant claimedAt = now != null ? now : Instant.now();
        Instant expiry = expiresAt != null ? expiresAt : claimedAt.plusSeconds(30);
        return mutate(connection -> {
            GraphExecutionState state = loadCheckpoint(connection, cleanRunId).orElseThrow(() ->
                    new IllegalArgumentException("run not found: " + cleanRunId));
            if (state.status() != GraphExecutionStatus.READY) return false;
            List<ActivationLeaseRow> rows = new ArrayList<>();
            for (NodeActivation activation : state.activeNodes()) {
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT status, available_at, lease_owner, lease_expires_at, version
                        FROM runtime_activations WHERE activation_id = ? AND run_id = ?
                        """)) {
                    query.setString(1, activation.activationId());
                    query.setString(2, cleanRunId);
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) return false;
                        rows.add(new ActivationLeaseRow(activation.activationId(), result.getString(1),
                                Instant.parse(result.getString(2)), result.getString(3),
                                result.getString(4) != null ? Instant.parse(result.getString(4)) : null,
                                result.getLong(5)));
                    }
                }
            }
            for (ActivationLeaseRow row : rows) {
                if (row.availableAt().isAfter(claimedAt)) return false;
                if ("READY".equals(row.status())) continue;
                if (!"CLAIMED".equals(row.status())) return false;
                if (owner.equals(row.owner())) continue;
                if (row.expiresAt() == null || row.expiresAt().isAfter(claimedAt)
                        || runtimeInstanceActive(connection, row.owner(), claimedAt)) return false;
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE runtime_activations SET status = 'CLAIMED', lease_owner = ?, lease_expires_at = ?,
                        version = version + 1, updated_at = ?
                    WHERE activation_id = ? AND version = ?
                    """)) {
                for (ActivationLeaseRow row : rows) {
                    update.setString(1, owner);
                    update.setString(2, expiry.toString());
                    update.setString(3, claimedAt.toString());
                    update.setString(4, row.activationId());
                    update.setLong(5, row.version());
                    if (update.executeUpdate() != 1) throw new IllegalStateException("activation lease conflict");
                }
            }
            appendProjectionEvent(connection, cleanRunId, "ACTIVATIONS_CLAIMED",
                    "ricbot.activation-lease.v2", MAPPER.valueToTree(Map.of("activationIds",
                            rows.stream().map(ActivationLeaseRow::activationId).toList(), "owner", owner,
                            "expiresAt", expiry.toString())),
                    "activation-claim:" + cleanRunId + ":" + state.superstep() + ":" + owner, "");
            return true;
        });
    }

    public void renewActivationClaims(String runId, String instanceId, Instant expiresAt) {
        mutate(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE runtime_activations SET lease_expires_at = ?, version = version + 1, updated_at = ?
                    WHERE run_id = ? AND status = 'CLAIMED' AND lease_owner = ?
                    """)) {
                Instant now = Instant.now();
                update.setString(1, expiresAt.toString());
                update.setString(2, now.toString());
                update.setString(3, required(runId, "runId"));
                update.setString(4, required(instanceId, "instanceId"));
                if (update.executeUpdate() > 0) {
                    appendProjectionEvent(connection, runId, "ACTIVATION_LEASE_RENEWED",
                            "ricbot.activation-lease.v2", MAPPER.valueToTree(Map.of("owner", instanceId,
                                    "expiresAt", expiresAt.toString())),
                            "activation-renew:" + runId + ":" + now.toEpochMilli(), "");
                }
            }
            return null;
        });
    }

    public void releaseActivationClaims(String runId, String instanceId) {
        mutate(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE runtime_activations SET status = 'READY', lease_owner = '', lease_expires_at = NULL,
                        version = version + 1, updated_at = ?
                    WHERE run_id = ? AND status = 'CLAIMED' AND lease_owner = ?
                    """)) {
                Instant now = Instant.now();
                update.setString(1, now.toString());
                update.setString(2, required(runId, "runId"));
                update.setString(3, required(instanceId, "instanceId"));
                if (update.executeUpdate() > 0) {
                    appendProjectionEvent(connection, runId, "ACTIVATION_LEASE_RELEASED",
                            "ricbot.activation-lease.v2", MAPPER.valueToTree(Map.of("owner", instanceId)),
                            "activation-release:" + runId + ":" + now.toEpochMilli(), "");
                }
            }
            return null;
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
        return mutate(connection -> {
            RuntimeEventEnvelope event = insertEvent(connection, required(runId, "runId"), eventType, payloadType,
                    payload, deduplicationId, sessionId, taskId, activationId, causationId, correlationId, traceParent);
            updateProjectionDigests(connection, runId, event.globalSequence());
            return event;
        });
    }

    public List<RuntimeEventEnvelope> runtimeEvents(String runId, long throughGlobalSequence) {
        return read(connection -> loadEvents(connection, required(runId, "runId"), throughGlobalSequence));
    }

    public List<RuntimeEventEnvelope> runtimeEventsAfter(long globalSequence, int limit) {
        int maximum = Math.max(1, Math.min(1_000, limit));
        return read(connection -> {
            List<RuntimeEventEnvelope> events = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT global_sequence, stream_sequence, event_id, stream_id, schema_version, event_type,
                           payload_type, run_id, session_id, task_id, activation_id, causation_id, correlation_id,
                           trace_parent, occurred_at, payload_json
                    FROM runtime_events WHERE global_sequence > ? ORDER BY global_sequence LIMIT ?
                    """)) {
                statement.setLong(1, Math.max(0, globalSequence));
                statement.setInt(2, maximum);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) events.add(readEnvelope(result));
                }
            }
            return List.copyOf(events);
        });
    }

    public long subscriberOffset(String subscriberId) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT last_global_sequence FROM subscriber_offsets WHERE subscriber_id = ?")) {
                statement.setString(1, required(subscriberId, "subscriberId"));
                try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
            }
        });
    }

    public void saveSubscriberOffset(String subscriberId, long globalSequence) {
        mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO subscriber_offsets(subscriber_id, last_global_sequence, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(subscriber_id) DO UPDATE SET
                        last_global_sequence = excluded.last_global_sequence,
                        updated_at = excluded.updated_at
                    WHERE subscriber_offsets.last_global_sequence < excluded.last_global_sequence
                    """)) {
                statement.setString(1, required(subscriberId, "subscriberId"));
                statement.setLong(2, Math.max(0, globalSequence));
                statement.setString(3, Instant.now().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Rebuilds the graph state exclusively from committed event payloads and verifies its projection digest. */
    public ReplayView replay(String runId, long throughGlobalSequence) {
        String cleanRunId = required(runId, "runId");
        return read(connection -> {
            List<RuntimeEventEnvelope> events = loadEvents(connection, cleanRunId, throughGlobalSequence);
            RuntimeEventEnvelope committed = null;
            for (RuntimeEventEnvelope event : events) {
                JsonNode state = event.payload().get("state");
                if (state != null && !state.isNull()) {
                    committed = event;
                }
            }
            RuntimeAggregate aggregate = new RuntimeReducer().reduce(cleanRunId, events);
            GraphExecutionState replayed = aggregate.graph();
            if (replayed == null || committed == null) {
                throw new IllegalStateException("run has no committed checkpoint event: " + cleanRunId);
            }
            String rebuiltDigest = RuntimeDigest.sha256(replayed);
            boolean atHead = events.isEmpty() || latestGlobalSequence(connection, cleanRunId) ==
                    events.get(events.size() - 1).globalSequence();
            Map<String, String> calculated = currentProjectionDigests(connection, cleanRunId);
            Map<String, String> stored = storedProjectionDigests(connection, cleanRunId);
            Map<String, String> semantic = semanticProjectionDigests(connection, cleanRunId, events);
            Map<String, String> reduced = aggregate.digests();
            boolean eventProjectionMatches = reduced.entrySet().stream()
                    .allMatch(entry -> entry.getValue().equals(semantic.get(entry.getKey())));
            long replaySequence = events.get(events.size() - 1).globalSequence();
            Map<String, String> historical = storedSemanticProjectionDigests(connection, cleanRunId,
                    replaySequence);
            boolean historyMatches = !historical.isEmpty() && historical.equals(reduced);
            boolean matches = historyMatches && (!atHead || (!stored.isEmpty() && stored.equals(calculated)
                    && storedProjectionSequence(connection, cleanRunId) == replaySequence
                    && rebuiltDigest.equals(calculated.getOrDefault("RUN", "")) && eventProjectionMatches));
            if (!matches) {
                throw new IllegalStateException("runtime replay projection digest mismatch for " + cleanRunId
                        + " through event " + replaySequence);
            }
            String projectedDigest = RuntimeDigest.sha256(reduced);
            return new ReplayView(cleanRunId, throughGlobalSequence, committed.globalSequence(), replayed,
                    RuntimeDigest.sha256(events), projectedDigest, matches, events);
        });
    }

    /** Forks from the last committed superstep not newer than the requested event sequence. */
    public ReplayView fork(String sourceRunId, long throughGlobalSequence, String newRunId) {
        ReplayView source = replay(sourceRunId, throughGlobalSequence);
        List<RuntimeEventEnvelope> safeEvents = source.events().stream()
                .filter(event -> event.globalSequence() <= source.committedSequence()).toList();
        RuntimeAggregate sourceAggregate = new RuntimeReducer().reduce(sourceRunId, safeEvents);
        String target = required(newRunId, "newRunId");
        if (loadCheckpoint(target).isPresent()) throw new IllegalArgumentException("run already exists: " + target);
        GraphExecutionState old = source.state();
        List<NodeActivation> activations = new ArrayList<>();
        int ordinal = 0;
        for (NodeActivation activation : old.activeNodes()) {
            activations.add(new NodeActivation(target + ":fork:" + old.superstep() + ":" + activation.nodeId()
                    + ":" + ordinal++, activation.nodeId(), activation.planOrder(), activation.attempt(),
                    activation.input()));
        }
        GraphExecutionState forked = new GraphExecutionState(GraphExecutionState.SCHEMA_VERSION, old.graphId(), target,
                old.superstep(), List.copyOf(activations), old.channels(), List.of(), old.failures(), GraphExecutionStatus.READY,
                old.lastNodeId(), old.transition() + 1, Instant.now());
        Map<String, Object> data = Map.of("sourceRunId", sourceRunId,
                "sourceEventSequence", source.committedSequence(), "requiresExplicitResume", true);
        RuntimeEventEnvelope event = mutate(connection -> {
            RuntimeEventEnvelope committed = commitState(connection, forked, "RUN_FORKED", data,
                    "fork-created", List.of(), List.of());
            List<SideEffectRecord> inherited = sourceAggregate.sideEffects().values().stream()
                    .map(node -> MAPPER.convertValue(node, SideEffectRecord.class))
                    .filter(effect -> effect.status() == ricbot.domain.agent.SideEffectStatus.SUCCEEDED)
                    .sorted(java.util.Comparator.comparing(SideEffectRecord::idempotencyKey)).toList();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO fork_effect_refs(fork_run_id, source_run_id, idempotency_key,
                                                 result_digest, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(fork_run_id, idempotency_key) DO NOTHING
                    """)) {
                for (SideEffectRecord effect : inherited) {
                    statement.setString(1, target);
                    statement.setString(2, sourceRunId);
                    statement.setString(3, effect.idempotencyKey());
                    statement.setString(4, RuntimeDigest.sha256(effect.result()));
                    statement.setString(5, Instant.now().toString());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            Map<String, TaskRecord> incomplete = new LinkedHashMap<>();
            for (JsonNode taskNode : sourceAggregate.tasks().values()) {
                TaskRecord record = MAPPER.convertValue(taskNode, TaskRecord.class);
                if (!record.status().terminal()) incomplete.put(record.spec().taskId(), record);
            }
            Map<String, String> rekeyed = new LinkedHashMap<>();
            for (TaskRecord record : incomplete.values()) {
                String localId = !record.spec().localTaskId().isBlank()
                        ? record.spec().localTaskId() : record.spec().taskId();
                rekeyed.put(record.spec().taskId(),
                        ricbot.domain.task.TeamPlanModelPlanner.deterministicTaskId(
                                target, record.spec().planRevision(), localId));
            }
            for (TaskRecord record : incomplete.values()) {
                ricbot.domain.task.TaskSpec spec = record.spec();
                String localId = !spec.localTaskId().isBlank() ? spec.localTaskId() : spec.taskId();
                String taskId = rekeyed.get(spec.taskId());
                ricbot.domain.task.TaskSpec forkSpec = new ricbot.domain.task.TaskSpec(taskId, target,
                        "plan-" + RuntimeDigest.sha256(target + ":revision:" + spec.planRevision()).substring(0, 20),
                        spec.planRevision(), localId, target + ":fork-task:" + taskId, spec.planOrder(),
                        spec.delegationDepth(), spec.role(), spec.goal(), spec.dependsOn().stream()
                        .map(rekeyed::get).filter(java.util.Objects::nonNull).toList(), spec.allowedTools(),
                        spec.workspaceMode(), spec.failurePolicy(), spec.allowFailedDependencies(),
                        spec.requiredCheckIds(), spec.acceptanceCriteria());
                TaskRecord rebuilt = TaskRecord.planned(forkSpec);
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO tasks(task_id, parent_run_id, plan_id, plan_revision, status, version,
                                          lease_owner, lease_expires_at, heartbeat_at, record_json, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    bindTask(insert, rebuilt);
                    insert.executeUpdate();
                }
                appendProjectionEvent(connection, target, "TASK_FORK_REBUILT", "ricbot.task.v2",
                        MAPPER.valueToTree(rebuilt), "fork-task:" + taskId, taskId);
            }
            return committed;
        });
        return replay(target, Long.MAX_VALUE);
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
        ObjectNode payload = (ObjectNode) graphPayload(state.superstep(), data, state);
        payload.set("acknowledgedDeliveryIds", MAPPER.valueToTree(
                acknowledgedDeliveryIds != null ? acknowledgedDeliveryIds : List.of()));
        payload.set("consumedSignalIds", MAPPER.valueToTree(
                consumedSignalIds != null ? consumedSignalIds : List.of()));
        RuntimeEventEnvelope event = insertEvent(connection, state.runId(), eventType,
                CHECKPOINT_PAYLOAD, payload, deduplicationId, sessionId(state), taskId(state),
                firstActivation(state), "", correlationId(state), "");
        faults.check(RuntimeFaultPoint.AFTER_EVENT_BEFORE_PROJECTION);
        try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO runtime_runs(run_id, graph_id, state_json, state_digest, status, superstep,
                                             transition, updated_at, last_event_sequence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(run_id) DO UPDATE SET graph_id=excluded.graph_id, state_json=excluded.state_json,
                        state_digest=excluded.state_digest, status=excluded.status, superstep=excluded.superstep,
                        transition=excluded.transition, updated_at=excluded.updated_at,
                        last_event_sequence=excluded.last_event_sequence
                    WHERE runtime_runs.transition < excluded.transition
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
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("run transition conflict: " + state.runId()
                            + " transition=" + state.transition());
                }
            }
        projectActivations(connection, state);
        acknowledgeDeliveries(connection, state.runId(), acknowledgedDeliveryIds);
        consumeSignals(connection, state.runId(), consumedSignalIds);
        updateProjectionDigests(connection, state.runId(), event.globalSequence());
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
        String effectiveCorrelation = clean(correlationId).isBlank() ? runId : clean(correlationId);
        String effectiveCausation = clean(causationId);
        if (effectiveCausation.isBlank()) {
            effectiveCausation = latestEventId(connection, runId).orElseGet(() ->
                    !effectiveCorrelation.equals(runId) ? latestEventIdUnchecked(connection, effectiveCorrelation) : "");
        }
        String effectiveTraceParent = clean(traceParent);
        if (effectiveTraceParent.isBlank() && !effectiveCorrelation.equals(runId)) {
            effectiveTraceParent = latestTraceParent(connection, effectiveCorrelation).orElse("");
        }
        if (effectiveTraceParent.isBlank()) {
            String traceId = RuntimeDigest.sha256(effectiveCorrelation).substring(0, 32);
            String spanId = RuntimeDigest.sha256(eventId).substring(0, 16);
            effectiveTraceParent = "00-" + traceId + "-" + spanId + "-01";
        }
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
            statement.setString(11, effectiveCausation);
            statement.setString(12, effectiveCorrelation);
            statement.setString(13, effectiveTraceParent);
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
                        clean(taskId), clean(activationId), effectiveCausation, effectiveCorrelation,
                        effectiveTraceParent, occurredAt, payload);
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
                    INSERT INTO tasks(task_id, parent_run_id, plan_id, plan_revision, status, version,
                                      lease_owner, lease_expires_at, heartbeat_at, record_json, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    MAPPER.valueToTree(result), "task-result:" + result.taskId() + ":" + result.attempt(),
                    result.taskId());
            return result;
        });
    }

    @Override
    public Optional<TaskResult> loadResult(String taskId) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT result_json FROM task_results WHERE task_id = ? ORDER BY attempt DESC LIMIT 1")) {
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
    public List<TaskResult> loadResultHistory(String taskId) {
        return read(connection -> {
            List<TaskResult> history = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT result_json FROM task_results WHERE task_id = ? ORDER BY attempt
                    """)) {
                statement.setString(1, required(taskId, "taskId"));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) history.add(readJson(result.getString(1), TaskResult.class));
                }
            }
            return List.copyOf(history);
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
                    "ricbot.task-delivery.v2", payload,
                    "task-settle:" + result.taskId() + ":" + result.attempt(), result.taskId());
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
            // Keep the immutable action payload so the graph can reduce a rejection deterministically.
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
            appendProjectionEvent(connection, runId, "APPROVAL_" + updated.status().name(),
                    "ricbot.approval.v2", MAPPER.valueToTree(updated),
                    "approval-decision:" + requestId + ":" + updated.status(), "");
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

    public List<SideEffectRecord> listSideEffectRecords() {
        return read(connection -> {
            List<SideEffectRecord> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT record_json FROM side_effects ORDER BY updated_at DESC, idempotency_key");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) records.add(readJson(result.getString(1), SideEffectRecord.class));
            }
            return List.copyOf(records);
        });
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

    public SideEffectRecord transitionSideEffectRecord(
            SideEffectRecord record,
            long expectedVersion,
            Set<ricbot.domain.agent.SideEffectStatus> allowedSources
    ) {
        return mutate(connection -> {
            SideEffectRecord current = loadSideEffect(connection, record.idempotencyKey()).orElseThrow(() ->
                    new IllegalArgumentException("side effect reservation not found: " + record.idempotencyKey()));
            if (current.version() != expectedVersion || record.version() != expectedVersion + 1) {
                throw new IllegalStateException("side effect version conflict: " + record.idempotencyKey());
            }
            Set<ricbot.domain.agent.SideEffectStatus> allowed = allowedSources != null
                    ? Set.copyOf(allowedSources) : Set.of();
            if (!allowed.contains(current.status())) {
                throw new IllegalStateException("side effect transition is not allowed from " + current.status());
            }
            if (!sameSideEffectIdentity(current, record)) {
                throw new IllegalStateException("side effect identity cannot change after reservation");
            }
            if (!updateSideEffect(connection, record, expectedVersion, current.status())) {
                throw new IllegalStateException("side effect version conflict: " + record.idempotencyKey());
            }
            appendProjectionEvent(connection, sideEffectRunId(record), "SIDE_EFFECT_" + record.status().name(),
                    "ricbot.side-effect.v2", MAPPER.valueToTree(record),
                    "side-effect:" + record.idempotencyKey() + ":v" + record.version(), record.taskId());
            return record;
        });
    }

    public RuntimeInstanceRecord registerRuntimeInstance(RuntimeInstanceRecord instance) {
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO runtime_instances(instance_id, host_id, pid, process_started_at,
                                                  heartbeat_at, expires_at, status, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                bindRuntimeInstance(statement, instance);
                statement.executeUpdate();
            }
            appendProjectionEvent(connection, "instance:" + instance.instanceId(), "RUNTIME_INSTANCE_REGISTERED",
                    "ricbot.runtime-instance.v2", MAPPER.valueToTree(instance),
                    "runtime-instance:" + instance.instanceId() + ":v" + instance.version(), "");
            return instance;
        });
    }

    public RuntimeInstanceRecord saveRuntimeInstance(RuntimeInstanceRecord instance, long expectedVersion,
                                                     RuntimeInstanceStatus expectedStatus) {
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE runtime_instances
                    SET heartbeat_at = ?, expires_at = ?, status = ?, version = ?
                    WHERE instance_id = ? AND version = ? AND status = ?
                    """)) {
                statement.setString(1, instance.heartbeatAt().toString());
                statement.setString(2, instance.expiresAt().toString());
                statement.setString(3, instance.status().name());
                statement.setLong(4, instance.version());
                statement.setString(5, instance.instanceId());
                statement.setLong(6, expectedVersion);
                statement.setString(7, expectedStatus.name());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("runtime instance lease conflict: " + instance.instanceId());
                }
            }
            appendProjectionEvent(connection, "instance:" + instance.instanceId(),
                    "RUNTIME_INSTANCE_" + instance.status().name(), "ricbot.runtime-instance.v2",
                    MAPPER.valueToTree(instance),
                    "runtime-instance:" + instance.instanceId() + ":v" + instance.version(), "");
            return instance;
        });
    }

    public Optional<RuntimeInstanceRecord> runtimeInstance(String instanceId) {
        return read(connection -> loadRuntimeInstance(connection, required(instanceId, "instanceId")));
    }

    public boolean runtimeInstanceActive(String instanceId, Instant now) {
        if (instanceId == null || instanceId.isBlank()) return false;
        Instant current = now != null ? now : Instant.now();
        return runtimeInstance(instanceId).map(instance -> instance.status() == RuntimeInstanceStatus.ACTIVE
                && instance.expiresAt().isAfter(current)).orElse(false);
    }

    public List<RuntimeInstanceRecord> expiredRuntimeInstances(Instant now) {
        Instant cutoff = now != null ? now : Instant.now();
        return read(connection -> {
            List<RuntimeInstanceRecord> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT instance_id, host_id, pid, process_started_at, heartbeat_at, expires_at, status, version
                    FROM runtime_instances WHERE status = 'ACTIVE' AND expires_at <= ? ORDER BY expires_at
                    """)) {
                statement.setString(1, cutoff.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) records.add(readRuntimeInstance(result));
                }
            }
            return List.copyOf(records);
        });
    }

    /** Recovers only effects whose owning instance is expired and whose own execution lease elapsed. */
    public int recoverExpiredSideEffects(String ownerInstanceId, Instant now) {
        String owner = required(ownerInstanceId, "ownerInstanceId");
        Instant cutoff = now != null ? now : Instant.now();
        return mutate(connection -> {
            RuntimeInstanceRecord instance = loadRuntimeInstance(connection, owner).orElseThrow(() ->
                    new IllegalArgumentException("runtime instance does not exist: " + owner));
            if (instance.status() == RuntimeInstanceStatus.ACTIVE || instance.expiresAt().isAfter(cutoff)) {
                throw new IllegalStateException("active runtime instance effects cannot be recovered: " + owner);
            }
            List<SideEffectRecord> executing = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT record_json FROM side_effects
                    WHERE status = 'EXECUTING' AND owner_instance_id = ?
                      AND lease_expires_at IS NOT NULL AND lease_expires_at <= ?
                    ORDER BY idempotency_key
                    """)) {
                statement.setString(1, owner);
                statement.setString(2, cutoff.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) executing.add(readJson(result.getString(1), SideEffectRecord.class));
                }
            }
            int recovered = 0;
            for (SideEffectRecord record : executing) {
                SideEffectRecord unknown = record.clearLease(ricbot.domain.agent.SideEffectStatus.UNKNOWN,
                        Map.of("reason", "owner lease expired", "ownerInstanceId", owner),
                        record.confirmationId());
                if (updateSideEffect(connection, unknown, record.version(),
                        ricbot.domain.agent.SideEffectStatus.EXECUTING)) {
                    appendProjectionEvent(connection, sideEffectRunId(record), "SIDE_EFFECT_UNKNOWN",
                            "ricbot.side-effect.v2", MAPPER.valueToTree(unknown),
                            "side-effect:" + record.idempotencyKey() + ":v" + unknown.version(), record.taskId());
                    recovered++;
                }
            }
            return recovered;
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

    private static Optional<GraphExecutionState> loadCheckpoint(Connection connection, String runId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state_json FROM runtime_runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readJson(result.getString(1), GraphExecutionState.class))
                        : Optional.empty();
            }
        }
    }

    private static void projectActivations(Connection connection, GraphExecutionState state) throws SQLException {
        String activeStatus = switch (state.status()) {
            case READY, RUNNING -> "READY";
            case RETRY_WAIT -> "RETRY_WAIT";
            case PAUSED, WAITING, RECOVERING -> "WAITING";
            case CANCELLED -> "CANCELLED";
            case FAILED -> "FAILED";
            case COMPLETED -> "COMPLETED";
        };
        Set<String> activeIds = new java.util.HashSet<>();
        for (NodeActivation activation : state.activeNodes()) {
            activeIds.add(activation.activationId());
            upsertActivation(connection, state.runId(), state.superstep(), activation, activeStatus, Instant.now());
        }
        List<String> stale = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT activation_id FROM runtime_activations WHERE run_id = ?
                  AND status IN ('READY', 'CLAIMED', 'WAITING', 'RETRY_WAIT')
                """)) {
            statement.setString(1, state.runId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String activationId = result.getString(1);
                    if (!activeIds.contains(activationId)) stale.add(activationId);
                }
            }
        }
        String staleStatus = state.status() == GraphExecutionStatus.CANCELLED ? "CANCELLED"
                : state.status() == GraphExecutionStatus.FAILED ? "FAILED" : "COMPLETED";
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE runtime_activations SET status = ?, lease_owner = '', lease_expires_at = NULL,
                    version = version + 1, updated_at = ? WHERE activation_id = ?
                """)) {
            for (String activationId : stale) {
                statement.setString(1, staleStatus);
                statement.setString(2, Instant.now().toString());
                statement.setString(3, activationId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void upsertActivation(Connection connection, String runId, long superstep,
                                         NodeActivation activation, String status, Instant availableAt)
            throws SQLException {
        Instant now = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO runtime_activations(activation_id, run_id, node_id, status, attempt, available_at,
                                                lease_owner, lease_expires_at, version, activation_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, '', NULL, 0, ?, ?)
                ON CONFLICT(activation_id) DO UPDATE SET node_id = excluded.node_id, status = excluded.status,
                    attempt = excluded.attempt, available_at = excluded.available_at,
                    lease_owner = '', lease_expires_at = NULL, activation_json = excluded.activation_json,
                    version = runtime_activations.version + 1,
                    updated_at = excluded.updated_at
                """)) {
            statement.setString(1, activation.activationId());
            statement.setString(2, runId);
            statement.setString(3, activation.nodeId());
            statement.setString(4, status);
            statement.setInt(5, activation.attempt());
            statement.setString(6, availableAt.toString());
            statement.setString(7, writeJson(Map.of("superstep", superstep, "activation", activation)));
            statement.setString(8, now.toString());
            statement.executeUpdate();
        }
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
        statement.setString(3, record.spec().planId());
        statement.setInt(4, record.spec().planRevision());
        statement.setString(5, record.status().name());
        statement.setLong(6, record.version());
        statement.setString(7, record.leaseOwner());
        statement.setString(8, record.leaseExpiresAt() != null ? record.leaseExpiresAt().toString() : null);
        statement.setString(9, record.heartbeatAt() != null ? record.heartbeatAt().toString() : null);
        statement.setString(10, writeJson(record));
        statement.setString(11, record.updatedAt().toString());
    }

    private static void updateTask(Connection connection, TaskRecord record, long expectedVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tasks SET status = ?, version = ?, lease_owner = ?, lease_expires_at = ?,
                    heartbeat_at = ?, record_json = ?, updated_at = ?
                WHERE task_id = ? AND version = ?
                """)) {
            statement.setString(1, record.status().name());
            statement.setLong(2, record.version());
            statement.setString(3, record.leaseOwner());
            statement.setString(4, record.leaseExpiresAt() != null ? record.leaseExpiresAt().toString() : null);
            statement.setString(5, record.heartbeatAt() != null ? record.heartbeatAt().toString() : null);
            statement.setString(6, writeJson(record));
            statement.setString(7, record.updatedAt().toString());
            statement.setString(8, record.spec().taskId());
            statement.setLong(9, expectedVersion);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("task version conflict: " + record.spec().taskId());
        }
    }

    private static void saveTaskResult(Connection connection, TaskResult result) throws SQLException {
        try (PreparedStatement current = connection.prepareStatement(
                "SELECT result_json FROM task_results WHERE task_id = ? AND attempt = ?")) {
            current.setString(1, result.taskId());
            current.setInt(2, result.attempt());
            try (ResultSet rows = current.executeQuery()) {
                if (rows.next()) {
                    TaskResult existing = readJson(rows.getString(1), TaskResult.class);
                    if (!existing.equals(result)) {
                        throw new IllegalStateException("task result is immutable for attempt "
                                + result.taskId() + ":" + result.attempt());
                    }
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO task_results(task_id, attempt, parent_run_id, result_json, completed_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, result.taskId());
            statement.setInt(2, result.attempt());
            statement.setString(3, result.parentRunId());
            statement.setString(4, writeJson(result));
            statement.setString(5, result.completedAt().toString());
            statement.executeUpdate();
        }
    }

    private static void saveDelivery(Connection connection, TaskDelivery delivery) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO deliveries(delivery_id, parent_run_id, task_id, attempt, plan_order, delivery_json,
                                       acknowledged, delivered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(delivery_id) DO NOTHING
                """)) {
            statement.setString(1, delivery.deliveryId());
            statement.setString(2, delivery.parentRunId());
            statement.setString(3, delivery.taskId());
            statement.setInt(4, delivery.result().attempt());
            statement.setInt(5, delivery.result().planOrder());
            statement.setString(6, writeJson(delivery));
            statement.setInt(7, delivery.acknowledged() ? 1 : 0);
            statement.setString(8, delivery.deliveredAt().toString());
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
                INSERT INTO side_effects(idempotency_key, run_id, session_key, task_id, activation_id,
                                         tool_name, arguments_digest, arguments_json, status, version,
                                         owner_instance_id, lease_expires_at, record_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, record.idempotencyKey());
            statement.setString(2, record.runId());
            statement.setString(3, record.sessionKey());
            statement.setString(4, record.taskId());
            statement.setString(5, record.activationId());
            statement.setString(6, record.toolName());
            statement.setString(7, record.argumentsDigest());
            statement.setString(8, writeJson(record.arguments()));
            statement.setString(9, record.status().name());
            statement.setLong(10, record.version());
            statement.setString(11, record.ownerInstanceId());
            statement.setString(12, record.leaseExpiresAt() != null ? record.leaseExpiresAt().toString() : null);
            statement.setString(13, writeJson(record));
            statement.setString(14, record.updatedAt().toString());
            statement.executeUpdate();
        }
    }

    private static boolean updateSideEffect(Connection connection, SideEffectRecord record,
                                            long expectedVersion,
                                            ricbot.domain.agent.SideEffectStatus expectedStatus)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE side_effects
                SET status = ?, version = ?, owner_instance_id = ?, lease_expires_at = ?,
                    record_json = ?, updated_at = ?
                WHERE idempotency_key = ? AND version = ? AND status = ?
                """)) {
            statement.setString(1, record.status().name());
            statement.setLong(2, record.version());
            statement.setString(3, record.ownerInstanceId());
            statement.setString(4, record.leaseExpiresAt() != null ? record.leaseExpiresAt().toString() : null);
            statement.setString(5, writeJson(record));
            statement.setString(6, record.updatedAt().toString());
            statement.setString(7, record.idempotencyKey());
            statement.setLong(8, expectedVersion);
            statement.setString(9, expectedStatus.name());
            return statement.executeUpdate() == 1;
        }
    }

    private static boolean sameSideEffectIdentity(SideEffectRecord current, SideEffectRecord next) {
        return current.idempotencyKey().equals(next.idempotencyKey())
                && current.runId().equals(next.runId())
                && current.sessionKey().equals(next.sessionKey())
                && current.taskId().equals(next.taskId())
                && current.activationId().equals(next.activationId())
                && current.toolName().equals(next.toolName())
                && current.argumentsDigest().equals(next.argumentsDigest())
                && current.arguments().equals(next.arguments());
    }

    private static void bindRuntimeInstance(PreparedStatement statement, RuntimeInstanceRecord instance)
            throws SQLException {
        statement.setString(1, instance.instanceId());
        statement.setString(2, instance.hostId());
        statement.setLong(3, instance.pid());
        statement.setString(4, instance.processStartedAt().toString());
        statement.setString(5, instance.heartbeatAt().toString());
        statement.setString(6, instance.expiresAt().toString());
        statement.setString(7, instance.status().name());
        statement.setLong(8, instance.version());
    }

    private static Optional<RuntimeInstanceRecord> loadRuntimeInstance(Connection connection, String instanceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT instance_id, host_id, pid, process_started_at, heartbeat_at, expires_at, status, version
                FROM runtime_instances WHERE instance_id = ?
                """)) {
            statement.setString(1, instanceId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRuntimeInstance(result)) : Optional.empty();
            }
        }
    }

    private static boolean runtimeInstanceActive(Connection connection, String instanceId, Instant now)
            throws SQLException {
        if (instanceId == null || instanceId.isBlank()) return false;
        Optional<RuntimeInstanceRecord> instance = loadRuntimeInstance(connection, instanceId);
        return instance.filter(value -> value.status() == RuntimeInstanceStatus.ACTIVE)
                .filter(value -> value.expiresAt().isAfter(now)).isPresent();
    }

    private static RuntimeInstanceRecord readRuntimeInstance(ResultSet result) throws SQLException {
        return new RuntimeInstanceRecord(result.getString("instance_id"), result.getString("host_id"),
                result.getLong("pid"), Instant.parse(result.getString("process_started_at")),
                Instant.parse(result.getString("heartbeat_at")), Instant.parse(result.getString("expires_at")),
                RuntimeInstanceStatus.valueOf(result.getString("status")), result.getLong("version"));
    }

    private record ActivationLeaseRow(String activationId, String status, Instant availableAt,
                                      String owner, Instant expiresAt, long version) { }

    private RuntimeEventEnvelope appendProjectionEvent(Connection connection, String runId, String eventType,
                                                       String payloadType, JsonNode payload, String dedupe,
                                                       String taskId) throws SQLException {
        RuntimeEventEnvelope event = insertEvent(connection, runId, eventType, payloadType, payload, dedupe,
                "", taskId, "", "", runId, "");
        updateProjectionDigests(connection, runId, event.globalSequence());
        return event;
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
        return !record.runId().isBlank() ? record.runId() : "session:" + record.sessionKey();
    }

    private void migrate() {
        int existingVersion = read(SqliteRuntimeStore::schemaVersion);
        if (existingVersion > 0 && existingVersion < 2) {
            throw new LegacyRuntimeDatabaseException(database, existingVersion);
        }
        if (existingVersion > 2) {
            throw new IllegalStateException("runtime database schema is newer than this binary: v"
                    + existingVersion);
        }
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
                            plan_id TEXT NOT NULL DEFAULT '',
                            plan_revision INTEGER NOT NULL DEFAULT 1,
                            status TEXT NOT NULL,
                            version INTEGER NOT NULL,
                            lease_owner TEXT NOT NULL DEFAULT '',
                            lease_expires_at TEXT,
                            heartbeat_at TEXT,
                            record_json TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS task_results(
                            task_id TEXT NOT NULL,
                            attempt INTEGER NOT NULL,
                            parent_run_id TEXT NOT NULL,
                            result_json TEXT NOT NULL,
                            completed_at TEXT NOT NULL,
                            PRIMARY KEY(task_id, attempt),
                            FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS deliveries(
                            delivery_id TEXT PRIMARY KEY,
                            parent_run_id TEXT NOT NULL,
                            task_id TEXT NOT NULL,
                            attempt INTEGER NOT NULL,
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
                            run_id TEXT NOT NULL DEFAULT '',
                            session_key TEXT NOT NULL,
                            task_id TEXT NOT NULL DEFAULT '',
                            activation_id TEXT NOT NULL DEFAULT '',
                            tool_name TEXT NOT NULL,
                            arguments_digest TEXT NOT NULL,
                            arguments_json TEXT NOT NULL,
                            status TEXT NOT NULL,
                            version INTEGER NOT NULL,
                            owner_instance_id TEXT NOT NULL DEFAULT '',
                            lease_expires_at TEXT,
                            record_json TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS runtime_instances(
                            instance_id TEXT PRIMARY KEY,
                            host_id TEXT NOT NULL,
                            pid INTEGER NOT NULL,
                            process_started_at TEXT NOT NULL,
                            heartbeat_at TEXT NOT NULL,
                            expires_at TEXT NOT NULL,
                            status TEXT NOT NULL,
                            version INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS runtime_activations(
                            activation_id TEXT PRIMARY KEY,
                            run_id TEXT NOT NULL,
                            node_id TEXT NOT NULL,
                            status TEXT NOT NULL,
                            attempt INTEGER NOT NULL,
                            available_at TEXT NOT NULL,
                            lease_owner TEXT NOT NULL DEFAULT '',
                            lease_expires_at TEXT,
                            version INTEGER NOT NULL,
                            activation_json TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS subscriber_offsets(
                            subscriber_id TEXT PRIMARY KEY,
                            last_global_sequence INTEGER NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS projection_digests(
                            run_id TEXT NOT NULL,
                            projection_type TEXT NOT NULL,
                            projection_digest TEXT NOT NULL,
                            through_global_sequence INTEGER NOT NULL,
                            updated_at TEXT NOT NULL,
                            PRIMARY KEY(run_id, projection_type)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS projection_digest_history(
                            run_id TEXT NOT NULL,
                            projection_type TEXT NOT NULL,
                            through_global_sequence INTEGER NOT NULL,
                            projection_digest TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            PRIMARY KEY(run_id, projection_type, through_global_sequence)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS fork_effect_refs(
                            fork_run_id TEXT NOT NULL,
                            source_run_id TEXT NOT NULL,
                            idempotency_key TEXT NOT NULL,
                            result_digest TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            PRIMARY KEY(fork_run_id, idempotency_key),
                            FOREIGN KEY(idempotency_key) REFERENCES side_effects(idempotency_key)
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
                statement.execute("CREATE INDEX IF NOT EXISTS runtime_events_run_idx ON runtime_events(run_id, global_sequence)");
                statement.execute("CREATE INDEX IF NOT EXISTS tasks_parent_idx ON tasks(parent_run_id, task_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS deliveries_pending_idx ON deliveries(parent_run_id, acknowledged, plan_order)");
                statement.execute("CREATE INDEX IF NOT EXISTS activations_due_idx ON runtime_activations(status, available_at)");
                statement.execute("CREATE INDEX IF NOT EXISTS side_effect_lease_idx ON side_effects(status, lease_expires_at)");
                statement.execute("CREATE INDEX IF NOT EXISTS runtime_instance_lease_idx ON runtime_instances(status, expires_at)");
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS runtime_events_dedupe_idx
                        ON runtime_events(stream_id, deduplication_id) WHERE deduplication_id <> ''
                        """);
                try (PreparedStatement migration = connection.prepareStatement("""
                        INSERT INTO schema_migrations(version, applied_at, digest) VALUES (2, ?, ?)
                        ON CONFLICT(version) DO NOTHING
                        """)) {
                    migration.setString(1, Instant.now().toString());
                    migration.setString(2, RuntimeDigest.sha256("runtime-schema-v2"));
                    migration.executeUpdate();
                }
            }
            return null;
        });
    }

    private static int schemaVersion(Connection connection) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, "schema_migrations", null)) {
            if (!tables.next()) return 0;
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return result.next() ? result.getInt(1) : 0;
        }
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

    private static Optional<String> latestEventId(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_id FROM runtime_events WHERE run_id = ? ORDER BY global_sequence DESC LIMIT 1")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        }
    }

    private static String latestEventIdUnchecked(Connection connection, String runId) {
        try { return latestEventId(connection, runId).orElse(""); }
        catch (SQLException failure) { throw new IllegalStateException(failure); }
    }

    private static Optional<String> latestTraceParent(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT trace_parent FROM runtime_events WHERE run_id = ? ORDER BY global_sequence DESC LIMIT 1")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.ofNullable(result.getString(1)).filter(value -> !value.isBlank())
                        : Optional.empty();
            }
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

    private static void updateProjectionDigests(Connection connection, String runId, long throughSequence)
            throws SQLException {
        Map<String, String> digests = currentProjectionDigests(connection, runId);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO projection_digests(run_id, projection_type, projection_digest,
                                               through_global_sequence, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(run_id, projection_type) DO UPDATE SET
                    projection_digest = excluded.projection_digest,
                    through_global_sequence = excluded.through_global_sequence,
                    updated_at = excluded.updated_at
                """)) {
            for (Map.Entry<String, String> entry : digests.entrySet()) {
                statement.setString(1, runId);
                statement.setString(2, entry.getKey());
                statement.setString(3, entry.getValue());
                statement.setLong(4, throughSequence);
                statement.setString(5, Instant.now().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        List<RuntimeEventEnvelope> events = loadEventsStatic(connection, runId, throughSequence);
        Map<String, String> semantic = new RuntimeReducer().reduce(runId, events).digests();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO projection_digest_history(run_id, projection_type, through_global_sequence,
                                                      projection_digest, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(run_id, projection_type, through_global_sequence) DO UPDATE SET
                    projection_digest = excluded.projection_digest
                """)) {
            for (Map.Entry<String, String> entry : semantic.entrySet()) {
                statement.setString(1, runId);
                statement.setString(2, entry.getKey());
                statement.setLong(3, throughSequence);
                statement.setString(4, entry.getValue());
                statement.setString(5, Instant.now().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static List<RuntimeEventEnvelope> loadEventsStatic(Connection connection, String runId, long through)
            throws SQLException {
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
                while (result.next()) {
                    int version = result.getInt("schema_version");
                    if (version != RuntimeEventEnvelope.CURRENT_SCHEMA_VERSION) {
                        throw new ricbot.domain.runtime.UnknownRuntimeEventVersionException(version);
                    }
                    events.add(new RuntimeEventEnvelope(version, result.getLong("global_sequence"),
                            result.getLong("stream_sequence"), result.getString("event_id"),
                            result.getString("stream_id"), result.getString("event_type"),
                            result.getString("payload_type"), result.getString("run_id"),
                            clean(result.getString("session_id")), clean(result.getString("task_id")),
                            clean(result.getString("activation_id")), clean(result.getString("causation_id")),
                            clean(result.getString("correlation_id")), clean(result.getString("trace_parent")),
                            Instant.parse(result.getString("occurred_at")), readTree(result.getString("payload_json"))));
                }
            }
        }
        return List.copyOf(events);
    }

    private static Map<String, String> storedProjectionDigests(Connection connection, String runId)
            throws SQLException {
        Map<String, String> digests = new java.util.TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT projection_type, projection_digest FROM projection_digests
                WHERE run_id = ? ORDER BY projection_type
                """)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) digests.put(result.getString(1), result.getString(2));
            }
        }
        return Map.copyOf(digests);
    }

    private static long storedProjectionSequence(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT CASE WHEN MIN(through_global_sequence) = MAX(through_global_sequence)
                            THEN MAX(through_global_sequence) ELSE -1 END
                FROM projection_digests WHERE run_id = ?
                """)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : -1;
            }
        }
    }

    private static Map<String, String> storedSemanticProjectionDigests(Connection connection, String runId,
                                                                        long throughSequence)
            throws SQLException {
        Map<String, String> digests = new java.util.TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT projection_type, projection_digest FROM projection_digest_history
                WHERE run_id = ? AND through_global_sequence = ? ORDER BY projection_type
                """)) {
            statement.setString(1, runId);
            statement.setLong(2, throughSequence);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) digests.put(result.getString(1), result.getString(2));
            }
        }
        return Map.copyOf(digests);
    }

    private static Map<String, String> currentProjectionDigests(Connection connection, String runId)
            throws SQLException {
        Map<String, String> digests = new java.util.TreeMap<>();
        Optional<GraphExecutionState> run = loadCheckpoint(connection, runId);
        digests.put("RUN", run.map(RuntimeDigest::sha256).orElseGet(() -> RuntimeDigest.sha256(List.of())));
        digests.put("ACTIVATION", digestRows(connection, """
                SELECT activation_id, status, attempt, available_at, lease_owner,
                       COALESCE(lease_expires_at, ''), activation_json
                FROM runtime_activations WHERE run_id = ? ORDER BY activation_id
                """, runId, 7));
        digests.put("TASK", digestRows(connection, """
                SELECT task_id, status, version, record_json FROM tasks
                WHERE parent_run_id = ? ORDER BY task_id
                """, runId, 4));
        digests.put("TASK_RESULT", digestRows(connection, """
                SELECT task_id, attempt, result_json FROM task_results
                WHERE parent_run_id = ? ORDER BY task_id, attempt
                """, runId, 3));
        digests.put("DELIVERY", digestRows(connection, """
                SELECT delivery_id, task_id, attempt, acknowledged, delivery_json FROM deliveries
                WHERE parent_run_id = ? ORDER BY delivery_id
                """, runId, 5));
        digests.put("APPROVAL", digestRows(connection, """
                SELECT request_id, status, request_json FROM approvals
                WHERE run_id = ? ORDER BY request_id
                """, runId, 3));
        digests.put("SIDE_EFFECT", digestRows(connection, """
                SELECT idempotency_key, status, version, owner_instance_id,
                       COALESCE(lease_expires_at, ''), record_json FROM side_effects
                WHERE run_id = ? ORDER BY idempotency_key
                """, runId, 6));
        digests.put("VERIFIER", digestRows(connection, """
                SELECT report_id, status, diff_digest, report_json FROM verifier_reports
                WHERE run_id = ? ORDER BY report_id
                """, runId, 4));
        digests.put("SIGNAL", digestRows(connection, """
                SELECT signal_id, signal_type, consumed, payload_json FROM runtime_signals
                WHERE run_id = ? ORDER BY signal_id
                """, runId, 4));
        return Map.copyOf(digests);
    }

    /** Canonical JSON projection used to prove that event reduction equals every durable domain projection. */
    private static Map<String, String> semanticProjectionDigests(Connection connection, String runId,
                                                                  List<RuntimeEventEnvelope> events)
            throws SQLException {
        Map<String, String> digests = new java.util.TreeMap<>();
        GraphExecutionState graph = loadCheckpoint(connection, runId).orElse(null);
        digests.put("RUN", graph != null ? RuntimeDigest.sha256(graph) : RuntimeDigest.sha256(List.of()));
        Map<String, JsonNode> activations = new java.util.TreeMap<>();
        if (graph != null) graph.activeNodes().forEach(activation ->
                activations.put(activation.activationId(), MAPPER.valueToTree(activation)));
        digests.put("ACTIVATION", RuntimeDigest.sha256(activations));
        digests.put("TASK", RuntimeDigest.sha256(jsonProjection(connection,
                "SELECT task_id, record_json FROM tasks WHERE parent_run_id = ? ORDER BY task_id", runId)));
        digests.put("TASK_RESULT", RuntimeDigest.sha256(jsonProjection(connection,
                "SELECT task_id || ':' || attempt, result_json FROM task_results "
                        + "WHERE parent_run_id = ? ORDER BY task_id, attempt", runId)));
        Map<String, JsonNode> deliveries = new java.util.TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT delivery_id, acknowledged, delivery_json FROM deliveries
                WHERE parent_run_id = ? ORDER BY delivery_id
                """)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    JsonNode node = readTree(result.getString(3));
                    if (node instanceof ObjectNode object) object.put("acknowledged", result.getInt(2) != 0);
                    deliveries.put(result.getString(1), node);
                }
            }
        }
        digests.put("DELIVERY", RuntimeDigest.sha256(deliveries));
        digests.put("APPROVAL", RuntimeDigest.sha256(jsonProjection(connection,
                "SELECT request_id, request_json FROM approvals WHERE run_id = ? ORDER BY request_id", runId)));
        digests.put("SIDE_EFFECT", RuntimeDigest.sha256(jsonProjection(connection,
                "SELECT idempotency_key, record_json FROM side_effects WHERE run_id = ? ORDER BY idempotency_key", runId)));
        digests.put("VERIFIER", RuntimeDigest.sha256(jsonProjection(connection,
                "SELECT report_id, report_json FROM verifier_reports WHERE run_id = ? ORDER BY report_id", runId)));
        RuntimeAggregate reduced = new RuntimeReducer().reduce(runId, events);
        digests.put("PATCH", RuntimeDigest.sha256(reduced.patches()));
        return Map.copyOf(digests);
    }

    private static Map<String, JsonNode> jsonProjection(Connection connection, String sql, String runId)
            throws SQLException {
        Map<String, JsonNode> values = new java.util.TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.put(result.getString(1), readTree(result.getString(2)));
            }
        }
        return Map.copyOf(values);
    }

    private static String digestRows(Connection connection, String sql, String runId, int columnCount)
            throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    List<Object> row = new ArrayList<>(columnCount);
                    for (int index = 1; index <= columnCount; index++) row.add(result.getObject(index));
                    rows.add(List.copyOf(row));
                }
            }
        }
        return RuntimeDigest.sha256(rows);
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

    private static String taskId(GraphExecutionState state) {
        Object config = state.channels().get("runConfig");
        if (config instanceof Map<?, ?> values && values.get("taskId") != null) {
            return clean(String.valueOf(values.get("taskId")));
        }
        return "";
    }

    private static String sessionId(GraphExecutionState state) {
        return clean(String.valueOf(state.channels().getOrDefault("sessionId", "")));
    }

    private static String correlationId(GraphExecutionState state) {
        Object config = state.channels().get("runConfig");
        if (config instanceof Map<?, ?> values && values.get("parentRunId") != null) {
            String parent = clean(String.valueOf(values.get("parentRunId")));
            if (!parent.isBlank()) return parent;
        }
        return state.runId();
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
