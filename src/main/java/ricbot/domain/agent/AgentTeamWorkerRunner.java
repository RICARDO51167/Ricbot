package ricbot.domain.agent;

import ricbot.domain.config.ProviderCapability;
import ricbot.domain.team.TeamTask;
import ricbot.domain.team.TeamWorkerResult;
import ricbot.domain.team.TeamWorkerRunner;
import ricbot.domain.team.TeamWorkerStatus;
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

public class AgentTeamWorkerRunner implements TeamWorkerRunner {
    public static final List<String> ALLOWED_TOOLS = List.of(
            "list_dir",
            "read_file",
            "write_file",
            "edit_file",
            "grep",
            "glob"
    );

    private final Path baseWorkspace;
    private final AgentRunner runner;
    private final String model;
    private final int maxIterations;
    private final int maxToolResultChars;
    private final String providerRetryMode;
    private final int contextWindowTokens;
    private final Integer contextBlockLimit;
    private final ProviderCapability providerCapability;

    public AgentTeamWorkerRunner(Path baseWorkspace, AgentRunner runner, String model) {
        this(baseWorkspace, runner, model, 8, 10_000, "standard", 64_000, null, null);
    }

    public AgentTeamWorkerRunner(
            Path baseWorkspace,
            AgentRunner runner,
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
    public TeamWorkerResult run(TeamTask task, WorkspaceSession workspaceSession, Path workspaceRoot) {
        Instant started = Instant.now();
        if (task == null) {
            return TeamWorkerResult.failed("team task is required", 0);
        }
        if (runner == null) {
            return TeamWorkerResult.failed("AgentRunner is unavailable for team worker", 0);
        }
        Path root;
        try {
            root = safeWorktreeRoot(workspaceSession, workspaceRoot);
        } catch (Exception e) {
            return TeamWorkerResult.failed(e.getMessage(), 0);
        }
        String sessionKey = "team-worker-" + task.id();
        try {
            AgentRunResult result = runner.run(workerSpec(task, workspaceSession, root, sessionKey));
            WorkspaceLifecycleService.WorkspaceDiff diff = new WorkspaceLifecycleService(baseWorkspace)
                    .diff(workspaceSession.id());
            List<String> changedFiles = diff.changedFiles();
            TeamWorkerStatus status = changedFiles.isEmpty() ? TeamWorkerStatus.NO_CHANGES : TeamWorkerStatus.APPLIED;
            String summary = !clean(result.getFinalContent()).isBlank()
                    ? clean(result.getFinalContent())
                    : status == TeamWorkerStatus.APPLIED
                    ? "Team worker applied changes: " + String.join(", ", changedFiles)
                    : "Team worker completed but produced no user changes.";
            return new TeamWorkerResult(
                    status,
                    changedFiles,
                    summary,
                    changedFiles.stream().map(path -> "APPLY_CHANGE " + path).toList(),
                    clean(result.getError()),
                    Duration.between(started, Instant.now()).toMillis(),
                    clean(result.getRunId()),
                    ""
            );
        } catch (Exception e) {
            return TeamWorkerResult.failed(e.getMessage(), Duration.between(started, Instant.now()).toMillis());
        }
    }

    AgentRunSpec workerSpec(TeamTask task, WorkspaceSession workspaceSession, Path root, String sessionKey) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "team-worker");
        metadata.put("taskId", task.id());
        metadata.put("teamSessionId", task.sessionId());
        metadata.put("workspaceSessionId", workspaceSession != null ? workspaceSession.id() : "");
        metadata.put("workspaceRoot", root.toString());
        return new AgentRunSpec()
                .setInitialMessages(workerMessages(task, root))
                .setTools(workerTools(root))
                .setModel(model)
                .setMaxIterations(maxIterations)
                .setMaxToolResultChars(maxToolResultChars)
                .setProviderRetryMode(providerRetryMode)
                .setErrorMessage("Team worker failed while calling model.")
                .setMaxIterationsMessage("Team worker reached the tool iteration limit before completing the task.")
                .setConcurrentTools(false)
                .setWorkspace(root)
                .setSessionKey(sessionKey)
                .setContextWindowTokens(contextWindowTokens)
                .setContextBlockLimit(contextBlockLimit)
                .setProviderCapability(providerCapability)
                .setMetadata(metadata)
                .setAllowedTools(ALLOWED_TOOLS);
    }

    private List<Map<String, Object>> workerMessages(TeamTask task, Path root) {
        String system = """
                You are Ricbot Team worker.
                You run inside a managed git worktree and must make real file changes with tools when the task requires changes.
                First read the necessary files, then make the smallest safe change.
                If the task is documentation work, read the relevant document and edit/write it with tools.
                If the task is code work, locate the file first and make a minimal edit.
                Do not only output a plan. Do not claim a file changed unless a tool changed it.
                Do not call tools that are not provided.
                Do not modify runtime artifacts such as .git, .ricbot, .team, .traces, .changesets, .workspaces, notes, session.json, target, or logs.
                When finished, output a short summary and list changed files.
                """;
        String user = "Current task: " + task.goal()
                + "\nWorkspace root: " + root
                + "\nComplete real changes in this worktree. If impossible, explain why.";
        return List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        );
    }

    private ToolRegistry workerTools(Path root) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new GuardedTool(new ListDirTool(root, root), root, false));
        registry.register(new GuardedTool(new ReadFileTool(root, root, List.of()), root, false));
        registry.register(new GuardedTool(new WriteFileTool(root, root), root, true));
        registry.register(new GuardedTool(new EditFileTool(root, root), root, true));
        registry.register(new GuardedTool(new GrepTool(root, root), root, false));
        registry.register(new GuardedTool(new GlobTool(root, root), root, false));
        return registry;
    }

    private Path safeWorktreeRoot(WorkspaceSession workspaceSession, Path workspaceRoot) {
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

    private static String clean(String value) {
        return value != null ? value.trim() : "";
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
        public List<ToolParam> getParams() {
            return delegate.getParams();
        }

        @Override
        public boolean isReadOnly() {
            return delegate.isReadOnly();
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
            return delegate.execute(params);
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
