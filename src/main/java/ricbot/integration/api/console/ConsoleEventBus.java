package ricbot.integration.api.console;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class ConsoleEventBus {
    public interface Listener {
        void onEvent(ConsoleEvent event);
    }

    private final ConsoleEventStore store;
    private final Map<String, Set<Listener>> listeners = new ConcurrentHashMap<>();

    public ConsoleEventBus(ConsoleEventStore store) {
        this.store = store;
    }

    public void publish(ConsoleEvent event) {
        if (event == null) {
            return;
        }
        if (store != null) {
            store.append(event);
        }
        notifyListeners(event);
    }

    public void subscribe(String sessionId, Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.computeIfAbsent(key(sessionId), ignored -> new CopyOnWriteArraySet<>()).add(listener);
    }

    public void unsubscribe(String sessionId, Listener listener) {
        if (listener == null) {
            return;
        }
        Set<Listener> sessionListeners = listeners.get(key(sessionId));
        if (sessionListeners == null) {
            return;
        }
        sessionListeners.remove(listener);
        if (sessionListeners.isEmpty()) {
            listeners.remove(key(sessionId), sessionListeners);
        }
    }

    public List<ConsoleEvent> listBySession(String sessionId, String category, String after, int limit) {
        return store != null ? store.listBySession(sessionId, category, after, limit) : List.of();
    }

    public List<ConsoleEvent> listByRun(String runId, String category, String after, int limit) {
        return store != null ? store.listByRun(runId, category, after, limit) : List.of();
    }

    public List<ConsoleEvent> listAll(int limit) {
        return store != null ? store.listAll(limit) : List.of();
    }

    public ConsoleEventStore store() {
        return store;
    }

    private void notifyListeners(ConsoleEvent event) {
        for (Listener listener : listeners.getOrDefault(key(event.sessionId()), Set.of())) {
            try {
                listener.onEvent(event);
            } catch (Exception ignored) {
            }
        }
    }

    private static String key(String sessionId) {
        return sessionId != null ? sessionId.trim() : "";
    }
}
