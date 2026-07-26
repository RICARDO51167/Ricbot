package ricbot.infra.runtime;

import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Session projection backed by the unified runtime database. */
public final class SqliteSessionManager extends SessionManager {
    private final SqliteRuntimeStore store;
    private final Map<String, Session> cache = new ConcurrentHashMap<>();

    public SqliteSessionManager(Path workspace, SqliteRuntimeStore store) {
        super(workspace, false);
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    @Override public Session getOrCreate(String key) {
        return cache.computeIfAbsent(required(key), value -> store.loadSession(value).orElseGet(() -> new Session(value)));
    }

    @Override public Optional<Session> find(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        Session cached = cache.get(key);
        if (cached != null) return Optional.of(cached);
        Optional<Session> loaded = store.loadSession(key);
        loaded.ifPresent(session -> cache.put(key, session));
        return loaded;
    }

    @Override public void save(Session session) {
        store.saveSession(session);
        cache.put(session.getKey(), session);
    }

    @Override public void invalidate(String key) { cache.remove(key); }

    @Override public void delete(String key) {
        cache.remove(key);
        store.deleteSession(key);
    }

    @Override public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Session session : store.listSessions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", session.getKey());
            item.put("updated_at", session.getUpdatedAt().toString());
            item.put("message_count", session.getMessages().size());
            result.add(item);
        }
        return List.copyOf(result);
    }

    private static String required(String key) {
        String clean = key != null ? key.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("session key is required");
        return clean;
    }
}
