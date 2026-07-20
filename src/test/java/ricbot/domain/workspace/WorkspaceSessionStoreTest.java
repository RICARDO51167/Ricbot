package ricbot.domain.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSessionStoreTest {

    @Test
    void saveLoadListAndLoadActive(@TempDir Path workspace) {
        WorkspaceSessionStore store = new WorkspaceSessionStore(workspace);
        WorkspaceSession active = store.save(new WorkspaceSession(
                "workspace_active",
                WorkspaceBackendType.LOCAL,
                workspace.toString(),
                workspace.toString(),
                "",
                "local goal",
                WorkspaceSessionStatus.ACTIVE,
                null,
                null,
                Map.of("mode", "local")
        ));
        WorkspaceSession closed = store.save(new WorkspaceSession(
                "workspace_closed",
                WorkspaceBackendType.LOCAL,
                workspace.toString(),
                workspace.toString(),
                "",
                "closed goal",
                WorkspaceSessionStatus.ACTIVE,
                null,
                null,
                Map.of()
        ));
        store.close(closed.id());

        assertEquals(active.id(), store.load(active.id()).id());
        assertEquals(2, store.list().size());
        assertEquals(1, store.loadActive().size());
        assertEquals(active.id(), store.loadActive().get(0).id());
        assertTrue(Files.exists(workspace.resolve(".workspaces").resolve(active.id()).resolve("session.json")));
        assertTrue(Files.exists(workspace.resolve(".workspaces").resolve("sessions.jsonl")));
    }
}
