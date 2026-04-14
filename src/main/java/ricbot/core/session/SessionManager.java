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

    // 初始化 ObjectMapper，用于 JSON 序列化和反序列化，并注册找到的模块（如 JavaTimeModule）
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    // 工作空间根路径
    private final Path workspace;
    // 会话文件存储目录
    private final Path sessionsDir;
    // 旧版本会话文件存储目录（用于迁移）
    private final Path legacySessionsDir;
    // 会话缓存，Key 为会话标识，Value 为 Session 对象
    private final Map<String, Session> cache = new HashMap<>();

    /**
     * 构造函数，初始化 SessionManager
     *
     * @param workspace 工作空间路径
     */
    public SessionManager(Path workspace) {
        // 规范化工作空间路径
        this.workspace = workspace.toAbsolutePath().normalize();
        // 确保会话目录存在并初始化
        this.sessionsDir = ensureDir(this.workspace.resolve("sessions"));
        // 确保旧会话目录存在并初始化
        this.legacySessionsDir = ensureDir(this.workspace.resolve("legacy_sessions"));
    }

    /**
     * 获取或创建会话
     *
     * @param key 会话唯一标识
     * @return Session 对象
     */
    public Session getOrCreate(String key) {
        // 如果缓存中存在，直接返回
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        // 尝试从磁盘加载会话
        Session session = load(key);
        // 如果磁盘上不存在，则创建新会话
        if (session == null) {
            session = new Session(key);
        }
        // 放入缓存
        cache.put(key, session);
        return session;
    }

    /**
     * 保存会话到磁盘
     *
     * @param session 要保存的会话对象
     */
    public void save(Session session) {
        // 获取会话文件路径
        Path path = getSessionPath(session.getKey());

        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            // 构建元数据行
            Map<String, Object> metadataLine = new LinkedHashMap<>();
            metadataLine.put("_type", "metadata"); // 标记类型为元数据
            metadataLine.put("key", session.getKey()); // 会话 Key
            metadataLine.put("created_at", session.getCreatedAt().toString()); // 创建时间
            metadataLine.put("updated_at", session.getUpdatedAt().toString()); // 更新时间
            metadataLine.put("metadata", session.getMetadata()); // 自定义元数据
            metadataLine.put("last_consolidated", session.getLastConsolidated()); // 最后合并索引

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
            throw new RuntimeException("Failed to save session " + session.getKey(), e);
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
                    // 计算消息数量（剩余行数）
                    int count = 0;
                    while (reader.readLine() != null) {
                        count++;
                    }

                    // 构建结果项
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", key);
                    item.put("updated_at", updatedAt);
                    item.put("message_count", count);
                    result.add(item);
                } catch (Exception ignored) {
                    // 忽略单个文件解析错误
                }
            }
        } catch (IOException ignored) {
            // 忽略目录遍历错误
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
        // 获取当前会话文件路径
        Path path = getSessionPath(key);
        // 如果当前路径不存在，检查旧路径
        if (!Files.exists(path)) {
            Path legacy = getLegacySessionPath(key);
            if (Files.exists(legacy)) {
                try {
                    // 创建父目录
                    Files.createDirectories(path.getParent());
                    // 移动旧文件到新位置
                    Files.move(legacy, path, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Migrated session " + key + " from legacy path");
                } catch (Exception e) {
                    // 打印迁移失败错误
                    System.err.println("Failed to migrate session " + key + ": " + e.getMessage());
                }
            }
        }

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
            // 打印加载失败错误
            System.err.println("Failed to load session " + key + ": " + e.getMessage());
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
        // 替换非法字符并生成安全文件名
        String safe = safeFilename(key.replace(":", "_"));
        return sessionsDir.resolve(safe + ".jsonl");
    }

    /**
     * 获取旧版本会话文件路径
     *
     * @param key 会话唯一标识
     * @return 文件 Path
     */
    private Path getLegacySessionPath(String key) {
        // 替换非法字符并生成安全文件名
        String safe = safeFilename(key.replace(":", "_"));
        return legacySessionsDir.resolve(safe + ".jsonl");
    }

    /**
     * 确保目录存在
     *
     * @param path 目录路径
     * @return 目录 Path
     */
    private static Path ensureDir(Path path) {
        try {
            // 创建目录（包括父目录）
            Files.createDirectories(path);
            return path;
        } catch (IOException e) {
            // 抛出运行时异常
            throw new RuntimeException(e);
        }
    }

    /**
     * 生成安全的文件名，替换非法字符
     *
     * @param name 原始名称
     * @return 安全文件名
     */
    private static String safeFilename(String name) {
        // 替换文件系统非法字符为下划线，并去除首尾空格
        return name.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
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
