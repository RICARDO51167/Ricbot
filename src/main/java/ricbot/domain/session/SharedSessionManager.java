package ricbot.domain.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.persistence.SharedStateStore;
import ricbot.infra.persistence.SharedValue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Session manager that uses shared CAS persistence instead of process-local JSONL files. */
public final class SharedSessionManager extends SessionManager {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String NAMESPACE = "agent-sessions";

    private final SharedStateStore store;
    private final Map<String, Session> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> versions = new ConcurrentHashMap<>();

    public SharedSessionManager(Path workspace, SharedStateStore store) {
        super(workspace);
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    @Override
    public Session getOrCreate(String key) {
        String clean = required(key);
        return cache.computeIfAbsent(clean, value -> read(value).orElseGet(() -> new Session(value)));
    }

    @Override
    public Optional<Session> find(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        String clean = key.trim();
        Session cached = cache.get(clean);
        if (cached != null) return Optional.of(cached);
        Optional<Session> loaded = read(clean);
        loaded.ifPresent(value -> cache.put(clean, value));
        return loaded;
    }

    @Override
    public void save(Session session) {
        java.util.Objects.requireNonNull(session, "session");
        String key = required(session.getKey());
        long expected = versions.getOrDefault(key, SharedStateStore.MUST_NOT_EXIST);
        SharedValue saved = store.put(NAMESPACE, key, write(session), expected);
        versions.put(key, saved.version());
        cache.put(key, session);
    }

    @Override
    public void invalidate(String key) {
        if (key == null) return;
        cache.remove(key);
        versions.remove(key);
    }

    @Override
    public void delete(String key) {
        String clean = required(key);
        Optional<SharedValue> current = store.get(NAMESPACE, clean);
        current.ifPresent(value -> store.delete(NAMESPACE, clean, value.version()));
        cache.remove(clean);
        versions.remove(clean);
    }

    @Override
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SharedValue value : store.list(NAMESPACE)) {
            Session session = decode(value, value.key());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", session.getKey());
            item.put("updated_at", session.getUpdatedAt().toString());
            item.put("message_count", session.getMessages().size());
            result.add(item);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("updated_at")),
                Comparator.reverseOrder()));
        return List.copyOf(result);
    }

    private Optional<Session> read(String key) {
        Optional<SharedValue> value = store.get(NAMESPACE, key);
        value.ifPresent(found -> versions.put(key, found.version()));
        return value.map(found -> decode(found, key));
    }

    private static Session decode(SharedValue value, String key) {
        try {
            Session session = MAPPER.readValue(value.content(), Session.class);
            if (!key.equals(session.getKey())) throw new IllegalStateException("session identity mismatch");
            return session;
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("failed to deserialize shared session", e);
        }
    }

    private static byte[] write(Session session) {
        try {
            return MAPPER.writeValueAsBytes(session);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize shared session", e);
        }
    }

    private static String required(String key) {
        String clean = key != null ? key.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException("session key is required");
        return clean;
    }
}
