package ricbot.domain.team;

import ricbot.domain.security.CommandRiskLevel;
import ricbot.domain.workspace.GitWorktreeWorkspaceBackend;
import ricbot.domain.workspace.RuntimeArtifactFilter;
import ricbot.domain.workspace.WorkspaceLifecycleService;
import ricbot.domain.workspace.WorkspaceBackendType;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.worker.WorkerStore;
import ricbot.domain.workspace.WorkspaceSessionStore;
import ricbot.infra.config.Config;
import ricbot.tool.api.BuiltinToolRegistrar;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TeamExecutionService {
    private final Path baseWorkspace;
    private final TeamEngine teamEngine;
    private final WorkspaceSessionStore workspaceStore;
    private final TeamWorkerRunner workerRunner;
    private final VerificationService verificationService = new VerificationService();

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

        WorkerStore.StoredWorker workerSession = teamEngine.startWorker(task.id(), task.role());
        teamEngine.startProducing(task.id());
        TeamWorkerResult workerResult;
        try {
            workerResult = workerRunner.run(task, workspaceSession, executionRoot);
        } catch (RuntimeException e) {
            teamEngine.failWorker(workerSession.spec().workerId(), e.getMessage());
            throw e;
        }
        WorkerExecutionResult worker = workerResult.toWorkerExecutionResult(task, executionRoot.toString(),
                teamEngine.whiteboard(task.sessionId()).readSummary());
        teamEngine.recordRoleToolCall(task.id(), worker);
        teamEngine.submitWorkerResult(task.id(), worker.summary(), worker.artifacts());
        if (workerResult.status() == TeamWorkerStatus.APPLIED) {
            teamEngine.recordAppliedChanges(task.id(), workerResult.changedFiles());
        }
        if (workerResult.status() == TeamWorkerStatus.FAILED) {
            teamEngine.failWorker(workerSession.spec().workerId(), worker.summary());
        } else {
            teamEngine.completeWorker(workerSession.spec().workerId(), worker.summary());
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
        long startedAt = System.nanoTime();
        Object raw = registry.execute("exec", Map.of(
                "command", command,
                "working_dir", executionRoot.toString(),
                "timeout", 600
        ));
        long durationMillis = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        String output = raw != null ? String.valueOf(raw) : "";
        boolean failed = output.startsWith("[退出码") || output.startsWith("错误") || output.contains("BUILD FAILURE");
        Integer exitCode = parseExitCode(output);
        if (failed && exitCode == null) {
            exitCode = 1;
        } else if (!failed && exitCode == null) {
            exitCode = 0;
        }
        List<DiffEvidence> changedFiles = diffEvidence(executionRoot, workspaceSession);
        VerificationEvidence evidence = new VerificationEvidence(
                List.of(new ExecutedTestEvidence(command, exitCode, !failed, outputSummary(output), durationMillis, Instant.now())),
                changedFiles,
                List.of()
        );
        VerificationInput input = new VerificationInput(
                task.id(),
                task.goal(),
                failed ? "worktree verifier command failed" : "worktree verifier command passed",
                List.of(),
                "TaskSummary contains worktree verifier evidence.",
                List.of(),
                List.of(command),
                List.of(command),
                "",
                evidence
        );
        VerificationResult result = verificationService.verify(input);
        TeamTask updated = teamEngine.submitVerification(task.id(), result, evidence);
        Map<String, Object> verifierMetadata = new LinkedHashMap<>();
        verifierMetadata.put("verificationStatus", result.status().name());
        verifierMetadata.put("workspacePath", executionRoot.toString());
        verifierMetadata.put("command", command);
        verifierMetadata.put("verifierCommand", command);
        verifierMetadata.put("exitCode", exitCode);
        verifierMetadata.put("passed", !failed);
        verifierMetadata.put("durationMillis", durationMillis);
        verifierMetadata.put("changedFilesCount", changedFiles.size());
        verifierMetadata.put("verificationDecision", result.status().name());
        verifierMetadata.put("verifierReason", result.reason());
        verifierMetadata.put("structuredEvidenceSource", "team-worktree-verifier");
        recordAudit(updated, StepAuditEventType.STEP_VERIFIED, "", updated.state().name(),
                "Verifier executed in task workspace.", workspaceSession, verifierMetadata);
        return new VerificationRun(result, output);
    }

    private List<DiffEvidence> diffEvidence(Path executionRoot, WorkspaceSession workspaceSession) {
        List<String> changedFiles;
        if (workspaceSession != null) {
            changedFiles = new WorkspaceLifecycleService(baseWorkspace).diff(workspaceSession.id()).changedFiles();
        } else {
            changedFiles = lines(git(executionRoot, "diff", "--name-only", "--"));
        }
        List<DiffEvidence> out = new ArrayList<>();
        for (String path : changedFiles) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String normalized = path.trim();
            if (RuntimeArtifactFilter.isRuntimeArtifact(normalized)) {
                continue;
            }
            out.add(new DiffEvidence(
                    normalized,
                    "EDIT",
                    riskLevelFor(normalized),
                    isTestFile(normalized),
                    false,
                    isConfigFile(normalized),
                    isSecuritySensitive(normalized),
                    false
            ));
        }
        return out;
    }

    private Integer parseExitCode(String output) {
        Matcher matcher = Pattern.compile("^\\[退出码\\s+(\\d+)]").matcher(output != null ? output.trim() : "");
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String outputSummary(String output) {
        String value = clean(output).replaceAll("\\s+", " ");
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private List<String> lines(String output) {
        List<String> out = new ArrayList<>();
        for (String line : clean(output).split("\\R")) {
            if (!line.isBlank()) {
                out.add(line.trim());
            }
        }
        return out;
    }

    private CommandRiskLevel riskLevelFor(String path) {
        return isSecuritySensitive(path) ? CommandRiskLevel.HIGH : CommandRiskLevel.LOW;
    }

    private boolean isSecuritySensitive(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("security")
                || lower.contains("approval")
                || lower.contains("policy")
                || lower.contains("provider")
                || lower.contains("agentloop")
                || lower.contains("toolregistry")
                || lower.startsWith(".github/workflows/");
    }

    private boolean isConfigFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xml")
                || lower.endsWith(".properties")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".toml")
                || lower.endsWith(".json")
                || lower.contains("/config/");
    }

    private boolean isTestFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.endsWith("test.java");
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
