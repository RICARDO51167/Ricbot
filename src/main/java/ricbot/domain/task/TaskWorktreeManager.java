package ricbot.domain.task;

import ricbot.domain.workspace.GitWorktreeWorkspaceBackend;
import ricbot.domain.workspace.WorkspaceLifecycleService;
import ricbot.domain.workspace.WorkspaceSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** Creates one integration worktree per parent run and one isolated worktree per writing task. */
public final class TaskWorktreeManager {
    private final Path baseWorkspace;
    private final GitWorktreeWorkspaceBackend backend;
    private final WorkspaceLifecycleService lifecycle;

    public TaskWorktreeManager(Path baseWorkspace) {
        this.baseWorkspace = baseWorkspace.toAbsolutePath().normalize();
        backend = new GitWorktreeWorkspaceBackend(this.baseWorkspace);
        lifecycle = new WorkspaceLifecycleService(this.baseWorkspace);
    }

    public synchronized TaskWorkspaceLease integration(String parentRunId) {
        String id = safe("integration-" + parentRunId);
        try {
            WorkspaceSession existing = lifecycle.resolveManagedWorktree(id);
            return lease(existing, parentRunId, "", true);
        } catch (IllegalArgumentException ignored) {
            WorkspaceSession created = backend.createSession(baseWorkspace, "Integration for " + parentRunId, id);
            return lease(created, parentRunId, "", true);
        }
    }

    public synchronized TaskWorkspaceLease worker(TaskSpec task) {
        if (task.workspaceMode() != TaskWorkspaceMode.ISOLATED_WORKTREE) {
            throw new IllegalArgumentException("task does not request an isolated worktree: " + task.taskId());
        }
        TaskWorkspaceLease integration = integration(task.parentRunId());
        String id = safe("task-" + task.taskId());
        WorkspaceSession session;
        try { session = lifecycle.resolveManagedWorktree(id); }
        catch (IllegalArgumentException ignored) {
            session = backend.createSession(baseWorkspace, task.goal(), id);
            String integrationPatch = lifecycle.diff(integration.workspaceId()).patch();
            if (!integrationPatch.isBlank()) apply(sessionPath(session), integrationPatch);
        }
        return lease(session, task.parentRunId(), task.taskId(), false);
    }

    public TaskResult attachPatch(TaskResult result, TaskWorkspaceLease lease) {
        WorkspaceLifecycleService.WorkspaceDiff diff = lifecycle.diff(lease.workspaceId());
        return new TaskResult(2, result.taskId(), result.parentRunId(), result.childRunId(), result.attempt(), result.status(),
                result.planOrder(), result.summary(), result.artifacts(), diff.patch(), diff.changedFiles(),
                result.testEvidence(), result.error(), result.completedAt());
    }

    private static TaskWorkspaceLease lease(WorkspaceSession session, String parentRunId, String taskId,
                                            boolean integration) {
        return new TaskWorkspaceLease(session.id(), parentRunId, taskId, sessionPath(session), integration);
    }
    private static Path sessionPath(WorkspaceSession session) {
        return Path.of(session.workspacePath()).toAbsolutePath().normalize();
    }
    private static void apply(Path directory, String patch) {
        runWithInput(directory, patch, "git", "apply", "--check", "-");
        runWithInput(directory, patch, "git", "apply", "-");
    }
    private static String runWithInput(Path directory, String input, String... command) {
        try {
            Process process = new ProcessBuilder(List.of(command)).directory(directory.toFile()).start();
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) throw new IllegalStateException(stderr.isBlank() ? stdout : stderr);
            return stdout;
        } catch (IOException e) { throw new IllegalStateException("cannot apply integration snapshot", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("patch apply interrupted", e); }
    }
    private static String safe(String value) {
        String clean = value.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return clean.length() <= 80 ? clean : clean.substring(0, 80);
    }
}
