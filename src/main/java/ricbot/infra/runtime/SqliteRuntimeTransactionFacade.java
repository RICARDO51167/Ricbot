package ricbot.infra.runtime;

import ricbot.domain.runtime.RuntimeEvent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Owns SQLite connections, BEGIN IMMEDIATE boundaries and one global sequence per mutation. */
final class SqliteRuntimeTransactionFacade {
    private final String jdbcUrl;
    private final ThreadLocal<Long> sequence = new ThreadLocal<>();
    private final ThreadLocal<List<RuntimeEvent>> publications = new ThreadLocal<>();

    SqliteRuntimeTransactionFacade(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }

    <T> T read(SqlWork<T> work) {
        try (Connection connection = connection()) { return work.apply(connection); }
        catch (SQLException failure) { throw new IllegalStateException("runtime database read failed", failure); }
    }

    <T> Mutation<T> mutate(SqlWork<T> work) {
        return mutateWhen(connection -> true, work, null);
    }

    <T> Mutation<T> mutateWhen(SqlPredicate condition, SqlWork<T> work, T unchanged) {
        List<RuntimeEvent> events = new ArrayList<>();
        T value;
        try (Connection connection = connection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            publications.set(events);
            try {
                if (!condition.test(connection)) {
                    transaction.execute("COMMIT");
                    return new Mutation<>(unchanged, List.of());
                }
                if (tableExists(connection, "runtime_transactions")) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO runtime_transactions(created_at) VALUES(?)")) {
                        insert.setString(1, Instant.now().toString()); insert.executeUpdate();
                    }
                    try (Statement generated = connection.createStatement();
                         ResultSet result = generated.executeQuery("SELECT last_insert_rowid()")) {
                        if (!result.next()) throw new SQLException(
                                "runtime transaction sequence was not allocated");
                        sequence.set(result.getLong(1));
                    }
                }
                value = work.apply(connection);
                transaction.execute("COMMIT");
            } catch (Exception failure) {
                try { transaction.execute("ROLLBACK"); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof SQLException sql) throw sql;
                throw new IllegalStateException(failure);
            } finally {
                publications.remove();
                sequence.remove();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("runtime database mutation failed", failure);
        }
        return new Mutation<>(value, List.copyOf(events));
    }

    long currentSequence() {
        Long value = sequence.get();
        if (value == null || value <= 0L) {
            throw new IllegalStateException("runtime transaction sequence is not available");
        }
        return value;
    }

    void publishAfterCommit(RuntimeEvent event) {
        List<RuntimeEvent> pending = publications.get();
        if (pending != null) pending.add(event);
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

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    record Mutation<T>(T value, List<RuntimeEvent> events) { }

    @FunctionalInterface
    interface SqlWork<T> { T apply(Connection connection) throws SQLException; }
    @FunctionalInterface
    interface SqlPredicate { boolean test(Connection connection) throws SQLException; }
}
