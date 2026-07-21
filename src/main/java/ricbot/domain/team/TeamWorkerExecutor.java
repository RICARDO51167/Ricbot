package ricbot.domain.team;

import ricbot.domain.policy.PolicyDecision;
import ricbot.domain.policy.PolicyAwareToolExecutor;
import ricbot.domain.policy.PolicyEngine;
import ricbot.domain.workspace.WorkspaceSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TeamWorkerExecutor {
    private final VerificationService verificationService;
    private final PolicyEngine policyEngine;

    public TeamWorkerExecutor() {
        this(new VerificationService(), null);
    }

    public TeamWorkerExecutor(VerificationService verificationService) {
        this(verificationService, null);
    }

    public TeamWorkerExecutor(VerificationService verificationService, PolicyEngine policyEngine) {
        this.verificationService = verificationService != null ? verificationService : new VerificationService();
        this.policyEngine = policyEngine;
    }

    public WorkerExecutionResult execute(WorkerExecutionInput input) {
        WorkerExecutionInput safe = input != null
                ? input
                : new WorkerExecutionInput("", "", TeamRole.EXPLORER, "", "", "", List.of(), List.of(), "", List.of(), List.of(), List.of(), List.of(), 0d, "");
        return switch (safe.role()) {
            case EXPLORER -> explore(safe);
            case VERIFIER -> verifyInternal(safe);
            case DEVELOPER -> developerPlan(safe);
            default -> unsupported(safe);
        };
    }

    public WorkerExecutionResult verify(WorkerExecutionInput input) {
        return verifyInternal(input != null ? input : new WorkerExecutionInput("", "", TeamRole.VERIFIER, "", "", "", List.of(), List.of(), "", List.of(), List.of(), List.of(), List.of(), 0d, ""));
    }

    public PolicyAwareToolExecutor.PolicyToolResult executeToolAsRole(
            PolicyAwareToolExecutor executor,
            TeamRole role,
            String toolName,
            Map<String, Object> args,
            WorkspaceSession workspaceSession,
            String sessionId,
            String teamSessionId,
            String taskId
    ) {
        if (executor == null) {
            throw new IllegalArgumentException("PolicyAwareToolExecutor is required");
        }
        return executor.execute(role, toolName, args, workspaceSession, sessionId, teamSessionId, taskId);
    }

    private WorkerExecutionResult explore(WorkerExecutionInput input) {
        List<String> policySummary = policySummary(input.role(), "read_file");
        List<String> findings = new ArrayList<>();
        findings.add("Workspace: " + workspaceDisplay(input.workspacePath()));
        if (!input.relatedFiles().isEmpty()) {
            findings.add("Related files: " + String.join(", ", input.relatedFiles()));
        } else {
            findings.add("No explicit related files were provided.");
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
                findings, risks, suggestedTests, artifacts, policySummary, 0.63d, "COMPLETED");
    }

    private WorkerExecutionResult verifyInternal(WorkerExecutionInput input) {
        List<String> policySummary = policySummary(input.role(), "change diff");
        VerificationInput verificationInput = new VerificationInput(
                input.taskId(),
                input.goal(),
                !input.summary().isBlank() ? input.summary() : String.join("; ", input.findings()),
                input.findings(),
                input.whiteboardSummary(),
                List.of(),
                input.suggestedTests(),
                input.constraints(),
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
        return result(input, verification.summary(), findings, risks, verification.suggestedTests(), artifacts, policySummary,
                verification.confidence(), verification.status().name());
    }

    private WorkerExecutionResult developerPlan(WorkerExecutionInput input) {
        List<String> targetFiles = !input.relatedFiles().isEmpty()
                ? input.relatedFiles()
                : pathLike(input.goal());
        List<String> intendedChanges = new ArrayList<>();
        intendedChanges.add("Use /team tool-call " + input.taskId() + " read_file to inspect target files before edits.");
        intendedChanges.add("Use /team tool-call " + input.taskId() + " edit_file or write_file only after explicit approval.");
        if (!targetFiles.isEmpty()) {
            intendedChanges.add("Target files: " + String.join(", ", targetFiles));
        }
        List<String> requiredApprovals = List.of(
                "edit_file requires ApprovalService approval",
                "write_file requires ApprovalService approval",
                "commit and rollback are not available to DEVELOPER worker"
        );
        List<String> nextActions = List.of(
                "Read target files with /team tool-call " + input.taskId() + " read_file {\"path\":\"<file>\"}",
                "Request approved edits with /team tool-call " + input.taskId() + " edit_file {\"path\":\"<file>\",\"old_text\":\"...\",\"new_text\":\"...\"}",
                "After approved changes, run /change create",
                "Run /team run-verifier " + input.taskId(),
                "Review /summary"
        );
        List<String> suggestedTests = !input.suggestedTests().isEmpty()
                ? input.suggestedTests()
                : suggestTests(targetFiles);
        String riskLevel = input.workspacePath().contains("/.workspaces/") ? "MEDIUM" : "MEDIUM local workspace warning";
        List<String> plan = new ArrayList<>();
        plan.add("goal=" + fallback(input.goal(), "current developer task"));
        plan.add("workspacePath=" + workspaceDisplay(input.workspacePath()));
        plan.add("targetFiles=" + (targetFiles.isEmpty() ? "none yet" : String.join(", ", targetFiles)));
        plan.add("intendedChanges=" + String.join("; ", intendedChanges));
        plan.add("riskLevel=" + riskLevel);
        plan.add("requiredApprovals=" + String.join("; ", requiredApprovals));
        plan.add("suggestedTests=" + (suggestedTests.isEmpty() ? "none yet" : String.join("; ", suggestedTests)));
        plan.add("nextActions=" + String.join("; ", nextActions));
        String changeSetRecommendation = "After approved edit/write tool calls, run /change create, then /team run-verifier " + input.taskId() + ".";
        List<String> findings = new ArrayList<>();
        findings.add("Developer Plan: " + String.join(" | ", plan));
        findings.add("No files were modified by /team run-worker.");
        List<String> risks = new ArrayList<>(input.risks());
        risks.add("DEVELOPER does not automatically edit/write files.");
        if (!input.workspacePath().contains("/.workspaces/")) {
            risks.add("No active worktree workspace detected; local workspace execution is allowed but a worktree is recommended.");
        }
        List<String> artifacts = List.of(".team/" + input.teamSessionId() + "/workers.jsonl");
        List<TeamArtifact> teamArtifacts = List.of(new TeamArtifact(
                null,
                input.taskId(),
                artifacts.get(0),
                "Developer plan for " + input.taskId(),
                "developer_plan",
                null
        ));
        return new WorkerExecutionResult(
                input.taskId(),
                input.teamSessionId(),
                input.role(),
                input.goal(),
                input.workspacePath(),
                input.whiteboardSummary(),
                targetFiles,
                input.constraints(),
                "Developer Plan created; no file changes were executed.",
                findings,
                risks,
                suggestedTests,
                teamArtifacts,
                policySummary(input.role(), "edit_file"),
                plan,
                requiredApprovals,
                nextActions,
                changeSetRecommendation,
                0.66d,
                "PLANNED",
                null
        );
    }

    private WorkerExecutionResult unsupported(WorkerExecutionInput input) {
        List<String> policySummary = policySummary(input.role(), "write_file");
        return result(input, "Worker role is not enabled for automatic execution: " + input.role(),
                List.of("No files were modified."), List.of("DEVELOPER automatic execution is disabled in V4.8."),
                input.suggestedTests(), List.of(), policySummary, 0.35d, "SKIPPED");
    }

    private WorkerExecutionResult result(
            WorkerExecutionInput input,
            String summary,
            List<String> findings,
            List<String> risks,
            List<String> suggestedTests,
            List<TeamArtifact> artifacts,
            List<String> policySummary,
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
                input.constraints(),
                summary,
                findings,
                risks,
                suggestedTests,
                artifacts,
                policySummary,
                confidence,
                status,
                null
        );
    }

    private List<String> policySummary(TeamRole role, String toolName) {
        PolicyEngine engine = policyEngine != null ? policyEngine : new PolicyEngine(Path.of(".").toAbsolutePath().normalize());
        PolicyDecision decision = engine.evaluate(role, toolName, Map.of(), null);
        return List.of(
                "source=" + engine.policy().source(),
                "role=" + decision.role(),
                "tool=" + decision.toolName(),
                "decision=" + decision.decisionType(),
                "requiresApproval=" + decision.requiresApproval(),
                "denied=" + decision.denied(),
                "rules=" + (decision.matchedRules().isEmpty() ? "none" : String.join(",", decision.matchedRules()))
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

    private List<String> pathLike(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String cleaned = text.replace("{", " ").replace("}", " ").replace(",", " ");
        for (String token : cleaned.split("\\s+")) {
            String value = token.replace("\"", "").replace("'", "").trim();
            if (value.contains("/") || value.endsWith(".java") || value.endsWith(".md") || value.endsWith(".json")
                    || value.endsWith(".yml") || value.endsWith(".yaml") || value.endsWith(".txt")) {
                if (!out.contains(value)) {
                    out.add(value);
                }
            }
        }
        return out;
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
