package ricbot.core.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class SessionManagerTest {

    @Test
    void saveAndReload_isConsistent(@TempDir Path workspace) throws Exception {
        SessionManager sm = new SessionManager(workspace);

        Session s = sm.getOrCreate("cli:test");
        s.addMessage("user", "hi");
        s.addMessage("assistant", "hello");
        sm.save(s);

        Path sessionsDir = workspace.resolve("sessions");
        assertTrue(Files.exists(sessionsDir));
        assertTrue(Files.list(sessionsDir).anyMatch(p -> p.getFileName().toString().endsWith(".jsonl")));

        sm.invalidate("cli:test");
        Session reloaded = sm.getOrCreate("cli:test");
        assertEquals(2, reloaded.getMessages().size());
        assertEquals("user", String.valueOf(reloaded.getMessages().get(0).get("role")));
        assertEquals("assistant", String.valueOf(reloaded.getMessages().get(1).get("role")));
    }
}

