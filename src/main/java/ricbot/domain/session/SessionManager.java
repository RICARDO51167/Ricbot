package ricbot.domain.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.common.HelperUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;

/**
 * 会话管理器，负责会话的加载、保存、缓存及迁移。
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final Path workspace;
    private final Path sessionsDir;
    private final Path legacySessionsDir;
    private final Map<String, Session> cache = new ConcurrentHashMap<>();

    public SessionManager(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.sessionsDir = HelperUtils.ensureDir(this.workspace.resolve("sessions"));
        this.legacySessionsDir = HelperUtils.ensureDir(this.workspace.resolve("legacy_sessions"));
    }

    public Session getOrCreate(String key) {
        return cache.computeIfAbsent(key, k -> {
            Session session = load(k);
            return session != null ? session : new Session(k);
        });
    }

    public void save(Session session) {
        Path path = getSessionPath(session.getKey());

        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new RuntimeException("保存会话失败（创建目录失败）：" + session.getKey(), e);
        }

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            Map<String, Object> metadataLine = new LinkedHashMap<>();
            metadataLine.put("_type", "metadata");
            metadataLine.put("key", session.getKey());
            metadataLine.put("created_at", session.getCreatedAt().toString());
            metadataLine.put("updated_at", session.getUpdatedAt().toString());
            metadataLine.put("metadata", session.getMetadata());
            metadataLine.put("last_consolidated", session.getLastConsolidated());
            metadataLine.put("message_count", session.getMessages().size());

            writer.write(MAPPER.writeValueAsString(metadataLine));
            writer.newLine();

            for (Map<String, Object> msg : session.getMessages()) {
                writer.write(MAPPER.writeValueAsString(msg));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("保存会话失败：" + session.getKey(), e);
        }

        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new RuntimeException("保存会话失败（重命名失败）：" + session.getKey(), ex);
            }
        } catch (IOException e) {
            throw new RuntimeException("保存会话失败（重命名失败）：" + session.getKey(), e);
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
                    Object c = metadata.get("message_count");
                    if (c instanceof Number n) {
                        count = n.intValue();
                    } else {
                        while (reader.readLine() != null) {
                            count++;
                        }
                    }

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", key);
                    item.put("updated_at", updatedAt);
                    item.put("message_count", count);
                    result.add(item);
                } catch (Exception e) {
                    log.debug("读取会话摘要失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.warn("遍历 sessions 目录失败: {}", sessionsDir, e);
        }

        result.sort((a, b) -> String.valueOf(b.get("updated_at")).compareTo(String.valueOf(a.get("updated_at"))));
        return result;
    }

    private Session load(String key) {
        Path path = resolveOrMigratePath(key);

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
            log.warn("加载会话失败: key={}", key, e);
            return null;
        }
    }

    private Path getSessionPath(String key) {
        String safe = HelperUtils.safeFilename(key.replace(":", "_"));
        String suffix = shortHash(key);
        return sessionsDir.resolve(safe + "-" + suffix + ".jsonl");
    }

    private Path getLegacySessionPath(String key) {
        String safe = HelperUtils.safeFilename(key.replace(":", "_"));
        return legacySessionsDir.resolve(safe + ".jsonl");
    }

    private Path getLegacyNameInSessionsDir(String key) {
        String safe = HelperUtils.safeFilename(key.replace(":", "_"));
        return sessionsDir.resolve(safe + ".jsonl");
    }

    private Path resolveOrMigratePath(String key) {
        Path target = getSessionPath(key);
        if (Files.exists(target)) {
            return target;
        }

        Path oldInSessions = getLegacyNameInSessionsDir(key);
        if (Files.exists(oldInSessions)) {
            try {
                Files.createDirectories(target.getParent());
                Files.move(oldInSessions, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("已迁移会话文件名: {} -> {}", oldInSessions.getFileName(), target.getFileName());
            } catch (Exception e) {
                log.warn("迁移会话文件名失败: key={}", key, e);
                return oldInSessions;
            }
            return target;
        }

        Path legacy = getLegacySessionPath(key);
        if (Files.exists(legacy)) {
            try {
                Files.createDirectories(target.getParent());
                Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("已将会话从旧目录迁移: {} -> {}", legacy, target);
            } catch (Exception e) {
                log.warn("迁移会话目录失败: key={}", key, e);
                return legacy;
            }
            return target;
        }

        return target;
    }

    private static String shortHash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "00000000";
        }
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
