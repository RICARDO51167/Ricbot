package ricbot.domain.agent;

import ricbot.domain.task.TaskExecutionContext;
import ricbot.domain.task.TaskExecutor;
import ricbot.domain.task.TaskResult;
import ricbot.domain.task.TaskStatus;
import ricbot.domain.task.TaskWorkspaceLease;
import ricbot.domain.task.TaskWorkspaceMode;
import ricbot.domain.task.TaskWorktreeManager;
import ricbot.domain.task.TaskWorkerRequest;
import ricbot.domain.task.TaskWorkerResult;
import ricbot.domain.task.TaskWorkerRunner;
import ricbot.domain.task.TaskWorkerStatus;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Adapts the existing model-backed worker to scheduler-owned TaskRecord/TaskResult facts. */
public final class LocalTeamTaskExecutor implements TaskExecutor {
    private final TaskWorktreeManager worktrees;
    private final WorkspaceSessionStore sessions;
    private final TaskWorkerRunner runner;

    public LocalTeamTaskExecutor(java.nio.file.Path workspace, TaskWorkerRunner runner) {
        worktrees = new TaskWorktreeManager(workspace);
        sessions = new WorkspaceSessionStore(workspace);
        this.runner = runner;
    }

    @Override
    public TaskResult execute(TaskExecutionContext context) {
        var spec = context.task().spec();
        // Read workers are also isolated when using this legacy worker adapter; this is stricter than shared-read.
        var isolatedSpec = spec.workspaceMode() == TaskWorkspaceMode.ISOLATED_WORKTREE ? spec
                : new ricbot.domain.task.TaskSpec(spec.taskId(), spec.parentRunId(), spec.activationId(), spec.planOrder(),
                    spec.delegationDepth(), spec.role(), spec.goal(), spec.dependsOn(), spec.allowedTools(),
                    TaskWorkspaceMode.ISOLATED_WORKTREE, spec.failurePolicy(), spec.allowFailedDependencies());
        TaskWorkspaceLease lease = worktrees.worker(isolatedSpec);
        WorkspaceSession workspaceSession = sessions.load(lease.workspaceId());
        TaskWorkerRequest task = new TaskWorkerRequest(spec.taskId(), spec.parentRunId(), spec.role(),
                dependencyPrompt(spec.goal(), context.dependencyResults()));
        TaskWorkerResult worker = runner.run(task, workspaceSession, lease.path());
        TaskStatus status = worker.status() == TaskWorkerStatus.FAILED ? TaskStatus.FAILED : TaskStatus.SUCCEEDED;
        TaskResult result = new TaskResult(2, spec.taskId(), spec.parentRunId(), context.task().childRunId(), context.task().attempt(), status,
                spec.planOrder(), worker.summary(), Map.of("workspace", lease.path().toString(),
                        "workerRunId", worker.childRunId()), "", worker.changedFiles(), worker.debugLines(),
                worker.errorMessage(), Instant.now());
        return worktrees.attachPatch(result, lease);
    }

    private static String dependencyPrompt(String goal, List<TaskResult> dependencies) {
        if (dependencies.isEmpty()) return goal;
        String context = dependencies.stream().map(result -> result.taskId() + " [" + result.status() + "]: "
                + bounded(result.summary(), 2_000)).reduce((left, right) -> left + "\n" + right).orElse("");
        return goal + "\n\nDependency results:\n" + bounded(context, 6_000);
    }
    private static String bounded(String value, int max) {
        String clean = value != null ? value : "";
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
