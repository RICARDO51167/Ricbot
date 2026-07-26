package ricbot.domain.agent;

import ricbot.domain.config.ProviderCapability;
import ricbot.domain.task.TaskWorkerRequest;
import ricbot.domain.task.TaskWorkerResult;
import ricbot.domain.task.TaskWorkerRunner;
import ricbot.domain.task.TaskWorkerStatus;
import ricbot.domain.workspace.RuntimeArtifactFilter;
import ricbot.domain.workspace.WorkspaceLifecycleService;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentTeamWorkerRunner implements TaskWorkerRunner {
    public static final List<String> ALLOWED_TOOLS = List.of(
            "list_dir",
            "read_file",
            "write_file",
            "edit_file",
            "grep",
            "glob"
    );
    public static final List<String> READ_ONLY_TOOLS = List.of("list_dir", "read_file", "grep", "glob");

    private final Path baseWorkspace;
    private final AgentInvocationRuntime runner;
    private final String model;
    private final int maxIterations;
    private final int maxToolResultChars;
    private final String providerRetryMode;
    private final int contextWindowTokens;
    private final Integer contextBlockLimit;
    private final ProviderCapability providerCapability;

    public AgentTeamWorkerRunner(Path baseWorkspace, AgentInvocationRuntime runner, String model) {
        this(baseWorkspace, runner, model, 8, 10_000, "standard", 64_000, null, null);
    }

    public AgentTeamWorkerRunner(
            Path baseWorkspace,
            AgentInvocationRuntime runner,
            String model,
            int maxIterations,
            int maxToolResultChars,
            String providerRetryMode,
            int contextWindowTokens,
            Integer contextBlockLimit,
            ProviderCapability providerCapability
    ) {
        this.baseWorkspace = baseWorkspace.toAbsolutePath().normalize();
        this.runner = runner;
        this.model = model != null && !model.isBlank() ? model : "model";
        this.maxIterations = Math.max(1, maxIterations);
        this.maxToolResultChars = Math.max(1_000, maxToolResultChars);
        this.providerRetryMode = providerRetryMode != null && !providerRetryMode.isBlank() ? providerRetryMode : "standard";
        this.contextWindowTokens = Math.max(1_000, contextWindowTokens);
        this.contextBlockLimit = contextBlockLimit;
        this.providerCapability = providerCapability;
    }

    @Override
    public TaskWorkerResult run(TaskWorkerRequest task, WorkspaceSession workspaceSession, Path workspaceRoot) {
        Instant started = Instant.now();
        if (task == null) {
            return TaskWorkerResult.failed("task is required", 0);
        }
        if (runner == null) {
            return TaskWorkerResult.failed("Agent Runtime is unavailable for task worker", 0);
        }
        Path root;
        try {
            root = safeWorkspaceRoot(task, workspaceSession, workspaceRoot);
        } catch (Exception e) {
            return TaskWorkerResult.failed(e.getMessage(), 0);
        }
        String sessionKey = "task-worker-" + task.taskId();
        try {
            WorkspaceLifecycleService lifecycle = new WorkspaceLifecycleService(baseWorkspace);
            boolean sharedRead = task.workspaceMode() == ricbot.domain.task.TaskWorkspaceMode.SHARED_READ;
            WorkspaceLifecycleService.WorkspaceDiff beforeDiff = sharedRead
                    ? emptyDiff() : lifecycle.diff(workspaceSession.id());
            AgentRunResult result = runner.run(workerSpec(task, workspaceSession, root, sessionKey));
            WorkspaceLifecycleService.WorkspaceDiff diff = sharedRead
                    ? emptyDiff() : lifecycle.diff(workspaceSession.id());
            List<String> changedFiles = diff.changedFiles();
            List<Map<String, Object>> toolEvents = result.getToolEvents() != null ? result.getToolEvents() : List.of();
            List<String> toolCallNames = toolEvents.stream()
                    .map(event -> clean(String.valueOf(event.getOrDefault("name", ""))))
                    .filter(name -> !name.isBlank())
                    .toList();
            boolean hasToolErrors = toolEvents.stream().anyMatch(event -> "error".equalsIgnoreCase(clean(String.valueOf(event.get("status")))));
            String noChangesReason = changedFiles.isEmpty()
                    ? noChangesReason(result, toolCallNames, rawGitStatus(root))
                    : "";
            TaskWorkerStatus status = statusFor(result, changedFiles, hasToolErrors);
            List<String> debugLines = debugLines(task, workspaceSession, root, sessionKey, result, beforeDiff, diff, rawGitStatus(root), noChangesReason);
            String summary = !clean(result.getFinalContent()).isBlank()
                    ? clean(result.getFinalContent())
                    : status == TaskWorkerStatus.APPLIED
                    ? "Team worker applied changes: " + String.join(", ", changedFiles)
                    : "Team worker completed but produced no user changes.";
            String error = status == TaskWorkerStatus.FAILED && clean(result.getError()).isBlank()
                    ? (!noChangesReason.isBlank() ? noChangesReason : "team worker failed")
                    : clean(result.getError());
            return new TaskWorkerResult(
                    status,
                    changedFiles,
                    summary,
                    changedFiles.stream().map(path -> "APPLY_CHANGE " + path).toList(),
                    error,
                    Duration.between(started, Instant.now()).toMillis(),
                    clean(result.getRunId()),
                    "",
                    debugLines
            );
        } catch (Exception e) {
            return TaskWorkerResult.failed(e.getMessage(), Duration.between(started, Instant.now()).toMillis());
        }
    }

    AgentRunSpec workerSpec(TaskWorkerRequest task, WorkspaceSession workspaceSession, Path root, String sessionKey) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "team-worker");
        metadata.put("taskId", task.taskId());
        metadata.put("parentRunId", task.parentRunId());
        metadata.put("workspaceSessionId", workspaceSession != null ? workspaceSession.id() : "");
        metadata.put("workspaceRoot", root.toString());
        return new AgentRunSpec()
                .setRunId(task.childRunId())
                .setInitialMessages(workerMessages(task, root))
                .setTools(workerTools(root, task.workspaceMode() != ricbot.domain.task.TaskWorkspaceMode.SHARED_READ))
                .setModel(model)
                .setMaxIterations(maxIterations)
                .setMaxToolResultChars(maxToolResultChars)
                .setProviderRetryMode(providerRetryMode)
                .setErrorMessage("Team worker failed while calling model.")
                .setMaxIterationsMessage("Team worker reached the tool iteration limit before completing the task.")
                .setConcurrentTools(false)
                .setWorkspace(root)
                .setRuntimeWorkspace(baseWorkspace)
                .setSessionKey(sessionKey)
                .setContextWindowTokens(contextWindowTokens)
                .setContextBlockLimit(contextBlockLimit)
                .setProviderCapability(providerCapability)
                .setMetadata(metadata)
                .setAllowedTools(workerToolNames(task));
    }

    private List<Map<String, Object>> workerMessages(TaskWorkerRequest task, Path root) {
        boolean writable = task.workspaceMode() != ricbot.domain.task.TaskWorkspaceMode.SHARED_READ;
        String system = (writable ? """
                You are Ricbot Team worker.
                You run inside a managed git worktree and must use the available file tools to make real file changes when the task requires changes.
                Do not only output a plan. If you do not call a write tool, the task is not complete.
                First read the necessary files with read_file or list_dir, then make the smallest safe change with edit_file or write_file.
                If the task asks for a README/documentation change, you must read_file README.md, then edit_file or write_file README.md.
                If the task is code work, locate the file first and make a minimal edit.
                After the write tool succeeds, stop and output a short summary plus changed files.
                Do not claim a file changed unless a tool changed it.
                Do not call tools that are not provided or invent tools.
                Do not modify runtime artifacts such as .git, .ricbot, .workspaces, notes, session.json, target, or logs.
                """ : """
                You are a read-only Ricbot Team worker sharing the parent repository workspace.
                Inspect only with list_dir, read_file, grep and glob. Never modify files, run commands, or claim changes.
                Return concise findings and evidence for the parent run.
                """);
        String user = "Current task: " + task.goal()
                + "\nWorkspace root: " + root
                + "\nComplete real changes in this worktree. If impossible, explain why.";
        return List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        );
    }

    private ToolRegistry workerTools(Path root, boolean writable) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new GuardedTool(new ListDirTool(root, root), root, false));
        registry.register(new GuardedTool(new ReadFileTool(root, root, List.of()), root, false));
        if (writable) {
            registry.register(new GuardedTool(new WriteFileTool(root, root), root, true));
            registry.register(new GuardedTool(new EditFileTool(root, root), root, true));
        }
        registry.register(new GuardedTool(new GrepTool(root, root), root, false));
        registry.register(new GuardedTool(new GlobTool(root, root), root, false));
        return registry;
    }

    private Path safeWorkspaceRoot(TaskWorkerRequest task, WorkspaceSession workspaceSession, Path workspaceRoot) {
        if (task.workspaceMode() == ricbot.domain.task.TaskWorkspaceMode.SHARED_READ) {
            Path root = workspaceRoot != null ? workspaceRoot.toAbsolutePath().normalize() : baseWorkspace;
            if (!root.equals(baseWorkspace)) {
                throw new IllegalStateException("shared-read worker must use the base workspace: " + root);
            }
            return root;
        }
        if (workspaceSession == null || workspaceSession.id().isBlank()) {
            throw new IllegalArgumentException("workspaceSession is required");
        }
        Path root = workspaceRoot != null
                ? workspaceRoot.toAbsolutePath().normalize()
                : Path.of(workspaceSession.workspacePath()).toAbsolutePath().normalize();
        Path sessionRoot = Path.of(workspaceSession.workspacePath()).toAbsolutePath().normalize();
        if (!root.equals(sessionRoot)) {
            throw new IllegalStateException("team worker workspace must match workspace session path: " + root);
        }
        Path workspacesRoot = baseWorkspace.resolve(".workspaces").toAbsolutePath().normalize();
        if (!root.startsWith(workspacesRoot) || root.equals(workspacesRoot)) {
            throw new IllegalStateException("team worker workspace must stay under .workspaces: " + root);
        }
        return root;
    }

    private static WorkspaceLifecycleService.WorkspaceDiff emptyDiff() {
        return new WorkspaceLifecycleService.WorkspaceDiff(null, "", List.of(), "");
    }

    private static List<String> workerToolNames(TaskWorkerRequest task) {
        List<String> maximum = task.workspaceMode() == ricbot.domain.task.TaskWorkspaceMode.SHARED_READ
                ? READ_ONLY_TOOLS : ALLOWED_TOOLS;
        if (task.allowedTools().isEmpty()) return maximum;
        return maximum.stream().filter(task.allowedTools()::contains).toList();
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private TaskWorkerStatus statusFor(AgentRunResult result, List<String> changedFiles, boolean hasToolErrors) {
        if ("no_exposed_tools".equalsIgnoreCase(clean(result.getStopReason()))) {
            return TaskWorkerStatus.FAILED;
        }
        if (!changedFiles.isEmpty()) {
            return TaskWorkerStatus.APPLIED;
        }
        return hasToolErrors ? TaskWorkerStatus.FAILED : TaskWorkerStatus.NO_CHANGES;
    }

    private String noChangesReason(AgentRunResult result, List<String> toolCallNames, String rawStatus) {
        if ("no_exposed_tools".equalsIgnoreCase(clean(result.getStopReason()))) {
            return !clean(result.getError()).isBlank() ? clean(result.getError()) : "no exposed tools";
        }
        if (toolCallNames.isEmpty()) {
            return "no tool calls";
        }
        boolean wrote = toolCallNames.stream().anyMatch(name -> name.equals("write_file") || name.equals("edit_file"));
        if (!wrote) {
            return "only read/list tools called";
        }
        if (!clean(rawStatus).isBlank()) {
            return "all changes filtered as runtime artifacts";
        }
        return "write tool called but no user diff";
    }

    private List<String> debugLines(
            TaskWorkerRequest task,
            WorkspaceSession workspaceSession,
            Path root,
            String sessionKey,
            AgentRunResult result,
            WorkspaceLifecycleService.WorkspaceDiff beforeDiff,
            WorkspaceLifecycleService.WorkspaceDiff afterDiff,
            String rawStatus,
            String noChangesReason
    ) {
        List<String> out = new ArrayList<>();
        out.add("debug:workerSessionKey=" + sessionKey);
        out.add("debug:workerWorkspaceRoot=" + root);
        out.add("debug:workerTaskId=" + task.taskId());
        out.add("debug:workerWorkspaceSessionId=" + (workspaceSession != null ? workspaceSession.id() : "shared-read"));
        out.add("debug:allowedTools=" + String.join(",", workerToolNames(task)));
        out.add("debug:registeredTools=" + String.join(",", valuesFromExposure(result, "registered_tools")));
        out.add("debug:exposedTools=" + String.join(",", valuesFromExposure(result, "exposed_tools")));
        List<String> missing = valuesFromExposure(result, "missing_allowed_tools");
        if (!missing.isEmpty()) {
            out.add("warning:missingAllowedTools=" + String.join(",", missing));
        }
        out.add("debug:modelToolCalls=" + String.join(",", requestedToolNames(result)));
        out.add("debug:toolResults=" + toolResults(result));
        out.add("debug:beforeChangedFiles=" + String.join(",", beforeDiff.changedFiles()));
        out.add("debug:afterChangedFiles=" + String.join(",", afterDiff.changedFiles()));
        out.add("debug:gitStatusShort=" + abbreviate(clean(rawStatus).replace('\n', '|'), 240));
        out.add("debug:workerFinalText=" + abbreviate(clean(result.getFinalContent()).replace('\n', ' '), 240));
        if (!clean(noChangesReason).isBlank()) {
            out.add("reason:" + noChangesReason);
        }
        return out;
    }

    private List<String> valuesFromExposure(AgentRunResult result, String key) {
        return runEvents(result).stream()
                .filter(event -> "tool_exposure".equals(String.valueOf(event.get("type"))))
                .findFirst()
                .map(event -> stringList(event.get(key)))
                .orElse(List.of());
    }

    private List<String> requestedToolNames(AgentRunResult result) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> event : runEvents(result)) {
            if ("tool_batch".equals(String.valueOf(event.get("type")))) {
                out.addAll(stringList(event.get("tools")));
            }
        }
        return out.stream().distinct().toList();
    }

    private String toolResults(AgentRunResult result) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> event : result.getToolEvents() != null ? result.getToolEvents() : List.<Map<String, Object>>of()) {
            String name = clean(String.valueOf(event.getOrDefault("name", "")));
            String status = clean(String.valueOf(event.getOrDefault("status", "")));
            if (!name.isBlank()) {
                out.add(name + ":" + (!status.isBlank() ? status : "unknown"));
            }
        }
        return out.isEmpty() ? "none" : String.join(",", out);
    }

    private List<Map<String, Object>> runEvents(AgentRunResult result) {
        return result != null && result.getRunEvents() != null ? result.getRunEvents() : List.of();
    }

    private List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }

    private String rawGitStatus(Path root) {
        try {
            Process process = new ProcessBuilder("git", "status", "--porcelain", "-uall")
                    .directory(root.toFile())
                    .start();
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            int code = process.waitFor();
            return code == 0 ? stdout.trim() : stderr.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String abbreviate(String value, int max) {
        String clean = value != null ? value.trim() : "";
        if (clean.length() <= max) {
            return clean;
        }
        return clean.substring(0, Math.max(0, max)) + "...";
    }

    private static final class GuardedTool extends Tool {
        private final Tool delegate;
        private final Path root;
        private final boolean write;

        private GuardedTool(Tool delegate, Path root, boolean write) {
            this.delegate = delegate;
            this.root = root.toAbsolutePath().normalize();
            this.write = write;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }

        @Override
        public ricbot.tool.api.ToolEffectPolicy effectPolicy() { return delegate.effectPolicy(); }

        @Override
        public List<ToolParam> getParams() {
            return delegate.getParams();
        }

        @Override
        public Map<String, Object> castParams(Map<String, Object> params) {
            return delegate.castParams(params);
        }

        @Override
        public List<String> validateParams(Map<String, Object> params) {
            return delegate.validateParams(params);
        }

        @Override
        public Object execute(Map<String, Object> params) throws Exception {
            String denied = guard(params);
            if (!denied.isBlank()) {
                return "错误：" + denied;
            }
            Map<String, Object> safe = params != null ? params : Map.of();
            if (delegate instanceof ListDirTool tool) {
                return tool.execute((String) safe.get("path"));
            }
            if (delegate instanceof ReadFileTool tool) {
                return tool.execute(
                        (String) safe.get("path"),
                        (Integer) safe.get("offset"),
                        (Integer) safe.get("limit")
                );
            }
            if (delegate instanceof WriteFileTool tool) {
                return tool.execute(safe);
            }
            if (delegate instanceof EditFileTool tool) {
                return tool.execute(safe);
            }
            if (delegate instanceof GrepTool tool) {
                return tool.execute(
                        (String) safe.get("pattern"),
                        (String) safe.get("base_dir"),
                        (String) safe.get("file_glob"),
                        (Boolean) safe.get("ignore_case"),
                        (Integer) safe.get("max_results")
                );
            }
            if (delegate instanceof GlobTool tool) {
                return tool.execute(
                        (String) safe.get("pattern"),
                        (String) safe.get("base_dir")
                );
            }
            return delegate.execute(safe);
        }

        private String guard(Map<String, Object> params) {
            Map<String, Object> safe = params != null ? params : Map.of();
            List<String> keys = new ArrayList<>();
            keys.add("path");
            keys.add("base_dir");
            for (String key : keys) {
                Object raw = safe.get(key);
                if (raw == null || String.valueOf(raw).isBlank()) {
                    continue;
                }
                String error = checkPath(String.valueOf(raw), write && "path".equals(key));
                if (!error.isBlank()) {
                    return error;
                }
            }
            return "";
        }

        private String checkPath(String rawPath, boolean writePath) {
            Path path = Path.of(rawPath);
            Path resolved = (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
            if (!resolved.startsWith(root)) {
                return "team worker path escapes worktree: " + rawPath;
            }
            String relative = root.relativize(resolved).toString().replace('\\', '/');
            if (relative.equals(".git") || relative.startsWith(".git/")) {
                return "team worker cannot access .git paths";
            }
            if (writePath && RuntimeArtifactFilter.isRuntimeArtifact(relative)) {
                return "team worker cannot modify runtime artifact: " + relative;
            }
            return "";
        }
    }
}
