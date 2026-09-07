package ricbot.infra.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.runtime.ChannelWrite;
import ricbot.domain.runtime.CommittedWrite;
import ricbot.domain.runtime.ExternalEvent;
import ricbot.domain.runtime.ForkSnapshot;
import ricbot.domain.runtime.Reduction;
import ricbot.domain.runtime.RunState;
import ricbot.domain.runtime.RunStatus;
import ricbot.domain.runtime.RuntimeCommand;
import ricbot.domain.runtime.RuntimePhase;
import ricbot.domain.runtime.RuntimeReducer;
import ricbot.domain.runtime.StateReplay;
import ricbot.domain.runtime.dto.RuntimeDigest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only replay/fork projection component sharing the caller's SQLite transaction. */
final class SqliteRuntimeReplay {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<ChannelWrite>> WRITES = new TypeReference<>() { };
    private static final TypeReference<List<RuntimeCommand>> COMMANDS = new TypeReference<>() { };

    private SqliteRuntimeReplay() { }

    static StateReplay replay(Connection connection, String runId, long throughCommit) throws SQLException {
        return replay(connection, runId, throughCommit, Long.MAX_VALUE);
    }

    static ForkSnapshot forkSnapshot(Connection connection, String runId, long throughCommit) throws SQLException {
        long cutoff = cutoff(connection, runId, throughCommit);
        StateReplay root = replay(connection, runId, throughCommit, cutoff);
        Map<String, RunState> descendants = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>(root.state().childRunIds());
        pending.addAll(root.state().spec().dependencies());
        for (int index = 0; index < pending.size(); index++) {
            String childId = pending.get(index);
            if (descendants.containsKey(childId)) continue;
            RunState child = replay(connection, childId, Long.MAX_VALUE, cutoff).state();
            descendants.put(childId, child);
            pending.addAll(child.childRunIds());
            pending.addAll(child.spec().dependencies());
        }
        return new ForkSnapshot(root.state(), descendants,
                cutoff == Long.MAX_VALUE ? maxTransactionSequence(connection) : cutoff);
    }

    private static StateReplay replay(Connection connection, String runId, long throughCommit,
                                      long throughTransactionSequence) throws SQLException {
        RunState state;
        try (PreparedStatement origin = connection.prepareStatement(
                "SELECT state_json FROM run_origins WHERE run_id=? AND transaction_sequence<=?")) {
            origin.setString(1, required(runId)); origin.setLong(2, throughTransactionSequence);
            try (ResultSet result = origin.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("run not found at cutoff: " + runId);
                state = read(result.getString(1), RunState.class);
            }
        }
        List<CommittedWrite> committedWrites = new ArrayList<>();
        RuntimeReducer reducer = new RuntimeReducer();
        try (PreparedStatement commits = connection.prepareStatement("""
                SELECT commit_sequence,kind,superstep,phase,writes_json,commands_json,external_event_id
                FROM commits WHERE run_id=? AND commit_sequence<=? AND transaction_sequence<=?
                ORDER BY commit_sequence
                """)) {
            commits.setString(1, runId);
            commits.setLong(2, throughCommit < 0 ? Long.MAX_VALUE : throughCommit);
            commits.setLong(3, throughTransactionSequence);
            try (ResultSet result = commits.executeQuery()) {
                while (result.next()) {
                    long sequence = result.getLong(1);
                    String kind = result.getString(2);
                    List<RuntimeCommand> commands = read(result.getString(6), COMMANDS);
                    if ("SUPERSTEP".equals(kind)) {
                        List<ChannelWrite> writes = read(result.getString(5), WRITES);
                        RunState running = SqliteRuntimeStateSupport.copy(state, RunStatus.RUNNING, state.phase(),
                                state.commitSequence(), state.superstep(), null, state.cancelRequested());
                        Reduction reduction = reducer.reduce(running, writes, commands);
                        state = reduction.state();
                        committedWrites.add(new CommittedWrite(sequence, result.getLong(3),
                                RuntimePhase.valueOf(result.getString(4)), writes, commands));
                    } else if ("EXTERNAL_EVENT".equals(kind)) {
                        state = reducer.accept(state, inboxEvent(connection, result.getString(7)), commands);
                    }
                    if (state.commitSequence() != sequence) {
                        throw new IllegalStateException("replay commit gap at " + runId + "@" + sequence);
                    }
                }
            }
        }
        return new StateReplay(runId, state.commitSequence(), state, committedWrites, RuntimeDigest.sha256(state));
    }

    private static long cutoff(Connection connection, String runId, long throughCommit) throws SQLException {
        if (throughCommit < 0 || throughCommit == Long.MAX_VALUE) return Long.MAX_VALUE;
        String sql = throughCommit == 0
                ? "SELECT transaction_sequence FROM run_origins WHERE run_id=?"
                : "SELECT transaction_sequence FROM commits WHERE run_id=? AND commit_sequence=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            if (throughCommit != 0) statement.setLong(2, throughCommit);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException(
                        "commit not found for fork: " + runId + "@" + throughCommit);
                return result.getLong(1);
            }
        }
    }

    private static long maxTransactionSequence(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(sequence),0) FROM runtime_transactions")) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private static ExternalEvent inboxEvent(Connection connection, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_json FROM inbox WHERE event_id=?")) {
            statement.setString(1, eventId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("replay event not found: " + eventId);
                try { return MAPPER.readerFor(ExternalEvent.class).readValue(result.getString(1)); }
                catch (Exception failure) { throw new IllegalStateException("cannot decode replay event", failure); }
            }
        }
    }

    private static <T> T read(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (Exception failure) { throw new IllegalStateException("cannot decode replay value", failure); }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (Exception failure) { throw new IllegalStateException("cannot decode replay value", failure); }
    }

    private static String required(String value) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("runId is required");
        return clean;
    }
}
