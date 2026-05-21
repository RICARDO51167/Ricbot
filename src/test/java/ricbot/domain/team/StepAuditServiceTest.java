package ricbot.domain.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepAuditServiceTest {

    @Test
    void appendListAndRenderTimeline(@TempDir Path workspace) {
        StepAuditService service = new StepAuditService(workspace);
        StepAuditRecord created = record("step_1", "task_1", "team_1", StepAuditEventType.STEP_CREATED, "", "READY");
        StepAuditRecord approval = new StepAuditRecord(null, "step_1", "task_1", "team_1",
                StepAuditEventType.STEP_APPROVAL_REQUIRED, "READY", "APPROVAL_REQUIRED",
                "approval required", "approval_1", "edit_file", "", "", "", "", null, Map.of());

        service.append(created);
        service.append(approval);

        assertEquals(2, service.listByStep("step_1").size());
        assertEquals(2, service.listByTask("task_1").size());
        StepAuditSummary summary = service.summarizeTask("task_1");
        assertEquals(1, summary.approvalRequiredCount());
        assertEquals(StepAuditHealth.NEEDS_REVIEW, summary.auditHealth());
        assertTrue(service.renderStepTimeline("step_1").contains("STEP_APPROVAL_REQUIRED"));
        assertTrue(service.renderTaskAudit("task_1").contains("task step audit"));
        assertTrue(service.renderCompactTaskAudit("task_1").contains("auditHealth=NEEDS_REVIEW"));
        assertTrue(service.renderJsonTaskAudit("task_1").contains("\"summary\""));
        assertTrue(Files.exists(workspace.resolve(".team").resolve("team_1").resolve("step_audit.jsonl")));
    }

    @Test
    void writeFailureDoesNotThrow(@TempDir Path temp) throws Exception {
        Path fileWorkspace = temp.resolve("workspace-file");
        Files.writeString(fileWorkspace, "not a directory");
        StepAuditService service = new StepAuditService(fileWorkspace);

        assertDoesNotThrow(() -> service.append(record("step_1", "task_1", "team_1", StepAuditEventType.STEP_CREATED, "", "READY")));
        assertTrue(service.listByTask("task_1").isEmpty());
    }

    private StepAuditRecord record(String stepId, String taskId, String teamSessionId, StepAuditEventType eventType, String before, String after) {
        return new StepAuditRecord(null, stepId, taskId, teamSessionId, eventType,
                before, after, eventType.name(), "", "", "", "", "", "", null, Map.of());
    }
}
