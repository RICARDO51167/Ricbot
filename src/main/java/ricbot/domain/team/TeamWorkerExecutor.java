package ricbot.domain.team;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TeamWorkerExecutor {
    private final VerificationService verificationService;

    public TeamWorkerExecutor() {
        this(new VerificationService());
    }

    public TeamWorkerExecutor(VerificationService verificationService) {
        this.verificationService = verificationService != null ? verificationService : new VerificationService();
    }

    public WorkerExecutionResult execute(WorkerExecutionInput input) {
        WorkerExecutionInput safe = input != null
                ? input
                : new WorkerExecutionInput("", "", TeamRole.EXPLORER, "", "", "", List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), List.of(), 0d, "");
        return switch (safe.role()) {
            case EXPLORER -> explore(safe);
            case VERIFIER -> verifyInternal(safe);
            default -> unsupported(safe);
        };
    }

    public WorkerExecutionResult verify(WorkerExecutionInput input) {
        return verifyInternal(input != null ? input : new WorkerExecutionInput("", "", TeamRole.VERIFIER, "", "", "", List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), List.of(), 0d, ""));
    }

    private WorkerExecutionResult explore(WorkerExecutionInput input) {
        List<String> findings = new ArrayList<>();
        findings.add("Workspace: " + workspaceDisplay(input.workspacePath()));
        if (!input.relatedFiles().isEmpty()) {
            findings.add("Related files: " + String.join(", ", input.relatedFiles()));
        } else {
            findings.add("No explicit related files were provided.");
        }
        if (!input.verifiedExperience().isEmpty()) {
            findings.add("Verified experience available: " + String.join("; ", input.verifiedExperience().stream().limit(3).toList()));
        }
        if (!input.whiteboardSummary().isBlank()) {
            findings.add("Whiteboard context: " + abbreviate(input.whiteboardSummary(), 260));
        }
        List<String> risks = new ArrayList<>(input.risks());
        risks.add("Explorer is read-only and did not modify files.");
        List<String> suggestedTests = !input.suggestedTests().isEmpty()
                ? input.suggestedTests()
                : suggestTests(input.relatedFiles());
        List<TeamArtifact> artifacts = List.of(new TeamArtifact(
                null,
                input.taskId(),
                ".team/" + input.teamSessionId() + "/workers.jsonl",
                "Explorer report for " + input.taskId(),
                "worker_report",
                null
        ));
        return result(input, "Explorer summarized workspace context for: " + fallback(input.goal(), "current task"),
                findings, risks, suggestedTests, artifacts, 0.63d, "COMPLETED");
    }

    private WorkerExecutionResult verifyInternal(WorkerExecutionInput input) {
        VerificationInput verificationInput = new VerificationInput(
                input.taskId(),
                input.goal(),
                !input.summary().isBlank() ? input.summary() : String.join("; ", input.findings()),
                input.findings(),
                input.whiteboardSummary(),
                List.of(),
                input.suggestedTests(),
                input.constraints(),
                input.verifiedExperience(),
                input.whiteboardSummary()
        );
        VerificationResult verification = verificationService.verify(verificationInput);
        List<String> findings = new ArrayList<>();
        findings.add("Verification status: " + verification.status());
        findings.addAll(verification.reasons());
        List<String> risks = new ArrayList<>(verification.suspiciousChanges());
        risks.addAll(verification.requiredActions());
        List<TeamArtifact> artifacts = List.of(new TeamArtifact(
                null,
                input.taskId(),
                ".team/" + input.teamSessionId() + "/verification.jsonl",
                "Verifier report for " + input.taskId() + " status=" + verification.status(),
                "verifier_report",
                null
        ));
        return result(input, verification.summary(), findings, risks, verification.suggestedTests(), artifacts,
                verification.confidence(), verification.status().name());
    }

    private WorkerExecutionResult unsupported(WorkerExecutionInput input) {
        return result(input, "Worker role is not enabled for automatic execution: " + input.role(),
                List.of("No files were modified."), List.of("DEVELOPER automatic execution is disabled in V4.8."),
                input.suggestedTests(), List.of(), 0.35d, "SKIPPED");
    }

    private WorkerExecutionResult result(
            WorkerExecutionInput input,
            String summary,
            List<String> findings,
            List<String> risks,
            List<String> suggestedTests,
            List<TeamArtifact> artifacts,
            double confidence,
            String status
    ) {
        return new WorkerExecutionResult(
                input.taskId(),
                input.teamSessionId(),
                input.role(),
                input.goal(),
                input.workspacePath(),
                input.whiteboardSummary(),
                input.relatedFiles(),
                input.verifiedExperience(),
                input.constraints(),
                summary,
                findings,
                risks,
                suggestedTests,
                artifacts,
                confidence,
                status,
                null
        );
    }

    private List<String> suggestTests(List<String> relatedFiles) {
        if (relatedFiles == null || relatedFiles.isEmpty()) {
            return List.of();
        }
        if (relatedFiles.stream().anyMatch(path -> path.contains("/domain/team/"))) {
            return List.of("./mvnw -q -Dtest='ricbot.domain.team.*Test' test");
        }
        if (relatedFiles.stream().anyMatch(path -> path.contains("/domain/agent/"))) {
            return List.of("./mvnw -q -Dtest='ricbot.domain.agent.*Test' test");
        }
        return List.of("./mvnw -q test");
    }

    private String workspaceDisplay(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "(fallback base workspace)";
        }
        Path path = Path.of(rawPath);
        return Files.exists(path) ? path.toString() : path + " (not found)";
    }

    private String fallback(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String abbreviate(String value, int maxChars) {
        String safe = value != null ? value.trim().replaceAll("\\s+", " ") : "";
        return safe.length() <= maxChars ? safe : safe.substring(0, Math.max(0, maxChars)) + "...";
    }
}
