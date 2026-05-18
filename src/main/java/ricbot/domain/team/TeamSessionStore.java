package ricbot.domain.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TeamSessionStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path workspace;
    private final Path teamRoot;

    public TeamSessionStore(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.teamRoot = this.workspace.resolve(".team");
    }

    public void saveSession(TeamSession session) {
        if (session == null || session.id().isBlank()) {
            return;
        }
        saveSession(session, isArchived(session.id()));
    }

    public TeamSession loadSession(String sessionId) {
        String id = sessionId != null ? sessionId.trim() : "";
        if (id.isBlank()) {
            return null;
        }
        Path dir = teamRoot.resolve(id);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        return restoreFromDirectory(dir);
    }

    public List<TeamSession> listSessions() {
        if (!Files.isDirectory(teamRoot)) {
            return List.of();
        }
        List<TeamSession> out = new ArrayList<>();
        try (var stream = Files.list(teamRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                try {
                    TeamSession session = restoreFromDirectory(dir);
                    if (session != null) {
                        out.add(session);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return out.stream()
                .sorted(Comparator.comparing(TeamSession::updatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .toList();
    }

    public TeamSession loadLatestActiveSession() {
        return listSessions().stream()
                .filter(session -> !isArchived(session.id()))
                .filter(session -> session.state() != TeamTaskState.DONE
                        && session.state() != TeamTaskState.FAILED
                        && session.state() != TeamTaskState.ABORTED)
                .findFirst()
                .orElse(null);
    }

    public TeamSession archiveSession(String sessionId) {
        TeamSession session = loadSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("team session not found: " + sessionId);
        }
        saveSession(session, true);
        return session;
    }

    public TeamSession restoreActiveSession(String sessionId) {
        TeamSession session = loadSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("team session not found: " + sessionId);
        }
        saveSession(session, false);
        return session;
    }

    public TeamSession restoreFromDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        ensureLayout(directory);
        Map<String, Object> rawSession = readMap(directory.resolve("session.json"));
        String sessionId = !string(rawSession.get("id")).isBlank()
                ? string(rawSession.get("id"))
                : directory.getFileName().toString();
        List<TeamTask> tasks = readTasks(directory.resolve("tasks.jsonl"));
        if (rawSession.isEmpty()) {
            return new TeamSession(sessionId, "", TeamTaskState.PLANNING, tasks, null, updatedAt(directory));
        }
        if (!tasks.isEmpty()) {
            rawSession.put("tasks", tasks.stream().map(TeamTask::toMap).toList());
        }
        return TeamSession.fromMap(rawSession);
    }

    public boolean isArchived(String sessionId) {
        Map<String, Object> raw = readMap(sessionDir(sessionId).resolve("session.json"));
        Object archived = raw.get("archived");
        return archived instanceof Boolean b ? b : archived != null && Boolean.parseBoolean(String.valueOf(archived));
    }

    public List<TeamEvent> loadEvents(String sessionId) {
        List<TeamEvent> out = new ArrayList<>();
        for (Map<String, Object> row : readJsonLines(sessionDir(sessionId).resolve("events.jsonl"))) {
            TeamEvent event = TeamEvent.fromMap(row);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }

    public List<TeamArtifact> loadArtifacts(String sessionId) {
        List<TeamArtifact> out = new ArrayList<>();
        for (Map<String, Object> row : readJsonLines(sessionDir(sessionId).resolve("artifacts.jsonl"))) {
            TeamArtifact artifact = TeamArtifact.fromMap(row);
            if (artifact != null) {
                out.add(artifact);
            }
        }
        return out;
    }

    public List<Map<String, Object>> loadVerificationReports(String sessionId) {
        return readJsonLines(sessionDir(sessionId).resolve("verification.jsonl"));
    }

    public List<Map<String, Object>> loadVerificationReportsForTask(String sessionId, String taskId) {
        String id = taskId != null ? taskId.trim() : "";
        return loadVerificationReports(sessionId).stream()
                .filter(row -> id.isBlank() || id.equals(String.valueOf(row.getOrDefault("taskId", ""))))
                .toList();
    }

    public void appendVerification(String sessionId, TeamTask task) {
        if (task == null || task.verificationResult() == null) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", task.id());
        row.put("role", task.role().name());
        row.put("state", task.state().name());
        row.put("verificationResult", task.verificationResult().toMap());
        row.put("revisionRequest", task.revisionRequest());
        row.put("createdAt", Instant.now().toString());
        appendJsonLine(sessionDir(sessionId).resolve("verification.jsonl"), row);
    }

    public Path sessionDir(String sessionId) {
        return teamRoot.resolve(sessionId != null && !sessionId.isBlank() ? sessionId : "team_unknown");
    }

    public Path teamRoot() {
        return teamRoot;
    }

    private void saveSession(TeamSession session, boolean archived) {
        Path dir = sessionDir(session.id());
        ensureLayout(dir);
        Map<String, Object> raw = new LinkedHashMap<>(session.toMap());
        raw.put("archived", archived);
        writeMap(dir.resolve("session.json"), raw);
        writeJsonLines(
                dir.resolve("tasks.jsonl"),
                session.tasks().stream().map(TeamTask::toMap).toList()
        );
    }

    private List<TeamTask> readTasks(Path file) {
        List<TeamTask> out = new ArrayList<>();
        for (Map<String, Object> row : readJsonLines(file)) {
            TeamTask task = TeamTask.fromMap(row);
            if (task != null) {
                out.add(task);
            }
        }
        return out;
    }

    private void ensureLayout(Path dir) {
        try {
            Files.createDirectories(dir);
            if (!Files.exists(dir.resolve("whiteboard.md"))) {
                Files.writeString(dir.resolve("whiteboard.md"), "# Team Whiteboard " + dir.getFileName() + "\n", StandardCharsets.UTF_8);
            }
            for (String file : List.of("tasks.jsonl", "events.jsonl", "artifacts.jsonl")) {
                Path target = dir.resolve(file);
                if (!Files.exists(target)) {
                    Files.writeString(target, "", StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("create team session layout failed: " + dir, e);
        }
    }

    private void writeMap(Path file, Map<String, Object> row) {
        try {
            Files.writeString(
                    file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(row != null ? row : Map.of()) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception e) {
            throw new RuntimeException("write team session failed: " + file, e);
        }
    }

    private Map<String, Object> readMap(Path file) {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void writeJsonLines(Path file, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Map<String, Object> row : rows != null ? rows : List.<Map<String, Object>>of()) {
                sb.append(MAPPER.writeValueAsString(row != null ? row : Map.of())).append("\n");
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException("write team jsonl failed: " + file, e);
        }
    }

    private void appendJsonLine(Path file, Map<String, Object> row) {
        try {
            ensureLayout(file.getParent());
            Files.writeString(
                    file,
                    MAPPER.writeValueAsString(row != null ? row : Map.of()) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            throw new RuntimeException("append team jsonl failed: " + file, e);
        }
    }

    private List<Map<String, Object>> readJsonLines(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line != null ? line.trim() : "";
                if (!trimmed.isBlank()) {
                    out.add(MAPPER.readValue(trimmed, MAP_TYPE));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return out;
    }

    private String updatedAt(Path directory) {
        try {
            return Files.getLastModifiedTime(directory).toInstant().toString();
        } catch (Exception e) {
            return Instant.now().toString();
        }
    }

    private String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }
}
