package ricbot.infra.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.sqlite.SQLiteConnection;
import ricbot.domain.runtime.RuntimeDigest;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One-shot boundary between execution-capable schema v2 and every older runtime format.
 * Legacy facts are copied with SQLite's Backup API and remain query-only.
 */
public final class RuntimeSchemaV2Migrator {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final List<String> LEGACY_DIRECTORIES = List.of(
            "runtime-v2", "side-effects", "run-journal", "run-checkpoints",
            "worker-runtime", "team", "team-runtime");

    private RuntimeSchemaV2Migrator() { }

    public static Result prepare(Path rawWorkspace) {
        Path workspace = Objects.requireNonNull(rawWorkspace, "workspace").toAbsolutePath().normalize();
        Path ricbot = workspace.resolve(".ricbot");
        Path database = workspace.resolve(SqliteRuntimeStore.DATABASE_RELATIVE_PATH);
        try { Files.createDirectories(ricbot); }
        catch (IOException failure) { throw new IllegalStateException("cannot create runtime directory", failure); }

        int version = Files.isRegularFile(database) ? schemaVersion(database) : 0;
        boolean legacyFiles = LEGACY_DIRECTORIES.stream().map(ricbot::resolve).anyMatch(RuntimeSchemaV2Migrator::hasFiles);
        if (version >= 2 && !legacyFiles) return Result.notRequired();
        if (!Files.exists(database) && !legacyFiles) return Result.notRequired();

        Path lockFile = ricbot.resolve("runtime-migration.lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = requireLock(channel)) {
            version = Files.isRegularFile(database) ? schemaVersion(database) : 0;
            legacyFiles = LEGACY_DIRECTORIES.stream().map(ricbot::resolve)
                    .anyMatch(RuntimeSchemaV2Migrator::hasFiles);
            if (version >= 2 && !legacyFiles) return Result.notRequired();
            if (version >= 2) return archiveLegacyDirectories(ricbot, version);
            return archiveAndReplace(workspace, ricbot, database, version);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot acquire runtime migration lock", failure);
        }
    }

    private static Result archiveLegacyDirectories(Path ricbot, int sourceVersion) {
        String migrationId = "legacy-files-" + ID_TIME.format(Instant.now()) + "-"
                + RuntimeDigest.sha256(LEGACY_DIRECTORIES).substring(0, 12);
        Path archive = ricbot.resolve("archive").resolve(migrationId);
        Map<Path, Path> moved = new LinkedHashMap<>();
        try {
            Files.createDirectories(archive);
            for (String directory : LEGACY_DIRECTORIES) {
                moveIfPresent(ricbot.resolve(directory), archive.resolve(directory), moved);
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("migrationId", migrationId);
            manifest.put("createdAt", Instant.now().toString());
            manifest.put("sourceSchemaVersion", sourceVersion);
            manifest.put("targetSchemaVersion", 2);
            manifest.put("legacyDirectoriesOnly", true);
            manifest.put("executionEligible", false);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(archive.resolve("manifest.json").toFile(), manifest);
            return new Result(migrationId, true, archive, null, sourceVersion, false);
        } catch (Exception failure) {
            try {
                rollback(ricbot.resolve("runtime.db.unused"), moved);
                deletePartialArchive(archive);
            } catch (RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw new IllegalStateException("legacy runtime directory archive failed", failure);
        }
    }

    private static Result archiveAndReplace(Path workspace, Path ricbot, Path database, int sourceVersion) {
        String preCheckpointDigest = Files.isRegularFile(database)
                ? sha256(database) : RuntimeDigest.sha256("no-database");
        String migrationId = "schema-v2-" + ID_TIME.format(Instant.now()) + "-"
                + preCheckpointDigest.substring(0, 12);
        Path archive = ricbot.resolve("archive").resolve(migrationId);
        Path backup = archive.resolve("runtime-v1.db");
        Map<Path, Path> moved = new LinkedHashMap<>();
        try {
            Files.createDirectories(archive);
            DatabaseFacts sourceFacts = Files.isRegularFile(database)
                    ? backupAndValidateSource(database, backup) : DatabaseFacts.empty();
            if (Files.isRegularFile(backup)) verifyBackup(backup, sourceFacts);
            String sourceDigest = Files.isRegularFile(database)
                    ? sha256(database) : RuntimeDigest.sha256("no-database");

            Path archivedOriginal = archive.resolve("runtime-v1.original.db");
            moveIfPresent(database, archivedOriginal, moved);
            if (Files.isRegularFile(archivedOriginal) && !sourceDigest.equals(sha256(archivedOriginal))) {
                throw new IllegalStateException("archived original database digest changed during move");
            }
            moveIfPresent(database.resolveSibling(database.getFileName() + "-wal"),
                    archive.resolve("runtime-v1.original.db-wal"), moved);
            moveIfPresent(database.resolveSibling(database.getFileName() + "-shm"),
                    archive.resolve("runtime-v1.original.db-shm"), moved);
            for (String directory : LEGACY_DIRECTORIES) {
                Path source = ricbot.resolve(directory);
                if (Files.exists(source)) moveIfPresent(source, archive.resolve(directory), moved);
            }

            try (SqliteRuntimeStore ignored = new SqliteRuntimeStore(workspace)) {
                verifyIntegrity(ignored.database());
            }
            String backupDigest = Files.isRegularFile(backup) ? sha256(backup) : "";
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("migrationId", migrationId);
            manifest.put("createdAt", Instant.now().toString());
            manifest.put("sourceSchemaVersion", sourceVersion);
            manifest.put("targetSchemaVersion", 2);
            manifest.put("preCheckpointSourceDigest", preCheckpointDigest);
            manifest.put("sourceDigest", sourceDigest);
            manifest.put("backupDigest", backupDigest);
            manifest.put("tableCounts", sourceFacts.tableCounts());
            manifest.put("eventCount", sourceFacts.eventCount());
            manifest.put("firstEventSequence", sourceFacts.firstEventSequence());
            manifest.put("lastEventSequence", sourceFacts.lastEventSequence());
            manifest.put("executionEligible", false);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(archive.resolve("manifest.json").toFile(), manifest);
            return new Result(migrationId, true, archive, backup, sourceVersion, false);
        } catch (Exception failure) {
            try {
                rollback(database, moved);
                deletePartialArchive(archive);
            } catch (RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw new IllegalStateException("schema v2 archive migration failed; original runtime was restored", failure);
        }
    }

    private static DatabaseFacts backupAndValidateSource(Path database, Path backup) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
            requireIntegrity(statement);
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            statement.execute("BEGIN EXCLUSIVE");
            DatabaseFacts facts;
            try {
                facts = facts(connection);
                // SQLite's online Backup API cannot run while the source connection owns an
                // EXCLUSIVE transaction. Acquiring and committing it first proves there is no
                // live writer; the post-backup fact comparison detects any non-cooperating writer.
                statement.execute("COMMIT");
                SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
                int result = sqlite.getDatabase().backup("main", backup.toString(), null);
                if (result != 0) throw new SQLException("SQLite Backup API returned " + result);
                statement.execute("BEGIN EXCLUSIVE");
                DatabaseFacts afterBackup = facts(connection);
                statement.execute("COMMIT");
                if (!facts.equals(afterBackup)) {
                    throw new SQLException("legacy runtime changed during archive backup");
                }
                return facts;
            } catch (Exception failure) {
                try { if (!connection.getAutoCommit()) statement.execute("ROLLBACK"); }
                catch (SQLException rollback) { failure.addSuppressed(rollback); }
                if (failure instanceof SQLException sql) throw sql;
                throw new SQLException("cannot archive legacy runtime", failure);
            }
        }
    }

    private static void verifyBackup(Path backup, DatabaseFacts expected) throws SQLException {
        String url = "jdbc:sqlite:file:" + backup + "?mode=ro&immutable=1";
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            requireIntegrity(statement);
            DatabaseFacts actual = facts(connection);
            if (!expected.equals(actual)) throw new SQLException("archived runtime facts do not match the source");
        }
    }

    private static void verifyIntegrity(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            requireIntegrity(statement);
            try (ResultSet result = statement.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
                if (!result.next() || result.getInt(1) != 2) throw new SQLException("new runtime is not schema v2");
            }
        }
    }

    private static void requireIntegrity(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("runtime database integrity_check failed");
            }
        }
    }

    private static DatabaseFacts facts(Connection connection) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name
                     """)) {
            while (result.next()) tables.add(result.getString(1));
        }
        for (String table : tables) {
            if (!table.matches("[A-Za-z0-9_]+")) throw new SQLException("unsafe SQLite table name");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
                counts.put(table, result.next() ? result.getLong(1) : 0L);
            }
        }
        if (!tables.contains("runtime_events")) return new DatabaseFacts(Map.copyOf(counts), 0, 0, 0);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*), COALESCE(MIN(global_sequence), 0), COALESCE(MAX(global_sequence), 0)
                     FROM runtime_events
                     """)) {
            result.next();
            return new DatabaseFacts(Map.copyOf(counts), result.getLong(1), result.getLong(2), result.getLong(3));
        }
    }

    private static int schemaVersion(Path database) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + database + "?mode=ro");
             PreparedStatement tables = connection.prepareStatement("""
                     SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'
                     """)) {
            try (ResultSet result = tables.executeQuery()) { if (!result.next()) return 1; }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
                int version = result.next() ? result.getInt(1) : 0;
                return version > 0 ? version : 1;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot inspect runtime schema", failure);
        }
    }

    private static FileLock requireLock(FileChannel channel) throws IOException {
        FileLock lock = channel.tryLock();
        if (lock == null) throw new IOException("another runtime migration is active");
        return lock;
    }

    private static void moveIfPresent(Path source, Path target, Map<Path, Path> moved) throws IOException {
        if (!Files.exists(source)) return;
        Files.createDirectories(target.getParent());
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target); }
        moved.put(source, target);
    }

    private static void rollback(Path database, Map<Path, Path> moved) {
        try { Files.deleteIfExists(database); }
        catch (IOException ignored) { }
        List<Map.Entry<Path, Path>> entries = new ArrayList<>(moved.entrySet());
        java.util.Collections.reverse(entries);
        for (Map.Entry<Path, Path> entry : entries) {
            try {
                if (Files.exists(entry.getValue())) {
                    Files.createDirectories(entry.getKey().getParent());
                    Files.move(entry.getValue(), entry.getKey(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException restoreFailure) {
                throw new IllegalStateException("failed to restore " + entry.getKey(), restoreFailure);
            }
        }
    }

    private static void deletePartialArchive(Path archive) {
        if (!Files.exists(archive)) return;
        try (var paths = Files.walk(archive)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException cleanupFailure) {
            throw new IllegalStateException("failed to remove partial migration archive " + archive, cleanupFailure);
        }
    }

    private static boolean hasFiles(Path directory) {
        if (!Files.exists(directory)) return false;
        if (Files.isRegularFile(directory)) return true;
        try (var paths = Files.walk(directory)) { return paths.anyMatch(Files::isRegularFile); }
        catch (IOException failure) { throw new IllegalStateException("cannot inspect legacy runtime", failure); }
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) { throw new IllegalStateException("cannot digest " + path, failure); }
    }

    private record DatabaseFacts(Map<String, Long> tableCounts, long eventCount,
                                 long firstEventSequence, long lastEventSequence) {
        private static DatabaseFacts empty() { return new DatabaseFacts(Map.of(), 0, 0, 0); }
    }

    public record Result(String migrationId, boolean migrated, Path archive, Path archivedDatabase,
                         int sourceSchemaVersion, boolean executionEligible) {
        private static Result notRequired() { return new Result("", false, null, null, 2, true); }
    }
}
