package ricbot.domain.note;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.experience.ExperienceEntry;
import ricbot.domain.experience.ExperiencePromoter;
import ricbot.domain.experience.ExperienceStore;
import ricbot.domain.experience.ExperienceType;

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

    @Test
    void promotedPlaybookIsSearchable(@TempDir Path workspace) {
        NoteService noteService = new NoteService(workspace);
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry entry = store.addCandidate(ExperienceEntry.candidate(
                ExperienceType.SUCCESS_PLAYBOOK,
                "Governance promote playbook",
                "Use project playbook promotion for durable verified experience.",
                "When an experience is high value and repeatedly useful.",
                "Validated by V3.8 tests.",
                "task_summary",
                "V3.8",
                List.of("src/main/java/ricbot/domain/experience/ExperiencePromoter.java"),
                List.of("./mvnw -q -Dtest='ricbot.domain.experience.*Test' test"),
                0.9d
        ));
        store.verify(entry.id());
        new ExperiencePromoter(noteService, store).promote(entry.id());

        List<NoteService.SearchResult> results = noteService.search("durable verified experience playbook promotion", 5);

        assertFalse(results.isEmpty());
        assertTrue(results.get(0).entry().path().equals("notes/project/playbooks.md"), results.toString());
    }
}
