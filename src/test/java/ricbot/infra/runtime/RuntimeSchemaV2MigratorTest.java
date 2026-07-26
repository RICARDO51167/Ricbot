package ricbot.infra.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSchemaV2MigratorTest {
    @TempDir Path workspace;

    @Test
    void archivesV1DatabaseAndCreatesAnEmptyExecutionCapableV2() throws Exception {
        Path database = workspace.resolve(SqliteRuntimeStore.DATABASE_RELATIVE_PATH);
        Files.createDirectories(database.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT, digest TEXT)");
            statement.execute("INSERT INTO schema_migrations VALUES (1, 'old', 'v1')");
            statement.execute("CREATE TABLE runtime_events(global_sequence INTEGER PRIMARY KEY, event_type TEXT)");
            statement.execute("INSERT INTO runtime_events VALUES (7, 'RUN_COMPLETED')");
        }

        assertThrows(LegacyRuntimeDatabaseException.class, () -> new SqliteRuntimeStore(workspace));
        RuntimeSchemaV2Migrator.Result result = RuntimeSchemaV2Migrator.prepare(workspace);

        assertTrue(result.migrated());
        assertFalse(result.executionEligible());
        assertTrue(Files.isRegularFile(result.archivedDatabase()));
        assertTrue(Files.isRegularFile(result.archive().resolve("manifest.json")));
        try (SqliteRuntimeStore ignored = new SqliteRuntimeStore(workspace);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + ignored.database());
             var query = connection.createStatement().executeQuery("SELECT MAX(version) FROM schema_migrations")) {
            assertTrue(query.next());
            assertEquals(2, query.getInt(1));
        }
    }
}
