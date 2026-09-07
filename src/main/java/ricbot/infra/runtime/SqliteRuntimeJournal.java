package ricbot.infra.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.runtime.ChannelWrite;
import ricbot.domain.runtime.ExternalEvent;
import ricbot.domain.runtime.RuntimeCommand;
import ricbot.domain.runtime.RuntimeEvent;
import ricbot.domain.runtime.RuntimePhase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Append-only commit, channel-write, inbox and event journal operations. */
final class SqliteRuntimeJournal {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<ChannelWrite>> WRITES = new TypeReference<>() { };
    private static final TypeReference<List<RuntimeCommand>> COMMANDS = new TypeReference<>() { };

    void insertCommit(Connection connection, long sequence, String runId, String kind,
                      long superstep, RuntimePhase phase, List<ChannelWrite> writes,
                      List<RuntimeCommand> commands, String externalEventId, Instant committedAt,
                      long transactionSequence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO commits(run_id,commit_sequence,kind,superstep,phase,writes_json,commands_json,
                  external_event_id,transaction_sequence,committed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, runId); statement.setLong(2, sequence); statement.setString(3, kind);
            statement.setLong(4, superstep); statement.setString(5, phase.name());
            statement.setString(6, typedJson(writes, WRITES));
            statement.setString(7, typedJson(commands, COMMANDS));
            statement.setString(8, externalEventId != null ? externalEventId : "");
            statement.setLong(9, transactionSequence); statement.setString(10, committedAt.toString());
            statement.executeUpdate();
        }
        for (int index = 0; index < writes.size(); index++) {
            try (PreparedStatement write = connection.prepareStatement("""
                    INSERT INTO channel_writes(run_id,commit_sequence,write_index,superstep,phase,write_json)
                    VALUES(?,?,?,?,?,?)
                    """)) {
                write.setString(1, runId); write.setLong(2, sequence); write.setInt(3, index);
                write.setLong(4, superstep); write.setString(5, phase.name());
                write.setString(6, json(writes.get(index))); write.executeUpdate();
            }
        }
    }

    ExternalEvent storedEvent(Connection connection, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_json FROM inbox WHERE event_id=?")) {
            statement.setString(1, eventId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("inbox event missing: " + eventId);
                return read(result.getString(1), ExternalEvent.class);
            }
        }
    }

    List<String> pendingInboxIds(Connection connection, String runId) throws SQLException {
        List<String> eventIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id FROM inbox WHERE run_id=? AND consumed_commit IS NULL
                ORDER BY occurred_at, event_id
                """)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) eventIds.add(result.getString(1));
            }
        }
        return List.copyOf(eventIds);
    }

    List<RuntimeEvent> events(Connection connection, String runId, boolean all) throws SQLException {
        List<RuntimeEvent> events = new ArrayList<>();
        String sql = all
                ? "SELECT sequence,event_id,run_id,commit_sequence,event_type,occurred_at,payload_json FROM runtime_events ORDER BY sequence"
                : "SELECT sequence,event_id,run_id,commit_sequence,event_type,occurred_at,payload_json FROM runtime_events WHERE run_id=? ORDER BY sequence";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!all) statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) events.add(new RuntimeEvent(result.getLong(1), result.getString(2),
                        result.getString(3), result.getLong(4), result.getString(5),
                        Instant.parse(result.getString(6)), read(result.getString(7),
                        new TypeReference<Map<String, Object>>() { })));
            }
        }
        return List.copyOf(events);
    }

    RuntimeEvent appendEvent(Connection connection, String runId, long commitSequence, String type,
                             Instant occurredAt, Map<String, Object> payload,
                             long transactionSequence) throws SQLException {
        String eventId = UUID.randomUUID().toString();
        long sequence;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO runtime_events(event_id,run_id,commit_sequence,event_type,occurred_at,payload_json,transaction_sequence)
                VALUES(?,?,?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, eventId); statement.setString(2, runId);
            statement.setLong(3, commitSequence); statement.setString(4, type);
            statement.setString(5, occurredAt.toString()); statement.setString(6, json(payload));
            statement.setLong(7, transactionSequence); statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new IllegalStateException("runtime event sequence was not generated");
                sequence = keys.getLong(1);
            }
        }
        return new RuntimeEvent(sequence, eventId, runId, commitSequence, type, occurredAt, payload);
    }

    private static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("cannot encode runtime journal", failure); }
    }
    private static <T> String typedJson(T value, TypeReference<T> type) {
        try { return MAPPER.writerFor(type).writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("cannot encode runtime journal", failure); }
    }
    private static <T> T read(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (Exception failure) { throw new IllegalStateException("cannot decode runtime journal", failure); }
    }
    private static <T> T read(String json, TypeReference<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (Exception failure) { throw new IllegalStateException("cannot decode runtime journal", failure); }
    }
}
