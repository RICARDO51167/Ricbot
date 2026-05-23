package ricbot.domain.team;

import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.workspace.GitWorktreeWorkspaceBackend;
import ricbot.domain.workspace.WorkspaceLifecycleService;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.infra.config.Config;
import ricbot.tool.api.BuiltinToolRegistrar;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class TeamExecutionService {
    private final Path baseWorkspace;
    private final TeamEngine teamEngine;
    private final WorkspaceSessionStore workspaceStore;
    private final TeamWorkerRunner workerRunner;

    public TeamExecutionService(Path baseWorkspace, TeamEngine teamEngine) {
        this(baseWorkspace, teamEngine, null);
    }

    public TeamExecutionService(Path baseWorkspace, TeamEngine teamEngine, TeamWorkerRunner workerRunner) {
        this.baseWorkspace = baseWorkspace.toAbsolutePath().normalize();
        this.teamEngine = teamEngine;
        this.workspaceStore = new WorkspaceSessionStore(this.baseWorkspace);
        this.workerRunner = workerRunner;
    }

    public TeamExecutionResult runUserTask(String teamSessionId, String taskGoal, TeamExecutionOptions options) {
        String goal = clean(taskGoal);
        if (goal.isBlank()) {
            throw new IllegalArgumentException("missing team run task");
        }
        TeamSession session = resolveSession(teamSessionId, goal);
        TeamTask task = teamEngine.createTask(session.id(), TeamRole.DEVELOPER, goal);
        return runTask(task.id(), options);
    }

    public TeamExecutionResult runTask(String taskId, TeamExecutionOptions options) {
        TeamExecutionOptions safeOptions = options != null ? options : TeamExecutionOptions.defaults();
        TeamTask task = teamEngine.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("team task not found: " + taskId);
        }
        WorkspaceSession workspaceSession = safeOptions.worktree()
                ? createTaskWorktree(task)
                : null;
        Path executionRoot = workspaceSession != null
                ? Path.of(workspaceSession.workspacePath()).toAbsolutePath().normalize()
                : baseWorkspace;
        recordAudit(task, StepAuditEventType.STEP_APPLY_REQUESTED, "", task.state().name(),
                "Worktree-backed team execution started.", workspaceSession, Map.of("workspacePath", executionRoot.toString()));

        WorkerExecutionInput workerInput = new WorkerExecutionInput(
                task.id(),
                task.sessionId(),
                task.role(),
                task.goal(),
                executionRoot.toString(),
                teamEngine.whiteboard(task.sessionId()).readSummary(),
                List.of(),
                List.of(),
                List.of("workspaceRoot=" + executionRoot),
                "",
                List.of("executionRoot=" + executionRoot),
                List.of(),
                List.of(),
                List.of(),
                0d,
                ""
        );
        WorkerExecutionResult worker = runWorker(task, workerInput, workspaceSession, executionRoot);

        VerificationRun verificationRun = safeOptions.verify() && worker != null && !"FAILED".equalsIgnoreCase(worker.status())
                ? runVerifier(task, executionRoot, workspaceSession)
                : VerificationRun.skipped();
        String diff = workspaceSession != null
                ? new WorkspaceLifecycleService(baseWorkspace).diff(workspaceSession.id()).patch()
                : git(executionRoot, "diff", "--");
        TeamTaskReport report = teamEngine.taskReport(task.id());
        return new TeamExecutionResult(
                task.id(),
                task.sessionId(),
                workspaceSession != null ? workspaceSession.id() : "",
                executionRoot.toString(),
                worker,
                verificationRun.result(),
                verificationRun.output(),
                diff != null ? diff : "",
                report
        );
    }

    private WorkerExecutionResult runWorker(
            TeamTask task,
            WorkerExecutionInput workerInput,
            WorkspaceSession workspaceSession,
            Path executionRoot
    ) {
        if (workspaceSession == null || workerRunner == null || task.role() != TeamRole.DEVELOPER) {
            WorkerExecutionResult planned = teamEngine.runWorker(task.id(), workerInput);
            recordAudit(task, StepAuditEventType.STEP_TOOL_APPLIED, "", teamEngine.findTask(task.id()).state().name(),
                    "Worker executed in task workspace.", workspaceSession,
                    Map.of("workerStatus", planned.status(), "workspacePath", executionRoot.toString()));
            return planned;
        }

        teamEngine.startProducing(task.id());
        TeamWorkerResult workerResult = workerRunner.run(task, workspaceSession, executionRoot);
        WorkerExecutionResult worker = workerResult.toWorkerExecutionResult(task, executionRoot.toString(),
                teamEngine.whiteboard(task.sessionId()).readSummary());
        teamEngine.recordRoleToolCall(task.id(), worker);
        teamEngine.submitWorkerResult(task.id(), worker.summary(), worker.artifacts());
        if (workerResult.status() == TeamWorkerStatus.APPLIED) {
            teamEngine.recordAppliedChanges(task.id(), workerResult.changedFiles());
        }
        recordAudit(task, workerResult.status() == TeamWorkerStatus.FAILED ? StepAuditEventType.STEP_FAILED : StepAuditEventType.STEP_TOOL_APPLIED,
                "", teamEngine.findTask(task.id()).state().name(),
                "Team worker completed in task workspace.", workspaceSession,
                Map.of("workerStatus", worker.status(),
                        "workspacePath", executionRoot.toString(),
                        "changedFiles", workerResult.changedFiles(),
                        "workerDebug", workerResult.debugLines()));
        return worker;
    }

    private VerificationRun runVerifier(TeamTask task, Path executionRoot, WorkspaceSession workspaceSession) {
        ToolRegistry registry = registryFor(executionRoot);
        String command = Files.exists(executionRoot.resolve("mvnw"))
                ? "sh ./mvnw -q test"
                : "git diff --name-only && git status --short";
        Object raw = registry.execute("exec", Map.of(
                "command", command,
                "working_dir", executionRoot.toString(),
                "timeout", 600
        ));
        String output = raw != null ? String.valueOf(raw) : "";
        boolean failed = output.startsWith("[退出码") || output.startsWith("错误") || output.contains("BUILD FAILURE");
        VerificationResult result = failed
                ? new VerificationResult(
                VerificationResult.Status.REJECT,
                "worktree verifier command failed",
                "Verifier rejected worktree execution result.",
                List.of(command),
                CommandRiskLevel.MEDIUM,
                List.of("verifier command failed: " + command),
                List.of(command),
                List.of(),
                List.of("Inspect worktree and rerun verifier."),
                List.of(),
                false,
                0.62d,
                null
        )
                : new VerificationResult(
                VerificationResult.Status.PASS,
                "worktree verifier command passed",
                "Verifier accepted worktree execution result.",
                List.of(command),
                CommandRiskLevel.LOW,
                List.of("verifier command passed: " + command),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                0.78d,
                null
        );
        TeamTask updated = teamEngine.submitVerification(task.id(), result);
        recordAudit(updated, StepAuditEventType.STEP_VERIFIED, "", updated.state().name(),
                "Verifier executed in task workspace.", workspaceSession, Map.of(
                        "verificationStatus", result.status().name(),
                        "workspacePath", executionRoot.toString(),
                        "command", command
                ));
        return new VerificationRun(result, output);
    }

    private WorkspaceSession createTaskWorktree(TeamTask task) {
        WorkspaceSession existing = workspaceStore.list().stream()
                .filter(session -> session.type() == WorkspaceBackendType.GIT_WORKTREE)
                .filter(session -> session.status() == ricbot.domain.workspace.WorkspaceSessionStatus.ACTIVE)
                .filter(session -> task.id().equals(String.valueOf(session.metadata().get("taskId"))))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        String slug = "team-" + safeSlug(task.goal()) + "-" + task.id().replaceAll("[^A-Za-z0-9._-]+", "-");
        GitWorktreeWorkspaceBackend backend = new GitWorktreeWorkspaceBackend(baseWorkspace, workspaceStore);
        WorkspaceSession created = backend.createSession(baseWorkspace, task.goal(), slug);
        Map<String, Object> metadata = new LinkedHashMap<>(created.metadata());
        metadata.put("teamSessionId", task.sessionId());
        metadata.put("taskId", task.id());
        metadata.put("taskGoal", task.goal());
        metadata.put("executionMode", "team-worktree");
        return workspaceStore.save(created.withMetadata(metadata));
    }

    private TeamSession resolveSession(String teamSessionId, String goal) {
        TeamSession existing = !clean(teamSessionId).isBlank() ? teamEngine.findSession(teamSessionId) : null;
        if (existing != null) {
            return existing;
        }
        return teamEngine.createSession("Team run: " + goal);
    }

    private ToolRegistry registryFor(Path executionRoot) {
        ToolRegistry registry = new ToolRegistry();
        BuiltinToolRegistrar.registerFileAndSearchTools(registry, executionRoot, executionRoot);
        BuiltinToolRegistrar.registerExecTool(registry, executionRoot, true, new Config.ExecToolConfig());
        return registry;
    }

    private void recordAudit(
            TeamTask task,
            StepAuditEventType type,
            String beforeStatus,
            String afterStatus,
            String message,
            WorkspaceSession workspaceSession,
            Map<String, Object> metadata
    ) {
        Map<String, Object> out = new LinkedHashMap<>(metadata != null ? metadata : Map.of());
        if (workspaceSession != null) {
            out.put("workspaceSessionId", workspaceSession.id());
            out.put("workspacePath", workspaceSession.workspacePath());
            out.put("workspaceType", workspaceSession.type().name());
        } else {
            out.put("workspaceType", WorkspaceBackendType.LOCAL.name());
            out.put("workspacePath", baseWorkspace.toString());
        }
        teamEngine.recordStepAudit(new StepAuditRecord(null, "", task.id(), task.sessionId(), type,
                beforeStatus, afterStatus, message,
                stringValue(out.get("approvalRequestId")),
                stringValue(out.get("toolName")),
                stringValue(out.get("toolResultSummary")),
                stringValue(out.get("changeSetId")),
                stringValue(out.get("verificationStatus")),
                "", null, out));
    }

    private String git(Path directory, String... args) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory.toFile());
        try {
            Process process = pb.start();
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            int code = process.waitFor();
            if (code != 0) {
                return stderr.isBlank() ? stdout : stderr;
            }
            return stdout;
        } catch (Exception e) {
            return "git command failed: " + e.getMessage();
        }
    }

    private String safeSlug(String value) {
        String slug = clean(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\p{IsAlphabetic}\\p{IsDigit}]+", "-")
                .replaceAll("^-+|-+$", "")
                .replaceAll("-{2,}", "-");
        if (slug.isBlank()) {
            slug = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        return slug.length() <= 48 ? slug : slug.substring(0, 48).replaceAll("-+$", "");
    }

    private String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private record VerificationRun(VerificationResult result, String output) {
        private static VerificationRun skipped() {
            return new VerificationRun(null, "");
        }
    }

    public record TeamExecutionOptions(boolean worktree, boolean verify) {
        public static TeamExecutionOptions defaults() {
            return new TeamExecutionOptions(false, false);
        }
    }

    public record TeamExecutionResult(
            String taskId,
            String teamSessionId,
            String workspaceSessionId,
            String workspacePath,
            WorkerExecutionResult workerResult,
            VerificationResult verificationResult,
            String verifierOutput,
            String diff,
            TeamTaskReport report
    ) {
        public boolean usedWorktree() {
            return workspaceSessionId != null && !workspaceSessionId.isBlank();
        }
    }
}
