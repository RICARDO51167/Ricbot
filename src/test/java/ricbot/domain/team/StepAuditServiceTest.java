package ricbot.domain.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.security.CommandRiskLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepAuditServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void appendListAndRenderTimeline(@TempDir Path workspace) {
        StepAuditService service = new StepAuditService(workspace);
        StepAuditRecord created = record("step_1", "task_1", "team_1", StepAuditEventType.STEP_CREATED, "", "READY");
        StepAuditRecord approval = new StepAuditRecord(null, "step_1", "task_1", "team_1",
                StepAuditEventType.STEP_APPROVAL_REQUIRED, "READY", "APPROVAL_REQUIRED",
                "approval required", "approval_1", "edit_file", "", "", "", "", null, Map.of());

        service.append(created);
        service.append(approval);

        assertEquals(1, service.listByStep("step_1").size());
        assertEquals(1, service.listByTask("task_1").size());
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

        assertDoesNotThrow(() -> service.append(record("step_1", "task_1", "team_1", StepAuditEventType.STEP_FAILED, "", "FAILED")));
        assertTrue(service.listByTask("task_1").isEmpty());
    }

    @Test
    void compactSummaryReadsImplementationStepsJsonl(@TempDir Path workspace) throws Exception {
        writeStep(workspace, step("step_ready", "task_1", ImplementationStepStatus.READY));
        StepAuditService service = new StepAuditService(workspace);

        StepAuditSummary summary = service.summarizeTask("task_1");

        assertEquals(1, summary.totalSteps());
        assertEquals(1, summary.readyCount());
        assertTrue(service.renderCompactTaskAudit("task_1").contains("total=1"));
    }

    @Test
    void compactSummarySkipsBadImplementationStepLines(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve(".team").resolve("team_1").resolve("implementation_steps.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{bad json}\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        Files.writeString(file, MAPPER.writeValueAsString(step("step_ready", "task_1", ImplementationStepStatus.READY).toMap()) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        StepAuditSummary summary = new StepAuditService(workspace).summarizeTask("task_1");

        assertEquals(1, summary.totalSteps());
        assertTrue(summary.warnings().stream().anyMatch(warning -> warning.contains("skipped invalid jsonl line")), summary.warnings().toString());
    }

    @Test
    void missingImplementationStepsFileDegradesToWarning(@TempDir Path workspace) {
        StepAuditSummary summary = new StepAuditService(workspace).summarizeTask("missing_task");

        assertEquals(0, summary.totalSteps());
        assertTrue(summary.warnings().contains("no implementation steps found"), summary.warnings().toString());
        assertTrue(new StepAuditService(workspace).renderCompactTaskAudit("missing_task").contains("no implementation steps found"));
    }

    @Test
    void verifierOutcomeKeepsStructuredEvidenceWithoutTraceMirror(@TempDir Path workspace) {
        StepAuditService service = new StepAuditService(workspace);
        service.append(new StepAuditRecord(null, "", "task_1", "team_1",
                StepAuditEventType.STEP_VERIFIED, "", "DONE",
                "Verifier executed in task workspace.", "", "", "", "",
                "PASS", "", null, Map.of(
                "verifierCommand", "sh ./mvnw -q test",
                "exitCode", 0,
                "passed", true,
                "changedFilesCount", 1,
                "verificationDecision", "PASS",
                "structuredEvidenceSource", "team-worktree-verifier"
        )));

        StepAuditRecord persisted = service.listByTask("task_1").get(0);
        assertEquals(StepAuditRecord.CURRENT_SCHEMA_VERSION, persisted.schemaVersion());
        assertEquals(0, persisted.metadata().get("exitCode"));
        assertEquals("PASS", persisted.metadata().get("verificationDecision"));
        assertTrue(persisted.toMap().keySet().stream().noneMatch(key -> key.toLowerCase().contains("trace")));
    }

    @Test
    void loadsLegacyRecordWithoutSchemaOrTraceDependency(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve(".team/team_1/step_audit.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"id":"legacy-1","stepId":"step_1","taskId":"task_1","teamSessionId":"team_1","eventType":"STEP_VERIFIED","verificationStatus":"PASS","traceEventId":"old-trace","createdAt":"2025-01-01T00:00:00Z","metadata":{}}
                """);

        StepAuditRecord legacy = new StepAuditService(workspace).listByTask("task_1").get(0);
        assertEquals(1, legacy.schemaVersion());
        assertTrue(legacy.toMap().keySet().stream().noneMatch(key -> key.toLowerCase().contains("trace")));
    }

    private StepAuditRecord record(String stepId, String taskId, String teamSessionId, StepAuditEventType eventType, String before, String after) {
        return new StepAuditRecord(null, stepId, taskId, teamSessionId, eventType,
                before, after, eventType.name(), "", "", "", "", "", "", null, Map.of());
    }

    private void writeStep(Path workspace, PendingImplementationStep step) throws Exception {
        Path file = workspace.resolve(".team").resolve(step.teamSessionId()).resolve("implementation_steps.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, MAPPER.writeValueAsString(step.toMap()) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private PendingImplementationStep step(String id, String taskId, ImplementationStepStatus status) {
        return new PendingImplementationStep(id, "team_1", taskId, TeamRole.DEVELOPER, ImplementationStepType.READ,
                "README.md", "", "", "", "read target", CommandRiskLevel.SAFE, false, Map.of(), status, null, null);
    }
}
