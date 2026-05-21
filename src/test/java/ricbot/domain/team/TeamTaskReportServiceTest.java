package ricbot.domain.team;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamTaskReportServiceTest {
    private final TeamTaskReportService service = new TeamTaskReportService(null);

    @Test
    void noStepsIsNotStartedWithUnknownHealth() {
        TeamTaskReport report = service.buildReport("team_1", summary(0, 0, 0, "", List.of("no implementation steps found")), "goal");

        assertEquals(TeamTaskStatus.NOT_STARTED, report.status());
        assertEquals(TeamTaskHealth.UNKNOWN, report.health());
        assertTrue(report.suggestedNextActions().toString().contains("/team plan-steps"));
    }

    @Test
    void warningsOnlyProduceWarningHealth() {
        TeamTaskReport report = service.buildReport("team_1", summary(1, 0, 0, "", List.of("review warning")), "goal");

        assertEquals(TeamTaskStatus.RUNNING, report.status());
        assertEquals(TeamTaskHealth.WARNING, report.health());
    }

    @Test
    void failedStepProducesFailedCritical() {
        TeamTaskReport report = service.buildReport("team_1", summary(2, 1, 1, "", List.of()), "goal");

        assertEquals(TeamTaskStatus.FAILED, report.status());
        assertEquals(TeamTaskHealth.CRITICAL, report.health());
        assertTrue(report.suggestedNextActions().toString().contains("failed implementation steps"));
    }

    @Test
    void allCompletedWithNoWarningsIsHealthy() {
        TeamTaskReport report = service.buildReport("team_1", summary(2, 2, 0, "", List.of()), "goal");

        assertEquals(TeamTaskStatus.COMPLETED, report.status());
        assertEquals(TeamTaskHealth.HEALTHY, report.health());
    }

    @Test
    void verifierFailureIsCritical() {
        TeamTaskReport report = service.buildReport("team_1", summary(2, 2, 0, "REJECT", List.of()), "goal");

        assertEquals(TeamTaskHealth.CRITICAL, report.health());
        assertTrue(report.suggestedNextActions().toString().contains("latest ChangeSet"));
    }

    @Test
    void pendingStepsProduceRunningStatus() {
        TeamTaskReport report = service.buildReport("team_1", summary(3, 1, 0, "", List.of()), "goal");

        assertEquals(TeamTaskStatus.RUNNING, report.status());
        assertEquals(2, report.pendingSteps());
        assertTrue(report.suggestedNextActions().toString().contains("/team apply-step"));
    }

    @Test
    void blockingWarningsProduceBlockedStatus() {
        TeamTaskReport report = service.buildReport("team_1", summary(1, 0, 0, "", List.of("approval required")), "goal");

        assertEquals(TeamTaskStatus.BLOCKED, report.status());
        assertEquals(TeamTaskHealth.WARNING, report.health());
    }

    private StepAuditSummary summary(int totalSteps, int applied, int failed, String verifier, List<String> warnings) {
        return new StepAuditSummary("task_1", "team_1", totalSteps,
                totalSteps, 0, Math.max(0, totalSteps - applied - failed), 0, applied, 0, failed,
                0, applied, List.of(), "", verifier, applied > 0 ? "STEP_TOOL_APPLIED" : "",
                "2026-05-21T00:00:00Z", "2026-05-21T00:00:01Z", 1000L, applied,
                List.of(), failed > 0 ? List.of("step_failed") : List.of(),
                Math.max(0, totalSteps - applied - failed) > 0 ? List.of("step_next") : List.of(),
                Math.max(0, totalSteps - applied - failed) > 0 ? "step_next" : "",
                StepAuditHealth.NEEDS_REVIEW, warnings);
    }
}
