package ricbot.domain.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Current SQLite-backed session storage contract. */
public interface SessionManager {
    Session getOrCreate(String key);

    Optional<Session> find(String key);

    void save(Session session);

    void invalidate(String key);

    void delete(String key);

    List<Map<String, Object>> listSessions();
}
