package ricbot.domain.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ricbot.domain.workspace.dto.WorkspaceSession;
import ricbot.domain.workspace.enump.WorkspaceSessionStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class WorkspaceSessionStore {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceSessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path baseWorkspace;
    private final Path root;

    public WorkspaceSessionStore(Path baseWorkspace) {
        this.baseWorkspace = baseWorkspace.toAbsolutePath().normalize();
        this.root = this.baseWorkspace.resolve(".workspaces");
    }

    public WorkspaceSession save(WorkspaceSession session) {
        if (session == null) {
            throw new IllegalArgumentException("workspace session is null");
        }
        try {
            Path dir = sessionDir(session.id());
            Files.createDirectories(dir);
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(session.toMap()) + "\n";
            Files.writeString(
                    dir.resolve("session.json"),
                    json,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            appendJsonl(session);
            return session;
        } catch (Exception e) {
            throw new IllegalStateException("write workspace session failed: " + session.id(), e);
        }
    }

    public WorkspaceSession load(String sessionId) {
        String id = safeId(sessionId);
        if (id.isBlank()) {
            return null;
        }
        Path file = sessionDir(id).resolve("session.json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return WorkspaceSession.fromMap(MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE));
        } catch (Exception e) {
            log.warn("skip workspace session read: {}", id, e);
            return null;
        }
    }

    public List<WorkspaceSession> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<WorkspaceSession> out = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                WorkspaceSession session = load(dir.getFileName().toString());
                if (session != null) {
                    out.add(session);
                }
            }
        } catch (Exception e) {
            log.warn("skip workspace session list", e);
            return List.of();
        }
        return out.stream()
                .sorted(Comparator.comparing(WorkspaceSession::updatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .toList();
    }

    public List<WorkspaceSession> loadActive() {
        return list().stream()
                .filter(session -> session.status() == WorkspaceSessionStatus.ACTIVE)
                .toList();
    }

    public WorkspaceSession close(String sessionId) {
        WorkspaceSession session = require(sessionId);
        WorkspaceSession closed = session.withStatus(WorkspaceSessionStatus.CLOSED);
        save(closed);
        return closed;
    }

    public WorkspaceSession cleanup(String sessionId) {
        WorkspaceSession session = require(sessionId);
        WorkspaceSession cleaned = session.withStatus(WorkspaceSessionStatus.CLEANED);
        save(cleaned);
        return cleaned;
    }

    public WorkspaceSession failed(String sessionId, String reason) {
        WorkspaceSession session = require(sessionId);
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(session.metadata());
        metadata.put("failureReason", reason != null ? reason : "");
        WorkspaceSession failed = session.withStatus(WorkspaceSessionStatus.FAILED).withMetadata(metadata);
        save(failed);
        return failed;
    }

    public Path sessionDir(String sessionId) {
        Path dir = root.resolve(safeId(sessionId)).normalize();
        if (!dir.startsWith(root.normalize())) {
            throw new IllegalArgumentException("workspace session path escapes .workspaces: " + sessionId);
        }
        return dir;
    }

    public Path sessionFile(String sessionId) {
        return sessionDir(sessionId).resolve("session.json");
    }

    public Path root() {
        return root;
    }

    private WorkspaceSession require(String sessionId) {
        WorkspaceSession session = load(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("workspace session not found: " + sessionId);
        }
        return session;
    }

    private void appendJsonl(WorkspaceSession session) {
        try {
            Files.createDirectories(root);
            Files.writeString(
                    root.resolve("sessions.jsonl"),
                    MAPPER.writeValueAsString(session.toMap()) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
        } catch (Exception e) {
            log.warn("skip workspace sessions.jsonl write: {}", session.id(), e);
        }
    }

    private String safeId(String value) {
        String id = value != null ? value.trim().replaceAll("[^A-Za-z0-9._-]+", "_") : "";
        while (id.contains("..")) {
            id = id.replace("..", "_");
        }
        if (id.startsWith(".") || id.equals(".")) {
            return "";
        }
        return id;
    }
}
