package ricbot.domain.experience;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceSkillPromoterTest {

    @Test
    void verifiedExperiencePromotesToGeneratedSkill(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry verified = store.verify(store.addCandidate(experience("Filesystem Safety Rule")).id());

        ExperienceSkillPromoter.PromotionResult result = new ExperienceSkillPromoter(workspace, store).promote(verified.id());

        assertTrue(result.created());
        assertEquals(verified.id(), result.sourceExperienceId());
        assertTrue(result.skillPath().startsWith(workspace.resolve("skills").resolve("generated")));
        String markdown = Files.readString(result.skillPath());
        assertTrue(markdown.contains("source: experience"), markdown);
        assertTrue(markdown.contains("source_experience_id: " + verified.id()), markdown);
        assertTrue(markdown.contains("# 来源经验"), markdown);
    }

    @Test
    void candidateExperienceIsRejected(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry candidate = store.addCandidate(experience("Candidate Skill"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ExperienceSkillPromoter(workspace, store).promote(candidate.id()));

        assertTrue(error.getMessage().contains("only VERIFIED experience"), error.getMessage());
    }

    @Test
    void rejectedExperienceIsRejected(@TempDir Path workspace) {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry candidate = store.addCandidate(experience("Rejected Skill"));
        ExperienceEntry rejected = store.reject(candidate.id());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ExperienceSkillPromoter(workspace, store).promote(rejected.id()));

        assertTrue(error.getMessage().contains("status=REJECTED"), error.getMessage());
    }

    @Test
    void titleWithSpecialCharactersUsesSafeFilename(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry verified = store.verify(store.addCandidate(experience("../Unsafe Skill: Write/File?")).id());

        ExperienceSkillPromoter.PromotionResult result = new ExperienceSkillPromoter(workspace, store).promote(verified.id());

        assertEquals("unsafe-skill-write-file", result.skillName());
        assertEquals("unsafe-skill-write-file.md", result.skillPath().getFileName().toString());
        assertTrue(Files.exists(result.skillPath()));
    }

    @Test
    void existingSkillIsNotOverwrittenByDefault(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry verified = store.verify(store.addCandidate(experience("Existing Skill")).id());
        ExperienceSkillPromoter promoter = new ExperienceSkillPromoter(workspace, store);
        ExperienceSkillPromoter.PromotionResult first = promoter.promote(verified.id());
        Files.writeString(first.skillPath(), "manual skill");

        ExperienceSkillPromoter.PromotionResult second = promoter.promote(verified.id());

        assertFalse(second.created());
        assertTrue(second.alreadyExists());
        assertEquals("manual skill", Files.readString(first.skillPath()));
    }

    @Test
    void incompleteFieldsStillGenerateSkill(@TempDir Path workspace) throws Exception {
        ExperienceStore store = new ExperienceStore(workspace);
        ExperienceEntry incomplete = ExperienceEntry.candidate(
                ExperienceType.PROJECT_CONVENTION,
                "",
                "",
                "",
                "",
                "test",
                "missing-fields",
                List.of(),
                List.of(),
                0.8d
        );
        ExperienceEntry verified = store.verify(store.addCandidate(incomplete).id());

        ExperienceSkillPromoter.PromotionResult result = new ExperienceSkillPromoter(workspace, store).promote(verified.id());

        assertTrue(result.created());
        String markdown = Files.readString(result.skillPath());
        assertTrue(markdown.contains("该技能由已验证经验生成。"), markdown);
        assertTrue(markdown.contains("未记录 evidence。"), markdown);
    }

    private ExperienceEntry experience(String title) {
        return ExperienceEntry.candidate(
                ExperienceType.TEST_POLICY,
                title,
                "Run targeted tests after changing filesystem tools.",
                "When changing filesystem tools.",
                "Command test evidence.",
                "task_summary",
                title,
                List.of("src/main/java/ricbot/tool/filesystem/WriteFileTool.java"),
                List.of("./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test"),
                0.9d
        );
    }
}
