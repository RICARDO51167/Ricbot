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

public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    // 初始化 ObjectMapper，用于 JSON 序列化和反序列化，并注册找到的模块（如 JavaTimeModule）
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    // 工作空间根路径
    private final Path workspace;
    // 会话文件存储目录
    private final Path sessionsDir;
    // 旧版本会话文件存储目录（用于迁移）
    private final Path legacySessionsDir;
    // 会话缓存，Key 为会话标识，Value 为 Session 对象
    private final Map<String, Session> cache = new ConcurrentHashMap<>();

    /**
     * 构造函数，初始化 SessionManager
     *
     * @param workspace 工作空间路径
     */
    public SessionManager(Path workspace) {
        // 规范化工作空间路径
        this.workspace = workspace.toAbsolutePath().normalize();
        // 确保会话目录存在并初始化
        this.sessionsDir = HelperUtils.ensureDir(this.workspace.resolve("sessions"));
        // 确保旧会话目录存在并初始化
        this.legacySessionsDir = HelperUtils.ensureDir(this.workspace.resolve("legacy_sessions"));
    }

    /**
     * 获取或创建会话
     *
     * @param key 会话唯一标识
     * @return Session 对象
     */
    public Session getOrCreate(String key) {
        return cache.computeIfAbsent(key, k -> {
            Session session = load(k);
            return session != null ? session : new Session(k);
        });
    }

    /**
     * 保存会话到磁盘
     *
     * @param session 要保存的会话对象
     */
    public void save(Session session) {
        // 获取会话文件路径
        Path path = getSessionPath(session.getKey());

        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new RuntimeException("保存会话失败（创建目录失败）：" + session.getKey(), e);
        }

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            // 构建元数据行
            Map<String, Object> metadataLine = new LinkedHashMap<>();
            metadataLine.put("_type", "metadata"); // 标记类型为元数据
            metadataLine.put("key", session.getKey()); // 会话 Key
            metadataLine.put("created_at", session.getCreatedAt().toString()); // 创建时间
            metadataLine.put("updated_at", session.getUpdatedAt().toString()); // 更新时间
            metadataLine.put("metadata", session.getMetadata()); // 自定义元数据
            metadataLine.put("last_consolidated", session.getLastConsolidated()); // 最后合并索引
            metadataLine.put("message_count", session.getMessages().size());

            // 写入元数据行
            writer.write(MAPPER.writeValueAsString(metadataLine));
            writer.newLine();

            // 遍历消息列表，逐行写入 JSON
            for (Map<String, Object> msg : session.getMessages()) {
                writer.write(MAPPER.writeValueAsString(msg));
                writer.newLine();
            }
        } catch (IOException e) {
            // 抛出运行时异常，包装 IO 错误信息
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

        // 更新缓存
        cache.put(session.getKey(), session);
    }

    /**
     * 使缓存中的会话失效
     *
     * @param key 会话唯一标识
     */
    public void invalidate(String key) {
        // 从缓存中移除指定 Key 的会话
        cache.remove(key);
    }

    /**
     * 列出所有会话简要信息
     *
     * @return 包含会话 Key、更新时间和消息数量的列表
     */
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        // 如果会话目录不存在，返回空列表
        if (!Files.exists(sessionsDir)) {
            return result;
        }

        try (var stream = Files.list(sessionsDir)) {
            // 遍历目录下的所有文件
            for (Path file : stream.toList()) {
                // 只处理 .jsonl 后缀的文件
                if (!file.getFileName().toString().endsWith(".jsonl")) {
                    continue;
                }

                try (BufferedReader reader = Files.newBufferedReader(file)) {
                    // 读取第一行（元数据行）
                    String first = reader.readLine();
                    // 如果文件为空或第一行为空，跳过
                    if (first == null || first.isBlank()) {
                        continue;
                    }

                    // 解析元数据
                    Map<String, Object> metadata = MAPPER.readValue(first, new TypeReference<>() {});
                    // 提取 Key
                    String key = String.valueOf(metadata.getOrDefault("key", ""));
                    // 提取更新时间
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

                    // 构建结果项
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

        // 按更新时间降序排序
        result.sort((a, b) -> String.valueOf(b.get("updated_at")).compareTo(String.valueOf(a.get("updated_at"))));
        return result;
    }

    // =========================================================

    /**
     * 从磁盘加载会话
     *
     * @param key 会话唯一标识
     * @return Session 对象，如果不存在则返回 null
     */
    private Session load(String key) {
        Path path = resolveOrMigratePath(key);

        // 如果最终路径仍不存在，返回 null
        if (!Files.exists(path)) {
            return null;
        }

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            // 初始化消息列表
            List<Map<String, Object>> messages = new ArrayList<>();
            // 初始化元数据
            Map<String, Object> metadata = new LinkedHashMap<>();
            // 初始化时间戳
            Instant createdAt = Instant.now();
            Instant updatedAt = Instant.now();
            // 初始化最后合并索引
            int lastConsolidated = 0;

            String line;
            // 逐行读取文件
            while ((line = reader.readLine()) != null) {
                // 去除首尾空白
                line = line.strip();
                // 跳过空行
                if (line.isEmpty()) {
                    continue;
                }

                // 解析 JSON 行
                Map<String, Object> data = MAPPER.readValue(line, new TypeReference<>() {});
                // 判断是否为元数据行
                if ("metadata".equals(String.valueOf(data.get("_type")))) {
                    // 提取元数据映射
                    metadata = castMap(data.get("metadata"));
                    // 提取创建时间
                    createdAt = parseInstant(String.valueOf(data.getOrDefault("created_at", Instant.now().toString())));
                    // 提取更新时间
                    updatedAt = parseInstant(String.valueOf(data.getOrDefault("updated_at", Instant.now().toString())));
                    // 提取最后合并索引
                    Object lc = data.get("last_consolidated");
                    if (lc instanceof Number n) {
                        lastConsolidated = n.intValue();
                    }
                } else {
                    // 否则视为消息内容
                    messages.add(data);
                }
            }

            // 构建并返回 Session 对象
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

    /**
     * 获取会话文件路径
     *
     * @param key 会话唯一标识
     * @return 文件 Path
     */
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

    /**
     * 获取旧版本会话文件路径
     *
     * @param key 会话唯一标识
     * @return 文件 Path
     */
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


    /**
     * 类型转换辅助方法，将 Object 转换为 Map<String, Object>
     *
     * @param o 待转换对象
     * @return Map<String, Object>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    /**
     * 解析 Instant 时间字符串
     *
     * @param s 时间字符串
     * @return Instant 对象，解析失败则返回当前时间
     */
    private static Instant parseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
