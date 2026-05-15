package ricbot.domain.experience;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.note.NoteService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperiencePromoterTest {

    @Test
    void testPolicyPromotesToProjectTestPolicy(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry entry = verify(store, experience(ExperienceType.TEST_POLICY, "Run focused tests", 0.8d));

        ExperienceEntry promoted = new ExperiencePromoter(new NoteService(workspace), store).promote(entry.id());

        assertEquals("notes/project/test_policy.md", promoted.promotedTo());
        String content = Files.readString(workspace.resolve("notes/project/test_policy.md"));
        assertTrue(content.contains("experience-id: " + entry.id()), content);
        assertTrue(content.contains("Run focused tests"), content);
    }

    @Test
    void securityRulePromotesToSecurityPolicy(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry entry = verify(store, experience(ExperienceType.SECURITY_RULE, "Avoid unsafe shell", 0.9d));

        new ExperiencePromoter(new NoteService(workspace), store).promote(entry.id());

        assertTrue(Files.readString(workspace.resolve("notes/project/security_policy.md")).contains("Avoid unsafe shell"));
    }

    @Test
    void promoteIsIdempotentForSameExperience(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry entry = verify(store, experience(ExperienceType.TEST_POLICY, "Run focused tests", 0.8d));
        ExperiencePromoter promoter = new ExperiencePromoter(new NoteService(workspace), store);

        promoter.promote(entry.id());
        promoter.promote(entry.id());

        String content = Files.readString(workspace.resolve("notes/project/test_policy.md"));
        assertEquals(1, count(content, "experience-id: " + entry.id()));
    }

    @Test
    void candidateCannotPromote(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry entry = store.addCandidate(experience(ExperienceType.TEST_POLICY, "Candidate tests", 0.8d));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ExperiencePromoter(new NoteService(workspace), store).promote(entry.id())
        );

        assertTrue(error.getMessage().contains("must be verified"), error.getMessage());
    }

    @Test
    void archivedCannotPromote(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry entry = verify(store, experience(ExperienceType.TEST_POLICY, "Archived tests", 0.8d));
        store.archive(entry.id());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ExperiencePromoter(new NoteService(workspace), store).promote(entry.id())
        );

        assertTrue(error.getMessage().contains("only verified"), error.getMessage());
    }

    private ExperienceEntry verify(ExperienceStore store, ExperienceEntry entry) {
        ExperienceEntry added = store.addCandidate(entry);
        return store.verify(added.id());
    }

    private ExperienceEntry experience(ExperienceType type, String title, double confidence) {
        return ExperienceEntry.candidate(
                type,
                title,
                "Use this policy when changing Ricbot experience governance.",
                "When editing experience governance code.",
                "Validated by focused tests.",
                "task_summary",
                "V3.8",
                List.of("src/main/java/ricbot/domain/experience/ExperienceStore.java"),
                List.of("./mvnw -q -Dtest='ricbot.domain.experience.*Test' test"),
                confidence
        );
    }

    private int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
