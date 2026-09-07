package ricbot.infra.runtime;

import ricbot.domain.runtime.ForkSnapshot;
import ricbot.domain.runtime.StateReplay;

import java.sql.Connection;
import java.sql.SQLException;

/** Consistent-cut Replay and Fork snapshot queries. */
final class SqliteRuntimeForkQueries {
    StateReplay replay(Connection connection, String runId, long throughCommit) throws SQLException {
        return SqliteRuntimeReplay.replay(connection, runId, throughCommit);
    }

    ForkSnapshot snapshot(Connection connection, String runId, long throughCommit) throws SQLException {
        return SqliteRuntimeReplay.forkSnapshot(connection, runId, throughCommit);
    }
}
