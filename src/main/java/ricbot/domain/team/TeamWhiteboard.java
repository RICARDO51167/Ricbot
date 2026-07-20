package ricbot.domain.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TeamWhiteboard {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path workspace;
    private final String sessionId;
    private final Path sessionDir;
    private final Path whiteboardFile;
    private final Path eventsFile;
    private final Path artifactsFile;

    public TeamWhiteboard(Path workspace, String sessionId) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.sessionId = sessionId != null && !sessionId.isBlank() ? sessionId : "team_unknown";
        this.sessionDir = this.workspace.resolve(".team").resolve(this.sessionId);
        this.whiteboardFile = sessionDir.resolve("whiteboard.md");
        this.eventsFile = sessionDir.resolve("events.jsonl");
        this.artifactsFile = sessionDir.resolve("artifacts.jsonl");
        ensureLayout();
    }

    public void appendNote(String note) {
        String value = note != null ? note.trim() : "";
        if (value.isBlank()) {
            return;
        }
        try {
            Files.writeString(
                    whiteboardFile,
                    "\n## " + Instant.now() + "\n\n" + value + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            throw new RuntimeException("append team whiteboard failed: " + whiteboardFile, e);
        }
    }

    public String readSummary() {
        if (!Files.exists(whiteboardFile)) {
            return "";
        }
        try {
            String body = Files.readString(whiteboardFile, StandardCharsets.UTF_8).trim();
            if (body.length() <= 900) {
                return body;
            }
            return body.substring(Math.max(0, body.length() - 900)).trim();
        } catch (Exception e) {
            return "";
        }
    }

    public void appendEvent(TeamEvent event) {
        appendJsonLine(eventsFile, event.toMap());
    }

    public void appendArtifact(TeamArtifact artifact) {
        appendJsonLine(artifactsFile, artifact.toMap());
    }

    public List<TeamEvent> readEvents() {
        List<TeamEvent> out = new ArrayList<>();
        for (Map<String, Object> row : readJsonLines(eventsFile)) {
            TeamEvent event = TeamEvent.fromMap(row);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }

    public List<TeamArtifact> readArtifacts() {
        List<TeamArtifact> out = new ArrayList<>();
        for (Map<String, Object> row : readJsonLines(artifactsFile)) {
            TeamArtifact artifact = TeamArtifact.fromMap(row);
            if (artifact != null) {
                out.add(artifact);
            }
        }
        return out;
    }

    public Path whiteboardPath() {
        return whiteboardFile;
    }

    public Path eventsPath() {
        return eventsFile;
    }

    public Path artifactsPath() {
        return artifactsFile;
    }

    public String relativeWhiteboardPath() {
        return workspace.relativize(whiteboardFile).toString().replace('\\', '/');
    }

    private void ensureLayout() {
        try {
            Files.createDirectories(sessionDir);
            if (!Files.exists(whiteboardFile)) {
                Files.writeString(whiteboardFile, "# Team Whiteboard " + sessionId + "\n", StandardCharsets.UTF_8);
            }
            for (Path file : List.of(eventsFile, artifactsFile)) {
                if (!Files.exists(file)) {
                    Files.writeString(file, "", StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("create team whiteboard failed: " + sessionDir, e);
        }
    }

    private void appendJsonLine(Path file, Map<String, Object> row) {
        try {
            Files.writeString(
                    file,
                    MAPPER.writeValueAsString(row != null ? row : new LinkedHashMap<>()) + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
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
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }
}
