package ricbot.domain.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WorkspaceLifecycleService {
    private final Path baseWorkspace;
    private final WorkspaceSessionStore store;

    public WorkspaceLifecycleService(Path baseWorkspace) {
        this.baseWorkspace = baseWorkspace.toAbsolutePath().normalize();
        this.store = new WorkspaceSessionStore(this.baseWorkspace);
    }

    public List<WorkspaceSession> activeWorktrees() {
        return store.list().stream()
                .filter(session -> session.type() == WorkspaceBackendType.GIT_WORKTREE)
                .filter(session -> session.status() == WorkspaceSessionStatus.ACTIVE)
                .toList();
    }

    public WorkspaceSession resolveManagedWorktree(String taskOrWorkspaceId) {
        String token = clean(taskOrWorkspaceId);
        if (token.isBlank()) {
            throw new IllegalArgumentException("workspace id or task id is required");
        }
        WorkspaceSession direct = store.load(token);
        WorkspaceSession session = direct != null ? direct : store.list().stream()
                .filter(candidate -> token.equals(String.valueOf(candidate.metadata().getOrDefault("taskId", "")).trim()))
                .findFirst()
                .orElse(null);
        if (session == null) {
            throw new IllegalArgumentException("managed worktree not found for: " + token);
        }
        requireManagedWorktree(session);
        return session;
    }

    public WorkspaceStatus status(String taskOrWorkspaceId) {
        WorkspaceSession session = resolveManagedWorktree(taskOrWorkspaceId);
        Path root = safeWorktreePath(session);
        String branch = git(root, "rev-parse", "--abbrev-ref", "HEAD").trim();
        String statusShort = git(root, "status", "--short");
        return new WorkspaceStatus(session, branch, statusShort, !statusShort.isBlank());
    }

    public WorkspaceDiff diff(String taskOrWorkspaceId) {
        WorkspaceSession session = resolveManagedWorktree(taskOrWorkspaceId);
        Path root = safeWorktreePath(session);
        String stat = git(root, "diff", "--stat", "--");
        String patch = git(root, "diff", "--");
        List<String> changedFiles = statusPaths(root);
        return new WorkspaceDiff(session, stat, changedFiles, patch);
    }

    public WorkspaceSession discard(String taskOrWorkspaceId, boolean force) {
        if (!force) {
            throw new IllegalArgumentException("discard requires --force");
        }
        WorkspaceSession session = resolveManagedWorktree(taskOrWorkspaceId);
        return new GitWorktreeWorkspaceBackend(baseWorkspace, store).discard(session.id());
    }

    private List<String> statusPaths(Path root) {
        String output = git(root, "status", "--short");
        List<String> files = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.length() < 4) {
                continue;
            }
            String path = line.substring(3).trim();
            int rename = path.indexOf(" -> ");
            files.add(rename >= 0 ? path.substring(rename + 4).trim() : path);
        }
        return files.stream().filter(value -> !value.isBlank()).distinct().toList();
    }

    private void requireManagedWorktree(WorkspaceSession session) {
        if (session.type() != WorkspaceBackendType.GIT_WORKTREE) {
            throw new IllegalArgumentException("workspace is not a git worktree: " + session.id());
        }
        if (!"ricbot".equalsIgnoreCase(String.valueOf(session.metadata().getOrDefault("managedBy", "")))) {
            throw new IllegalStateException("workspace is not managed by ricbot: " + session.id());
        }
        safeWorktreePath(session);
    }

    private Path safeWorktreePath(WorkspaceSession session) {
        Path base = Path.of(session.baseWorkspace()).toAbsolutePath().normalize();
        if (!base.equals(baseWorkspace)) {
            throw new IllegalStateException("workspace base does not match configured workspace: " + session.id());
        }
        Path root = baseWorkspace.resolve(".workspaces").toAbsolutePath().normalize();
        Path path = Path.of(session.workspacePath()).toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalStateException("workspace path is outside safe .workspaces root: " + path);
        }
        return path;
    }

    private String git(Path directory, String... args) {
        Path dir = directory.toAbsolutePath().normalize();
        if (!dir.startsWith(baseWorkspace)) {
            throw new IllegalStateException("git command directory escapes baseWorkspace: " + dir);
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        try {
            Process process = pb.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException(stderr.isBlank() ? stdout : stderr);
            }
            return stdout;
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException("git command failed: " + String.join(" ", command), e);
        }
    }

    private String clean(String value) {
        return value != null ? value.trim() : "";
    }

    public record WorkspaceStatus(WorkspaceSession session, String branch, String statusShort, boolean dirty) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "workspaceId", session.id(),
                    "taskId", String.valueOf(session.metadata().getOrDefault("taskId", "")),
                    "path", session.workspacePath(),
                    "branch", branch != null ? branch : "",
                    "dirty", dirty,
                    "statusShort", statusShort != null ? statusShort : ""
            );
        }
    }

    public record WorkspaceDiff(WorkspaceSession session, String stat, List<String> changedFiles, String patch) {
        public WorkspaceDiff {
            stat = stat != null ? stat : "";
            changedFiles = changedFiles != null ? List.copyOf(changedFiles) : List.of();
            patch = patch != null ? patch : "";
        }
    }
}
