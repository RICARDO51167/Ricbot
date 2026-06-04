package ricbot.integration.api.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleEventInfrastructureTest {
    @Test
    void consoleEventStore_appendsAndListsBySession(@TempDir Path workspace) {
        JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
        store.append(event("evt-1", "session-a", "run", "run_submit"));
        store.append(event("evt-2", "session-b", "run", "run_submit"));

        List<ConsoleEvent> events = store.listBySession("session-a", "", "", 20);

        assertEquals(1, events.size());
        assertEquals("evt-1", events.get(0).id());
        assertTrue(java.nio.file.Files.isRegularFile(store.eventFile()));
    }

    @Test
    void consoleEventStore_filtersByCategory(@TempDir Path workspace) {
        JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
        store.append(event("evt-1", "session-a", "run", "run_submit"));
        store.append(event("evt-2", "session-a", "approval", "approval_reject"));

        List<ConsoleEvent> events = store.listBySession("session-a", "approval", "", 20);

        assertEquals(1, events.size());
        assertEquals("evt-2", events.get(0).id());
    }

    @Test
    void consoleEventStore_respectsAfterCursor(@TempDir Path workspace) {
        JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
        store.append(event("evt-1", "session-a", "run", "run_submit"));
        store.append(event("evt-2", "session-a", "run", "run_queued"));
        store.append(event("evt-3", "session-a", "run", "run_started"));

        List<ConsoleEvent> events = store.listBySession("session-a", "run", "evt-1", 20);

        assertEquals(List.of("evt-2", "evt-3"), events.stream().map(ConsoleEvent::id).toList());
    }

    @Test
    void consoleEventBus_publishPersistsAndNotifies(@TempDir Path workspace) {
        JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
        ConsoleEventBus bus = new ConsoleEventBus(store);
        List<ConsoleEvent> received = new ArrayList<>();
        bus.subscribe("session-a", received::add);

        bus.publish(event("evt-1", "session-a", "run", "run_submit"));

        assertEquals(1, received.size());
        assertEquals(1, store.listBySession("session-a", "", "", 20).size());
    }

    @Test
    void consoleEventBus_unsubscribeStopsNotifications(@TempDir Path workspace) {
        JsonlConsoleEventStore store = new JsonlConsoleEventStore(workspace);
        ConsoleEventBus bus = new ConsoleEventBus(store);
        List<ConsoleEvent> received = new ArrayList<>();
        ConsoleEventBus.Listener listener = received::add;
        bus.subscribe("session-a", listener);
        bus.unsubscribe("session-a", listener);

        bus.publish(event("evt-1", "session-a", "run", "run_submit"));

        assertTrue(received.isEmpty());
        assertEquals(1, store.listBySession("session-a", "", "", 20).size());
    }

    private static ConsoleEvent event(String id, String sessionId, String category, String name) {
        return new ConsoleEvent(
                id,
                sessionId,
                "run-1",
                category + "_event",
                name,
                category,
                "INFO",
                "2026-06-04T00:00:00Z",
                name,
                name,
                "console",
                "console_event_store",
                Map.of("name", name)
        );
    }
}
