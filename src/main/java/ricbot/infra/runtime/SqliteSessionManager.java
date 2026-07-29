package ricbot.infra.runtime;

import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 基于统一运行时数据库的会话投影实现。 */
public final class SqliteSessionManager implements SessionManager {
    private final SqliteRuntimeStore store;
    // 使用 ConcurrentHashMap 保证多线程环境下缓存操作的安全性和可见性
    private final Map<String, Session> cache = new ConcurrentHashMap<>();

    public SqliteSessionManager(SqliteRuntimeStore store) {
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    @Override
    public Session getOrCreate(String key) {
        String cleanKey = required(key);
        return cache.computeIfAbsent(cleanKey, k -> 
            store.loadSession(k).orElseGet(() -> new Session(k))
        );
    }

    @Override
    public Optional<Session> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        // 首先检查缓存
        Session cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        // 缓存未命中，尝试从数据库加载
        Optional<Session> loaded = store.loadSession(key);
        loaded.ifPresent(session -> cache.put(key, session));
        return loaded;
    }

    @Override
    public void save(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("Session cannot be null");
        }
        store.saveSession(session);
        cache.put(session.getKey(), session);
    }

    @Override
    public void invalidate(String key) {
        if (key != null && !key.isBlank()) {
            cache.remove(key);
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        cache.remove(key);
        store.deleteSession(key);
    }

    @Override
    public List<Map<String, Object>> listSessions() {
        List<Session> sessions = store.listSessions();
        List<Map<String, Object>> result = new ArrayList<>(sessions.size());
        
        for (Session session : sessions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", session.getKey());
            item.put("updated_at", session.getUpdatedAt().toString());
            item.put("message_count", session.getMessages().size());
            result.add(item);
        }
        return List.copyOf(result);
    }

    /**
     * 验证并清理 session key。
     * 如果 key 为 null 或空白字符串，抛出异常。
     */
    private static String required(String key) {
        if (key == null) {
            throw new IllegalArgumentException("session key is required");
        }
        String clean = key.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("session key is required");
        }
        return clean;
    }
}
