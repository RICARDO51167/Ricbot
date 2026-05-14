package ricbot.domain.note;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteServiceTest {

    @Test
    void taskNotesAreSearchable(@TempDir Path workspace) {
        NoteService noteService = new NoteService(workspace);
        noteService.create(
                "Task Summary - searchable task note",
                "tasks",
                "task_state",
                "Changed Files\n- src/main/java/ricbot/domain/agent/AgentCommands.java\n\nSuggested Tests\n- ./mvnw -q test",
                List.of("task-summary", "agent")
        );

        List<NoteService.SearchResult> results = noteService.search("AgentCommands suggested tests", 5);

        assertFalse(results.isEmpty());
        assertTrue(results.get(0).entry().path().startsWith("notes/tasks/"));
    }
}
