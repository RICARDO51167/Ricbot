package ricbot.infra.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.change.GitChangeSet;
import ricbot.domain.runtime.dto.RuntimeInstanceRecord;
import ricbot.domain.runtime.enump.RuntimeInstanceStatus;
import ricbot.domain.runtime.TranscriptPort;
import ricbot.domain.runtime.dto.RuntimeDigest;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalRequestStore;
import ricbot.domain.session.Session;
import ricbot.domain.trace.TraceEvent;
import ricbot.domain.verification.VerificationReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Peripheral application persistence for sessions, traces and UI projections.
 * Durable Run state is exclusively owned by {@link SqliteDurableRuntimeStore}.
 */
public final class SqliteRuntimeStore implements AutoCloseable, TranscriptPort {
    public static final String DATABASE_RELATIVE_PATH = ".ricbot/application.db";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final Path database;
    private final String jdbcUrl;

    public SqliteRuntimeStore(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        database = workspace.toAbsolutePath().normalize().resolve(DATABASE_RELATIVE_PATH);
        jdbcUrl = "jdbc:sqlite:" + database;
        try { Files.createDirectories(database.getParent()); }
        catch (IOException failure) { throw new IllegalStateException("cannot create application store", failure); }
        initialize();
    }

    public Path database() { return database; }

    @Override public String initialize(String runId, List<Map<String, Object>> messages) {
        String id = required(runId, "runId");
        int index = 0;
        for (Map<String, Object> message : messages != null ? messages : List.<Map<String, Object>>of()) {
            append(id, id + ":initial:" + index++, message);
        }
        return reference(id);
    }

    @Override public void append(String runId, String entryId, Map<String, Object> message) {
        String id = required(runId, "runId");
        String entry = required(entryId, "entryId");
        Map<String, Object> value = Map.copyOf(message != null ? message : Map.of());
        String encoded = json(value);
        String digest = RuntimeDigest.sha256(value);
        mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO transcript_entries(run_id,entry_id,entry_digest,entry_json,created_at)
                    VALUES(?,?,?,?,?) ON CONFLICT(run_id,entry_id) DO NOTHING
                    """)) {
                statement.setString(1, id); statement.setString(2, entry); statement.setString(3, digest);
                statement.setString(4, encoded); statement.setString(5, Instant.now().toString());
                if (statement.executeUpdate() == 1) return null;
            }
            try (PreparedStatement existing = connection.prepareStatement(
                    "SELECT entry_digest FROM transcript_entries WHERE run_id=? AND entry_id=?")) {
                existing.setString(1, id); existing.setString(2, entry);
                try (ResultSet result = existing.executeQuery()) {
                    if (!result.next() || !digest.equals(result.getString(1))) {
                        throw new IllegalStateException("transcript entry identity conflict: " + entry);
                    }
                }
            }
            return null;
        });
    }

    @Override public List<Map<String, Object>> read(String runId) {
        return read(connection -> {
            List<Map<String, Object>> messages = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT entry_json FROM transcript_entries WHERE run_id=? ORDER BY sequence")) {
                statement.setString(1, required(runId, "runId"));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) messages.add(readJson(result.getString(1), MAP_TYPE));
                }
            }
            return List.copyOf(messages);
        });
    }

    @Override public long size(String runId) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM transcript_entries WHERE run_id=?")) {
                statement.setString(1, required(runId, "runId"));
                try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
            }
        });
    }

    @Override public String reference(String runId) {
        return "sqlite:.ricbot/application.db#transcripts/" + required(runId, "runId");
    }

    @Override public String copy(String sourceRunId, String targetRunId, long throughCursor) {
        String source = required(sourceRunId, "sourceRunId");
        String target = required(targetRunId, "targetRunId");
        long limit = Math.max(0L, throughCursor);
        mutate(connection -> {
            List<Map<String, Object>> prefix = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT entry_json FROM transcript_entries WHERE run_id=? ORDER BY sequence LIMIT ?")) {
                statement.setString(1, source); statement.setLong(2, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) prefix.add(readJson(result.getString(1), MAP_TYPE));
                }
            }
            for (int index = 0; index < prefix.size(); index++) {
                Map<String, Object> message = prefix.get(index);
                String entryId = target + ":fork:" + index;
                String encoded = json(message);
                String digest = RuntimeDigest.sha256(message);
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO transcript_entries(run_id,entry_id,entry_digest,entry_json,created_at)
                        VALUES(?,?,?,?,?) ON CONFLICT(run_id,entry_id) DO NOTHING
                        """)) {
                    insert.setString(1, target); insert.setString(2, entryId); insert.setString(3, digest);
                    insert.setString(4, encoded); insert.setString(5, Instant.now().toString());
                    if (insert.executeUpdate() == 1) continue;
                }
                try (PreparedStatement existing = connection.prepareStatement(
                        "SELECT entry_digest FROM transcript_entries WHERE run_id=? AND entry_id=?")) {
                    existing.setString(1, target); existing.setString(2, entryId);
                    try (ResultSet result = existing.executeQuery()) {
                        if (!result.next() || !digest.equals(result.getString(1))) {
                            throw new IllegalStateException("transcript prefix identity conflict: " + entryId);
                        }
                    }
                }
            }
            return null;
        });
        return reference(target);
    }

    @Override public void cleanup(String runId) {
        mutate(connection -> {
            executeDelete(connection, "DELETE FROM transcript_entries WHERE run_id=?", required(runId, "runId"));
            return null;
        });
    }

    public Session saveSession(Session session) {
        Objects.requireNonNull(session, "session");
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sessions(session_key,session_json,updated_at) VALUES(?,?,?)
                    ON CONFLICT(session_key) DO UPDATE SET session_json=excluded.session_json,updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, session.getKey()); statement.setString(2, json(session));
                statement.setString(3, session.getUpdatedAt().toString()); statement.executeUpdate();
            }
            return session;
        });
    }

    public Optional<Session> loadSession(String key) {
        return read(connection -> loadJson(connection, "SELECT session_json FROM sessions WHERE session_key=?",
                required(key, "sessionKey"), Session.class));
    }
    public List<Session> listSessions() { return listJson("SELECT session_json FROM sessions ORDER BY updated_at DESC", Session.class); }
    public void deleteSession(String key) {
        mutate(connection -> { executeDelete(connection, "DELETE FROM sessions WHERE session_key=?", required(key, "sessionKey")); return null; });
    }

    public VerificationReport saveVerificationReport(String runId, VerificationReport report) {
        Objects.requireNonNull(report, "report");
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO verifier_reports(report_id,run_id,status,diff_digest,report_json,created_at)
                    VALUES(?,?,?,?,?,?) ON CONFLICT(report_id) DO UPDATE SET status=excluded.status,
                      diff_digest=excluded.diff_digest,report_json=excluded.report_json
                    """)) {
                statement.setString(1, report.reportId()); statement.setString(2, clean(runId));
                statement.setString(3, report.status().name()); statement.setString(4, report.diffDigest());
                statement.setString(5, json(report)); statement.setString(6, report.createdAt().toString()); statement.executeUpdate();
            }
            return report;
        });
    }
    public List<VerificationReport> verificationReports() {
        return listJson("SELECT report_json FROM verifier_reports ORDER BY created_at DESC", VerificationReport.class);
    }

    public TraceEvent saveTraceEvent(TraceEvent event) {
        Objects.requireNonNull(event, "event");
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO trace_events(event_id,trace_id,event_json,created_at) VALUES(?,?,?,?)
                    ON CONFLICT(event_id) DO UPDATE SET event_json=excluded.event_json
                    """)) {
                statement.setString(1, event.eventId()); statement.setString(2, event.traceId());
                statement.setString(3, json(event)); statement.setString(4, event.createdAt()); statement.executeUpdate();
            }
            return event;
        });
    }
    public List<TraceEvent> traceEvents(String traceId) {
        return listJson("SELECT event_json FROM trace_events WHERE trace_id=? ORDER BY created_at,event_id",
                required(traceId, "traceId"), TraceEvent.class);
    }
    public List<String> traceIds() {
        return read(connection -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT trace_id FROM trace_events GROUP BY trace_id ORDER BY MAX(created_at) DESC");
                 ResultSet result = statement.executeQuery()) { while (result.next()) ids.add(result.getString(1)); }
            return List.copyOf(ids);
        });
    }

    public GitChangeSet saveChangeSet(GitChangeSet changeSet) {
        Objects.requireNonNull(changeSet, "changeSet");
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO change_sets(change_set_id,status,change_set_json,updated_at) VALUES(?,?,?,?)
                    ON CONFLICT(change_set_id) DO UPDATE SET status=excluded.status,
                      change_set_json=excluded.change_set_json,updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, changeSet.id()); statement.setString(2, changeSet.status().name());
                statement.setString(3, json(changeSet)); statement.setString(4, changeSet.updatedAt()); statement.executeUpdate();
            }
            return changeSet;
        });
    }
    public Optional<GitChangeSet> changeSet(String id) {
        return read(connection -> loadJson(connection, "SELECT change_set_json FROM change_sets WHERE change_set_id=?",
                required(id, "changeSetId"), GitChangeSet.class));
    }
    public List<GitChangeSet> changeSets() {
        return listJson("SELECT change_set_json FROM change_sets ORDER BY updated_at DESC", GitChangeSet.class);
    }

    public ApprovalRequestStore approvalStore() {
        return new ApprovalRequestStore() {
            @Override public ApprovalRequest save(ApprovalRequest request) { return saveApproval(request); }
            @Override public Optional<ApprovalRequest> load(String requestId) { return loadApproval(requestId); }
            @Override public List<ApprovalRequest> list() { return listApprovals(); }
        };
    }
    public ApprovalRequest saveApproval(ApprovalRequest request) {
        Objects.requireNonNull(request, "request");
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO approvals(request_id,status,request_json,updated_at) VALUES(?,?,?,?)
                    ON CONFLICT(request_id) DO UPDATE SET status=excluded.status,request_json=excluded.request_json,updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, request.requestId()); statement.setString(2, request.status().name());
                statement.setString(3, json(request)); statement.setString(4, Instant.now().toString()); statement.executeUpdate();
            }
            return request;
        });
    }
    public Optional<ApprovalRequest> loadApproval(String id) {
        return read(connection -> loadJson(connection, "SELECT request_json FROM approvals WHERE request_id=?",
                required(id, "requestId"), ApprovalRequest.class));
    }
    public List<ApprovalRequest> listApprovals() {
        return listJson("SELECT request_json FROM approvals ORDER BY updated_at DESC", ApprovalRequest.class);
    }

    public RuntimeInstanceRecord registerRuntimeInstance(RuntimeInstanceRecord instance) {
        return mutate(connection -> { insertInstance(connection, instance); return instance; });
    }
    public RuntimeInstanceRecord saveRuntimeInstance(RuntimeInstanceRecord instance, long expectedVersion,
                                                     RuntimeInstanceStatus expectedStatus) {
        return mutate(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE runtime_instances SET heartbeat_at=?,expires_at=?,status=?,version=?
                    WHERE instance_id=? AND version=? AND status=?
                    """)) {
                statement.setString(1, instance.heartbeatAt().toString()); statement.setString(2, instance.expiresAt().toString());
                statement.setString(3, instance.status().name()); statement.setLong(4, instance.version());
                statement.setString(5, instance.instanceId()); statement.setLong(6, expectedVersion);
                statement.setString(7, expectedStatus.name());
                if (statement.executeUpdate() != 1) throw new IllegalStateException("runtime instance lease conflict: " + instance.instanceId());
            }
            return instance;
        });
    }
    public Optional<RuntimeInstanceRecord> runtimeInstance(String id) {
        return read(connection -> loadInstance(connection, required(id, "instanceId")));
    }
    public boolean runtimeInstanceActive(String id, Instant now) {
        return id != null && !id.isBlank() && runtimeInstance(id).map(value -> value.status() == RuntimeInstanceStatus.ACTIVE
                && value.expiresAt().isAfter(now != null ? now : Instant.now())).orElse(false);
    }
    public List<RuntimeInstanceRecord> expiredRuntimeInstances(Instant now) {
        Instant cutoff = now != null ? now : Instant.now();
        return read(connection -> {
            List<RuntimeInstanceRecord> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT instance_id,host_id,pid,process_started_at,heartbeat_at,expires_at,status,version
                    FROM runtime_instances WHERE status='ACTIVE' AND expires_at<=? ORDER BY expires_at
                    """)) {
                statement.setString(1, cutoff.toString());
                try (ResultSet result = statement.executeQuery()) { while (result.next()) values.add(instance(result)); }
            }
            return List.copyOf(values);
        });
    }
    @Override public void close() { }

    private void initialize() {
        mutate(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS application_schema(version INTEGER PRIMARY KEY,created_at TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS sessions(session_key TEXT PRIMARY KEY,session_json TEXT NOT NULL,updated_at TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS verifier_reports(report_id TEXT PRIMARY KEY,run_id TEXT NOT NULL,status TEXT NOT NULL,diff_digest TEXT NOT NULL,report_json TEXT NOT NULL,created_at TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS trace_events(event_id TEXT PRIMARY KEY,trace_id TEXT NOT NULL,event_json TEXT NOT NULL,created_at TEXT NOT NULL)");
                statement.execute("CREATE INDEX IF NOT EXISTS trace_events_trace_idx ON trace_events(trace_id,created_at)");
                statement.execute("CREATE TABLE IF NOT EXISTS change_sets(change_set_id TEXT PRIMARY KEY,status TEXT NOT NULL,change_set_json TEXT NOT NULL,updated_at TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS approvals(request_id TEXT PRIMARY KEY,status TEXT NOT NULL,request_json TEXT NOT NULL,updated_at TEXT NOT NULL)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS transcript_entries(
                          sequence INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL,entry_id TEXT NOT NULL,
                          entry_digest TEXT NOT NULL,entry_json TEXT NOT NULL,created_at TEXT NOT NULL,
                          UNIQUE(run_id,entry_id))
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS transcript_entries_run_idx ON transcript_entries(run_id,sequence)");
                statement.execute("DROP TABLE IF EXISTS side_effects");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS runtime_instances(
                          instance_id TEXT PRIMARY KEY,host_id TEXT NOT NULL,pid INTEGER NOT NULL,
                          process_started_at TEXT NOT NULL,heartbeat_at TEXT NOT NULL,expires_at TEXT NOT NULL,
                          status TEXT NOT NULL,version INTEGER NOT NULL)
                        """);
                statement.execute("INSERT INTO application_schema(version,created_at) VALUES(2,'" + Instant.now() + "') ON CONFLICT(version) DO NOTHING");
            }
            return null;
        });
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA synchronous=FULL"); statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }
    private <T> T read(SqlWork<T> work) {
        try (Connection connection = connection()) { return work.apply(connection); }
        catch (SQLException failure) { throw new IllegalStateException("application database read failed", failure); }
    }
    private <T> T mutate(SqlWork<T> work) {
        try (Connection connection = connection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try { T value = work.apply(connection); transaction.execute("COMMIT"); return value; }
            catch (Exception failure) {
                try { transaction.execute("ROLLBACK"); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                if (failure instanceof SQLException sql) throw sql;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new SQLException(failure);
            }
        } catch (SQLException failure) { throw new IllegalStateException("application database mutation failed", failure); }
    }

    private <T> List<T> listJson(String sql, Class<T> type) {
        return read(connection -> {
            List<T> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(readJson(result.getString(1), type));
            }
            return List.copyOf(values);
        });
    }
    private <T> List<T> listJson(String sql, String parameter, Class<T> type) {
        return read(connection -> {
            List<T> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, parameter);
                try (ResultSet result = statement.executeQuery()) { while (result.next()) values.add(readJson(result.getString(1), type)); }
            }
            return List.copyOf(values);
        });
    }
    private static <T> Optional<T> loadJson(Connection connection, String sql, String id, Class<T> type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readJson(result.getString(1), type)) : Optional.empty();
            }
        }
    }
    private static void executeDelete(Connection connection, String sql, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { statement.setString(1, id); statement.executeUpdate(); }
    }

    private static void insertInstance(Connection connection, RuntimeInstanceRecord instance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO runtime_instances(instance_id,host_id,pid,process_started_at,heartbeat_at,expires_at,status,version)
                VALUES(?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, instance.instanceId()); statement.setString(2, instance.hostId());
            statement.setLong(3, instance.pid()); statement.setString(4, instance.processStartedAt().toString());
            statement.setString(5, instance.heartbeatAt().toString()); statement.setString(6, instance.expiresAt().toString());
            statement.setString(7, instance.status().name()); statement.setLong(8, instance.version()); statement.executeUpdate();
        }
    }
    private static Optional<RuntimeInstanceRecord> loadInstance(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT instance_id,host_id,pid,process_started_at,heartbeat_at,expires_at,status,version
                FROM runtime_instances WHERE instance_id=?
                """)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? Optional.of(instance(result)) : Optional.empty(); }
        }
    }
    private static RuntimeInstanceRecord instance(ResultSet result) throws SQLException {
        return new RuntimeInstanceRecord(result.getString(1), result.getString(2), result.getLong(3),
                Instant.parse(result.getString(4)), Instant.parse(result.getString(5)), Instant.parse(result.getString(6)),
                RuntimeInstanceStatus.valueOf(result.getString(7)), result.getLong(8));
    }

    private static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("cannot encode application value", failure); }
    }
    private static <T> T readJson(String value, Class<T> type) {
        try { return MAPPER.readValue(value, type); }
        catch (Exception failure) { throw new IllegalStateException("cannot decode application value", failure); }
    }
    private static <T> T readJson(String value, TypeReference<T> type) {
        try { return MAPPER.readValue(value, type); }
        catch (Exception failure) { throw new IllegalStateException("cannot decode application value", failure); }
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
    private static String required(String value, String field) {
        String clean = clean(value); if (clean.isBlank()) throw new IllegalArgumentException(field + " is required"); return clean;
    }
    @FunctionalInterface private interface SqlWork<T> { T apply(Connection connection) throws SQLException; }
}
