package ricbot.infra.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.runtime.RuntimeDigest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Query-only catalog for pre-schema-v2 databases. It exposes no mutation capability. */
public final class ArchivedRuntimeCatalog {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final Path archiveRoot;

    public ArchivedRuntimeCatalog(Path workspace) {
        this.archiveRoot = workspace.toAbsolutePath().normalize().resolve(".ricbot/archive");
    }

    public List<Map<String, Object>> runs() {
        List<Map<String, Object>> runs = new ArrayList<>();
        for (Archive archive : archives()) {
            query(archive.database(), connection -> {
                if (!table(connection, "runtime_runs")) return null;
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT run_id, state_json FROM runtime_runs ORDER BY run_id")) {
                    while (result.next()) {
                        Map<String, Object> state = jsonMap(result.getString(2));
                        Map<String, Object> view = new LinkedHashMap<>();
                        view.put("runId", result.getString(1));
                        view.put("archiveId", archive.id());
                        view.put("source", "ARCHIVED");
                        view.put("executionEligible", false);
                        view.put("state", state);
                        runs.add(Map.copyOf(view));
                    }
                }
                return null;
            });
        }
        return List.copyOf(runs);
    }

    public Map<String, Object> status(String runId) {
        return runs().stream().filter(run -> runId.equals(String.valueOf(run.get("runId"))))
                .findFirst().orElse(null);
    }

    public List<Map<String, Object>> events(String runId, long throughSequence) {
        for (Archive archive : archives()) {
            List<Map<String, Object>> found = query(archive.database(), connection -> {
                if (!table(connection, "runtime_events")) return List.of();
                if (!column(connection, "runtime_events", "run_id")) return List.of();
                List<Map<String, Object>> events = new ArrayList<>();
                String sql = "SELECT * FROM runtime_events WHERE run_id = '" + sqlLiteral(runId)
                        + "' ORDER BY global_sequence";
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                    var metadata = result.getMetaData();
                    while (result.next()) {
                        long sequence = hasColumn(metadata, "global_sequence") ? result.getLong("global_sequence") : 0;
                        if (sequence > throughSequence) break;
                        Map<String, Object> event = new LinkedHashMap<>();
                        for (int index = 1; index <= metadata.getColumnCount(); index++) {
                            String name = metadata.getColumnLabel(index);
                            Object value = result.getObject(index);
                            if (value instanceof String text && name.endsWith("_json")) {
                                try { value = MAPPER.readValue(text, Object.class); } catch (Exception ignored) { }
                            }
                            event.put(name, value);
                        }
                        event.put("archiveId", archive.id());
                        event.put("source", "ARCHIVED");
                        events.add(Map.copyOf(event));
                    }
                }
                return List.copyOf(events);
            });
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    public Map<String, Object> replay(String runId, long throughSequence) {
        Map<String, Object> archived = status(runId);
        if (archived == null) throw new IllegalArgumentException("archived run not found: " + runId);
        List<Map<String, Object>> events = events(runId, throughSequence);
        return Map.of("runId", runId, "source", "ARCHIVED", "executionEligible", false,
                "legacyReplay", true, "throughSequence", throughSequence, "eventDigest",
                RuntimeDigest.sha256(events), "state", archived.get("state"), "events", events);
    }

    private List<Archive> archives() {
        if (!Files.isDirectory(archiveRoot)) return List.of();
        try (var paths = Files.list(archiveRoot)) {
            return paths.filter(Files::isDirectory).sorted().map(path -> {
                Path backup = path.resolve("runtime-v1.db");
                if (!Files.isRegularFile(backup)) backup = path.resolve("runtime-v1.original.db");
                return new Archive(path.getFileName().toString(), backup);
            }).filter(archive -> Files.isRegularFile(archive.database())).toList();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read archived runtime catalog", failure);
        }
    }

    private static boolean table(Connection connection, String name) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }
    private static boolean column(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (result.next()) if (column.equalsIgnoreCase(result.getString("name"))) return true;
            return false;
        }
    }
    private static Map<String, Object> jsonMap(String json) {
        try { return MAPPER.readValue(json, MAP); }
        catch (Exception failure) { return Map.of("unreadableState", true); }
    }
    private static boolean hasColumn(java.sql.ResultSetMetaData metadata, String name) throws Exception {
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            if (name.equalsIgnoreCase(metadata.getColumnLabel(index))) return true;
        }
        return false;
    }
    private static String sqlLiteral(String value) { return value != null ? value.replace("'", "''") : ""; }
    private static <T> T query(Path database, SqlQuery<T> query) {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:file:" + database + "?mode=ro&immutable=1")) {
            return query.apply(connection);
        } catch (Exception failure) { throw new IllegalStateException("archived runtime query failed", failure); }
    }
    private record Archive(String id, Path database) { }
    @FunctionalInterface private interface SqlQuery<T> { T apply(Connection connection) throws Exception; }
}
