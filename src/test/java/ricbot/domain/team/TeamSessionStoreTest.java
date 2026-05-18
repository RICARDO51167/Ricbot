package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamSessionStoreTest {

    @Test
    void savesAndLoadsSessionWithTasks(@TempDir Path workspace) {
        TeamSessionStore store = new TeamSessionStore(workspace);
        TeamTask task = new TeamTask(null, "team_demo", TeamRole.DEVELOPER, "Implement persistence", TeamTaskState.DONE, "done", List.of(), null, "", null, null);
        TeamSession session = new TeamSession("team_demo", "Persist TeamSession", TeamTaskState.DONE, List.of(task), null, null);

        store.saveSession(session);
        TeamSession loaded = store.loadSession("team_demo");

        assertNotNull(loaded);
        assertEquals("team_demo", loaded.id());
        assertEquals(1, loaded.tasks().size());
        assertTrue(Files.exists(workspace.resolve(".team/team_demo/session.json")));
        assertTrue(Files.exists(workspace.resolve(".team/team_demo/tasks.jsonl")));
        assertTrue(Files.exists(workspace.resolve(".team/team_demo/events.jsonl")));
        assertTrue(Files.exists(workspace.resolve(".team/team_demo/artifacts.jsonl")));
        assertTrue(Files.exists(workspace.resolve(".team/team_demo/whiteboard.md")));
    }

    @Test
    void listsSessionsByUpdatedAt(@TempDir Path workspace) throws Exception {
        TeamSessionStore store = new TeamSessionStore(workspace);
        store.saveSession(new TeamSession("team_old", "Old", TeamTaskState.PLANNING, List.of(), "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"));
        Thread.sleep(5);
        store.saveSession(new TeamSession("team_new", "New", TeamTaskState.PLANNING, List.of(), "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"));

        List<TeamSession> sessions = store.listSessions();

        assertEquals(2, sessions.size());
        assertEquals("team_new", sessions.get(0).id());
    }

    @Test
    void archiveSessionRemovesItFromLatestActive(@TempDir Path workspace) {
        TeamSessionStore store = new TeamSessionStore(workspace);
        store.saveSession(new TeamSession("team_a", "Active", TeamTaskState.PLANNING, List.of(), null, "2026-01-02T00:00:00Z"));
        store.archiveSession("team_a");

        assertTrue(store.isArchived("team_a"));
        assertNull(store.loadLatestActiveSession());
    }

    @Test
    void loadLatestActiveSessionSkipsDoneAndArchived(@TempDir Path workspace) {
        TeamSessionStore store = new TeamSessionStore(workspace);
        store.saveSession(new TeamSession("team_done", "Done", TeamTaskState.DONE, List.of(), null, "2026-01-03T00:00:00Z"));
        store.saveSession(new TeamSession("team_active", "Active", TeamTaskState.REVISING, List.of(), null, "2026-01-02T00:00:00Z"));

        TeamSession latest = store.loadLatestActiveSession();

        assertNotNull(latest);
        assertEquals("team_active", latest.id());
    }

    @Test
    void restoreFromDirectorySupportsExistingV41Layout(@TempDir Path workspace) throws Exception {
        Path dir = workspace.resolve(".team/team_legacy");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("whiteboard.md"), "# legacy whiteboard\n");
        Files.writeString(dir.resolve("events.jsonl"), "");
        Files.writeString(dir.resolve("artifacts.jsonl"), "");

        TeamSession restored = new TeamSessionStore(workspace).restoreFromDirectory(dir);

        assertNotNull(restored);
        assertEquals("team_legacy", restored.id());
        assertEquals(TeamTaskState.PLANNING, restored.state());
    }
}
