package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamWhiteboardTest {

    @Test
    void writesAndReadsWhiteboardEventsAndArtifacts(@TempDir Path workspace) throws Exception {
        TeamWhiteboard whiteboard = new TeamWhiteboard(workspace, "team_demo");
        TeamEvent event = TeamEvent.of("team_demo", "task_demo", TeamRole.DEVELOPER, TeamEvent.WORKER_RESULT_SUBMITTED, "implemented summary");
        TeamArtifact artifact = TeamArtifact.of("task_demo", "src/main/java/Demo.java", "changed one method");

        whiteboard.appendNote("Leader note: keep only summaries on the whiteboard.");
        whiteboard.appendEvent(event);
        whiteboard.appendArtifact(artifact);

        assertTrue(Files.exists(whiteboard.whiteboardPath()));
        assertTrue(Files.exists(whiteboard.eventsPath()));
        assertTrue(Files.exists(whiteboard.artifactsPath()));
        assertTrue(whiteboard.readSummary().contains("Leader note"));
        assertEquals(1, whiteboard.readEvents().size());
        assertEquals(TeamEvent.WORKER_RESULT_SUBMITTED, whiteboard.readEvents().get(0).type());
        assertEquals(TeamEvent.CURRENT_SCHEMA_VERSION, whiteboard.readEvents().get(0).schemaVersion());
        assertEquals("DEVELOPER", whiteboard.readEvents().get(0).actor());
        assertEquals(1, whiteboard.readArtifacts().size());
        assertEquals("src/main/java/Demo.java", whiteboard.readArtifacts().get(0).path());
        assertEquals(".team/team_demo/whiteboard.md", whiteboard.relativeWhiteboardPath());
    }

    @Test
    void readsTeamEventWrittenBeforeSchemaVersionWasAdded() {
        TeamEvent event = TeamEvent.fromMap(Map.of(
                "eventId", "legacy-team-event",
                "sessionId", "team_demo",
                "type", TeamEvent.TEAM_STARTED,
                "createdAt", "2025-01-01T00:00:00Z"
        ));

        assertEquals(TeamEvent.CURRENT_SCHEMA_VERSION, event.schemaVersion());
        assertEquals("legacy-team-event", event.eventId());
    }
}
