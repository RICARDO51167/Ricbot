package ricbot.domain.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.task.TaskFailurePolicy;
import ricbot.domain.task.TaskResult;
import ricbot.domain.task.TaskSpec;
import ricbot.domain.task.TaskStatus;
import ricbot.domain.task.TaskWorkspaceMode;
import ricbot.domain.task.TeamPlan;
import ricbot.domain.task.TaskRole;
import ricbot.infra.execution.ExecutionBackend;
import ricbot.infra.execution.ExecutionRequest;
import ricbot.infra.execution.ExecutionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceVerificationServiceTest {
    @Test
    void trustedNamedChecksProduceStructuredPassEvidence(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve(".ricbot"));
        Files.writeString(workspace.resolve(".ricbot/verification.json"), """
                {"version":1,
                 "compile":[{"id":"compile","command":"compile-safe","timeoutSeconds":10}],
                 "test":[{"id":"test","command":"test-safe","timeoutSeconds":10}],
                 "acceptance":[{"id":"api-contract","command":"accept-safe","timeoutSeconds":10}]}
                """);
        RecordingBackend backend = new RecordingBackend(0);
        TeamPlan plan = plan(List.of("api-contract"), List.of("API contract is preserved"));

        VerificationReport report = new WorkspaceVerificationService(workspace, backend)
                .verify("run-1", workspace, plan, List.of(result()));

        assertEquals(VerificationReport.Status.PASS, report.status());
        assertEquals(List.of("git diff --check", "git diff --name-status HEAD", "git diff --binary HEAD",
                        "compile-safe", "test-safe", "accept-safe"),
                backend.commands);
        assertTrue(report.checks().stream().allMatch(check -> !check.artifact().isBlank()));
    }

    @Test
    void unknownOrUnprovenAcceptanceNeedsHuman(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("mvnw"), "");
        VerificationReport report = new WorkspaceVerificationService(workspace, new RecordingBackend(0))
                .verify("run-2", workspace, plan(List.of("unknown-check"), List.of("manual UX review")), List.of(result()));

        assertEquals(VerificationReport.Status.NEEDS_HUMAN, report.status());
        assertTrue(report.unprovenCriteria().stream().anyMatch(value -> value.contains("unknown check")));
    }

    @Test
    void failedCompileRejectsAndSkipsLaterStages(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("mvnw"), "");
        RecordingBackend backend = new RecordingBackend(1);
        VerificationReport report = new WorkspaceVerificationService(workspace, backend)
                .verify("run-3", workspace, plan(List.of(), List.of()), List.of(result()));

        assertEquals(VerificationReport.Status.REJECT, report.status());
        assertTrue(report.checks().stream().anyMatch(check -> check.stage() == VerificationCheckResult.Stage.TEST
                && check.status() == VerificationCheckResult.Status.SKIPPED));
    }

    @Test
    void changedTrustedProfileDigestNeedsHumanWithoutExecutingCommands(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve(".ricbot"));
        Path profile = workspace.resolve(".ricbot/verification.json");
        Files.writeString(profile, """
                {"version":1,"compile":[{"id":"compile","command":"compile-v1"}],
                 "test":[{"id":"test","command":"test-v1"}]}
                """);
        String pinned = VerificationProfile.load(workspace).digest();
        Files.writeString(profile, """
                {"version":1,"compile":[{"id":"compile","command":"compile-tampered"}],
                 "test":[{"id":"test","command":"test-v1"}]}
                """);
        RecordingBackend backend = new RecordingBackend(0);

        VerificationReport report = new WorkspaceVerificationService(workspace, backend)
                .verify("run-profile", workspace, plan(List.of(), List.of()), List.of(result()), pinned);

        assertEquals(VerificationReport.Status.NEEDS_HUMAN, report.status());
        assertTrue(backend.commands.isEmpty());
        assertTrue(report.artifactDirectory().startsWith("sqlite:.ricbot/runtime.db#verification/"));
        assertEquals(report.reportId(), new ricbot.infra.runtime.SqliteRuntimeStore(workspace)
                .verificationReports().get(0).reportId());
    }

    private static TeamPlan plan(List<String> checks, List<String> criteria) {
        TaskSpec task = new TaskSpec("task-1", "run", "activation", 0, 0, TaskRole.DEVELOPER, "change code",
                List.of(), List.of(), TaskWorkspaceMode.ISOLATED_WORKTREE, TaskFailurePolicy.FAIL_FAST, false,
                checks, criteria);
        return new TeamPlan("plan", "run", 0, List.of(task));
    }
    private static TaskResult result() {
        return new TaskResult(2, "task-1", "run", "child", TaskStatus.SUCCEEDED, 0, "done", Map.of(),
                "patch", List.of("src/A.java"), List.of("test-safe"), "", Instant.now());
    }
    private static final class RecordingBackend implements ExecutionBackend {
        private final List<String> commands = new ArrayList<>();
        private final int compileExit;
        private RecordingBackend(int compileExit) { this.compileExit = compileExit; }
        public String name() { return "fake"; }
        public ricbot.infra.execution.ExecutionCapabilities probe() {
            return new ricbot.infra.execution.ExecutionCapabilities(true, false, false, false, "fake");
        }
        public ExecutionResult execute(ExecutionRequest request) {
            commands.add(request.command());
            int exit = request.command().contains("-DskipTests") || request.command().equals("compile-safe")
                    ? compileExit : 0;
            return new ExecutionResult(exit, request.command().contains("--binary") ? "diff" : "ok", "", false,
                    false, Duration.ofMillis(5), "fake", Map.of());
        }
    }
}
