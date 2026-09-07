package ricbot.domain.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.artifact.ArtifactDelta;
import ricbot.infra.execution.ExecutionBackend;
import ricbot.infra.execution.ExecutionRequest;
import ricbot.infra.execution.ExecutionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
        ArtifactDelta delta = delta(List.of("api-contract"), List.of("API contract is preserved"));

        VerificationReport report = new WorkspaceVerificationService(workspace, backend)
                .verify("run-1", workspace, delta);

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
                .verify("run-2", workspace, delta(List.of("unknown-check"), List.of("manual UX review")));

        assertEquals(VerificationReport.Status.NEEDS_HUMAN, report.status());
        assertTrue(report.unprovenCriteria().stream().anyMatch(value -> value.contains("unknown check")));
    }

    @Test
    void failedCompileRejectsAndSkipsLaterStages(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("mvnw"), "");
        RecordingBackend backend = new RecordingBackend(1);
        VerificationReport report = new WorkspaceVerificationService(workspace, backend)
                .verify("run-3", workspace, delta(List.of(), List.of()));

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
                .verify("run-profile", workspace, delta(List.of(), List.of()), pinned);

        assertEquals(VerificationReport.Status.NEEDS_HUMAN, report.status());
        assertTrue(backend.commands.isEmpty());
        assertTrue(report.artifactDirectory().startsWith("sqlite:.ricbot/application.db#verification/"));
        assertEquals(report.reportId(), new ricbot.infra.runtime.SqliteRuntimeStore(workspace)
                .verificationReports().get(0).reportId());
    }

    private static ArtifactDelta delta(List<String> checks, List<String> criteria) {
        return new ArtifactDelta("delta-1", "git-patch", "HEAD", List.of("git:working-tree"),
                Map.of("requiredChecks", checks, "acceptanceCriteria", criteria,
                        "checkRunIds", Map.of("api-contract", List.of("run-child"))));
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
