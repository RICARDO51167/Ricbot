package ricbot.domain.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.infra.persistence.FileSharedStateStore;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SharedSessionManagerTest {

    @Test
    void roundTripsAndListsSessionsAcrossManagers(@TempDir Path workspace) {
        SharedSessionManager first = new SharedSessionManager(workspace, new FileSharedStateStore(workspace));
        SharedSessionManager second = new SharedSessionManager(workspace, new FileSharedStateStore(workspace));
        Session session = first.getOrCreate("cli:shared");
        session.addMessage("user", "hello");
        first.save(session);

        Session loaded = second.find("cli:shared").orElseThrow();

        assertEquals(1, loaded.getMessages().size());
        assertEquals("cli:shared", second.listSessions().get(0).get("key"));
        assertEquals(1, second.listSessions().get(0).get("message_count"));
        second.delete("cli:shared");
        first.invalidate("cli:shared");
        assertTrue(first.find("cli:shared").isEmpty());
    }

    @Test
    void rejectsLostUpdatesUsingCompareAndSet(@TempDir Path workspace) {
        SharedSessionManager first = new SharedSessionManager(workspace, new FileSharedStateStore(workspace));
        Session initial = first.getOrCreate("session");
        initial.addMessage("user", "initial");
        first.save(initial);

        SharedSessionManager second = new SharedSessionManager(workspace, new FileSharedStateStore(workspace));
        Session stale = second.find("session").orElseThrow();
        initial.addMessage("assistant", "winner");
        first.save(initial);
        stale.addMessage("assistant", "stale");

        assertThrows(IllegalStateException.class, () -> second.save(stale));
    }
}
