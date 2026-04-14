package ricbot.core.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public class SessionManager {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final Path workspace;
    private final Path sessionsDir;
    private final Path legacySessionsDir;
    private final Map<String, Session> cache = new HashMap<>();

    public SessionManager(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.sessionsDir = ensureDir(this.workspace.resolve("sessions"));
        this.legacySessionsDir = ensureDir(this.workspace.resolve("legacy_sessions"));
    }

    public Session getOrCreate(String key) {
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Session session = load(key);
        if (session == null) {
            session = new Session(key);
        }
        cache.put(key, session);
        return session;
    }

    public void save(Session session) {
        Path path = getSessionPath(session.getKey());

        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            Map<String, Object> metadataLine = new LinkedHashMap<>();
            metadataLine.put("_type", "metadata");
            metadataLine.put("key", session.getKey());
            metadataLine.put("created_at", session.getCreatedAt().toString());
            metadataLine.put("updated_at", session.getUpdatedAt().toString());
            metadataLine.put("metadata", session.getMetadata());
            metadataLine.put("last_consolidated", session.getLastConsolidated());

            writer.write(MAPPER.writeValueAsString(metadataLine));
            writer.newLine();

            for (Map<String, Object> msg : session.getMessages()) {
                writer.write(MAPPER.writeValueAsString(msg));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save session " + session.getKey(), e);
        }

        cache.put(session.getKey(), session);
    }

    public void invalidate(String key) {
        cache.remove(key);
    }

    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.exists(sessionsDir)) {
            return result;
        }

        try (var stream = Files.list(sessionsDir)) {
            for (Path file : stream.toList()) {
                if (!file.getFileName().toString().endsWith(".jsonl")) {
                    continue;
                }

                try (BufferedReader reader = Files.newBufferedReader(file)) {
                    String first = reader.readLine();
                    if (first == null || first.isBlank()) {
                        continue;
                    }

                    Map<String, Object> metadata = MAPPER.readValue(first, new TypeReference<>() {});
                    String key = String.valueOf(metadata.getOrDefault("key", ""));
                    String updatedAt = String.valueOf(metadata.getOrDefault("updated_at", ""));
                    int count = 0;
                    while (reader.readLine() != null) {
                        count++;
                    }

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", key);
                    item.put("updated_at", updatedAt);
                    item.put("message_count", count);
                    result.add(item);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }

        result.sort((a, b) -> String.valueOf(b.get("updated_at")).compareTo(String.valueOf(a.get("updated_at"))));
        return result;
    }

    // =========================================================

    private Session load(String key) {
        Path path = getSessionPath(key);
        if (!Files.exists(path)) {
            Path legacy = getLegacySessionPath(key);
            if (Files.exists(legacy)) {
                try {
                    Files.createDirectories(path.getParent());
                    Files.move(legacy, path, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Migrated session " + key + " from legacy path");
                } catch (Exception e) {
                    System.err.println("Failed to migrate session " + key + ": " + e.getMessage());
                }
            }
        }

        if (!Files.exists(path)) {
            return null;
        }

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> metadata = new LinkedHashMap<>();
            Instant createdAt = Instant.now();
            Instant updatedAt = Instant.now();
            int lastConsolidated = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }

                Map<String, Object> data = MAPPER.readValue(line, new TypeReference<>() {});
                if ("metadata".equals(String.valueOf(data.get("_type")))) {
                    metadata = castMap(data.get("metadata"));
                    createdAt = parseInstant(String.valueOf(data.getOrDefault("created_at", Instant.now().toString())));
                    updatedAt = parseInstant(String.valueOf(data.getOrDefault("updated_at", Instant.now().toString())));
                    Object lc = data.get("last_consolidated");
                    if (lc instanceof Number n) {
                        lastConsolidated = n.intValue();
                    }
                } else {
                    messages.add(data);
                }
            }

            return new Session(key)
                    .setMessages(messages)
                    .setMetadata(metadata)
                    .setCreatedAt(createdAt)
                    .setUpdatedAt(updatedAt)
                    .setLastConsolidated(lastConsolidated);
        } catch (Exception e) {
            System.err.println("Failed to load session " + key + ": " + e.getMessage());
            return null;
        }
    }

    private Path getSessionPath(String key) {
        String safe = safeFilename(key.replace(":", "_"));
        return sessionsDir.resolve(safe + ".jsonl");
    }

    private Path getLegacySessionPath(String key) {
        String safe = safeFilename(key.replace(":", "_"));
        return legacySessionsDir.resolve(safe + ".jsonl");
    }

    private static Path ensureDir(Path path) {
        try {
            Files.createDirectories(path);
            return path;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String safeFilename(String name) {
        return name.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    private static Instant parseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
