package ricbot.domain.experience;

import org.junit.jupiter.api.Test;
import ricbot.domain.agent.TaskSummaryService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceExtractorTest {

    @Test
    void suggestedTestsBecomeTestPolicy() {
        List<ExperienceEntry> entries = new ExperienceExtractor().extract(summary(
                List.of(),
                List.of(),
                List.of("./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test"),
                List.of()
        ));

        assertTrue(entries.stream().anyMatch(e -> e.type() == ExperienceType.TEST_POLICY
                && e.suggestedTests().contains("./mvnw -q -Dtest='ricbot.tool.filesystem.*Test' test")));
    }

    @Test
    void blockersBecomeFailureLessons() {
        List<ExperienceEntry> entries = new ExperienceExtractor().extract(summary(
                List.of("index missing"),
                List.of(),
                List.of(),
                List.of()
        ));

        assertTrue(entries.stream().anyMatch(e -> e.type() == ExperienceType.FAILURE_LESSON
                && e.content().contains("index missing")));
    }

    @Test
    void securityDiffReviewBecomesSecurityRule() {
        List<ExperienceEntry> entries = new ExperienceExtractor().extract(summary(
                List.of(),
                List.of("src/main/java/ricbot/domain/security/ApprovalService.java — security-sensitive code changed [risk=HIGH]"),
                List.of("./mvnw -q -Dtest='ricbot.domain.security.*Test' test"),
                List.of()
        ));

        assertTrue(entries.stream().anyMatch(e -> e.type() == ExperienceType.SECURITY_RULE
                && e.evidence().contains("ApprovalService")));
    }

    @Test
    void keyDecisionCanBecomeSuccessPlaybook() {
        List<ExperienceEntry> entries = new ExperienceExtractor().extract(summary(
                List.of(),
                List.of(),
                List.of(),
                List.of("Use explicit /experience extract to avoid context pollution")
        ));

        assertTrue(entries.stream().anyMatch(e -> e.type() == ExperienceType.SUCCESS_PLAYBOOK
                && e.content().contains("avoid context pollution")));
    }

    private TaskSummaryService.TaskSummary summary(
            List<String> blockers,
            List<String> diffReviews,
            List<String> suggestedTests,
            List<String> keyDecisions
    ) {
        return new TaskSummaryService.TaskSummary(
                "V3.5 experience extraction",
                List.of("src/main/java/ricbot/domain/experience/ExperienceExtractor.java"),
                keyDecisions,
                List.of(),
                blockers,
                List.of(),
                List.of(),
                diffReviews,
                suggestedTests,
                List.of(),
                ""
        );
    }
}
