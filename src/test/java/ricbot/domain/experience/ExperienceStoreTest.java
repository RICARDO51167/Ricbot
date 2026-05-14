package ricbot.domain.experience;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceStoreTest {

    @Test
    void addCandidateAndListCandidates(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);

        ExperienceEntry added = store.addCandidate(candidate("Run filesystem tests"));

        assertTrue(Files.exists(workspace.resolve("experience").resolve("candidates.jsonl")));
        assertEquals(1, store.listCandidates().size());
        assertEquals(added.id(), store.listCandidates().get(0).id());
    }

    @Test
    void verifyPromotesCandidateToVerifiedFile(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry added = store.addCandidate(candidate("Run agent tests"));

        ExperienceEntry verified = store.verify(added.id());

        assertEquals(ExperienceStatus.VERIFIED, verified.status());
        assertEquals(0, store.listCandidates().size());
        assertTrue(Files.readString(store.verifiedFile()).contains(added.id()));
        assertEquals(ExperienceStatus.VERIFIED, store.find(added.id()).status());
    }

    @Test
    void rejectWritesRejectedFile(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry added = store.addCandidate(candidate("Reject noisy rule"));

        ExperienceEntry rejected = store.reject(added.id());

        assertEquals(ExperienceStatus.REJECTED, rejected.status());
        assertEquals(0, store.listCandidates().size());
        assertTrue(Files.readString(store.rejectedFile()).contains(added.id()));
    }

    @Test
    void addCandidateDeduplicatesSimilarExperiences(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);

        ExperienceEntry first = store.addCandidate(candidate("Run filesystem tests"));
        ExperienceEntry second = store.addCandidate(candidate("Run filesystem tests"));

        assertEquals(first.id(), second.id());
        assertEquals(1, store.listCandidates().size());
        assertNotNull(store.find(first.id()));
    }

    private ExperienceEntry candidate(String title) {
        return ExperienceEntry.candidate(
                ExperienceType.TEST_POLICY,
                title,
                "Run targeted tests after changing filesystem tools.",
                "When changing src/main/java/ricbot/tool/filesystem.",
                "Task summary suggested targeted tests.",
                "task_summary",
                "V3.5",
                List.of("src/main/java/ricbot/tool/filesystem/WriteFileTool.java"),
                List.of("./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test"),
                0.7d
        );
    }
}
