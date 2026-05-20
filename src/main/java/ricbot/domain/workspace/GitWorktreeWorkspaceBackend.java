package ricbot.domain.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class GitWorktreeWorkspaceBackend implements WorkspaceBackend {
    private final Path baseWorkspace;
    private final WorkspaceSessionStore store;

    public GitWorktreeWorkspaceBackend(Path baseWorkspace) {
        this.baseWorkspace = normalize(baseWorkspace);
        this.store = new WorkspaceSessionStore(this.baseWorkspace);
    }

    public GitWorktreeWorkspaceBackend(Path baseWorkspace, WorkspaceSessionStore store) {
        this.baseWorkspace = normalize(baseWorkspace);
        this.store = store;
    }

    @Override
    public WorkspaceSession createSession(Path baseWorkspace, String goal) {
        Path base = normalize(baseWorkspace);
        ensureInsideConfiguredBase(base);
        ensureGitRepository(base);
        String id = WorkspaceSession.newId();
        String branch = "ricbot/" + id;
        Path workspacesRoot = safeWorkspacesRoot(base);
        Path workspacePath = workspacesRoot.resolve(id).normalize();
        ensureInsideWorkspaces(base, workspacePath);
        try {
            Files.createDirectories(workspacesRoot);
            git(base, "worktree", "add", "-b", branch, workspacePath.toString());
        } catch (Exception e) {
            throw new IllegalStateException("git worktree create failed: " + cleanMessage(e), e);
        }
        WorkspaceSession session = new WorkspaceSession(
                id,
                WorkspaceBackendType.GIT_WORKTREE,
                base.toString(),
                workspacePath.toString(),
                branch,
                goal,
                WorkspaceSessionStatus.ACTIVE,
                null,
                null,
                Map.of("mode", "worktree")
        );
        return store.save(session);
    }

    @Override
    public Path getWorkspacePath(String sessionId) {
        return normalize(Path.of(require(sessionId).workspacePath()));
    }

    @Override
    public WorkspaceSessionStatus status(String sessionId) {
        return require(sessionId).status();
    }

    @Override
    public String diff(String sessionId) {
        WorkspaceSession session = require(sessionId);
        Path path = normalize(Path.of(session.workspacePath()));
        ensureInsideWorkspaces(Path.of(session.baseWorkspace()), path);
        return git(path, "diff", "--");
    }

    @Override
    public WorkspaceSession cleanup(String sessionId) {
        WorkspaceSession session = require(sessionId);
        Path base = normalize(Path.of(session.baseWorkspace()));
        ensureInsideConfiguredBase(base);
        Path path = normalize(Path.of(session.workspacePath()));
        ensureInsideWorkspaces(base, path);
        if (Files.exists(path)) {
            try {
                Files.deleteIfExists(path.resolve("session.json"));
                git(base, "worktree", "remove", path.toString());
            } catch (Exception e) {
                try {
                    store.save(session);
                } catch (Exception ignored) {
                }
                throw new IllegalStateException("git worktree cleanup failed: " + cleanMessage(e), e);
            }
        }
        return store.save(session.withStatus(WorkspaceSessionStatus.CLEANED));
    }

    @Override
    public boolean supports(Path baseWorkspace) {
        try {
            Path base = normalize(baseWorkspace);
            ensureInsideConfiguredBase(base);
            ensureGitRepository(base);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void conservativeDeleteIfSafe(Path candidate) {
        Path target = normalize(candidate);
        ensureInsideWorkspaces(baseWorkspace, target);
        try {
            if (!Files.exists(target)) {
                return;
            }
            try (var walk = Files.walk(target)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("workspace cleanup delete failed: " + target, e);
        }
    }

    private WorkspaceSession require(String sessionId) {
        WorkspaceSession session = store.load(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("workspace session not found: " + sessionId);
        }
        if (session.type() != WorkspaceBackendType.GIT_WORKTREE) {
            throw new IllegalArgumentException("workspace session is not GIT_WORKTREE: " + sessionId);
        }
        return session;
    }

    private void ensureGitRepository(Path base) {
        try {
            git(base, "rev-parse", "--is-inside-work-tree");
        } catch (Exception e) {
            throw new IllegalStateException("baseWorkspace is not a git repository: " + base);
        }
    }

    private void ensureInsideConfiguredBase(Path base) {
        if (!base.equals(baseWorkspace)) {
            throw new IllegalArgumentException("baseWorkspace must match configured workspace: " + baseWorkspace);
        }
    }

    private Path safeWorkspacesRoot(Path base) {
        Path root = base.resolve(".workspaces").normalize();
        if (!root.startsWith(base)) {
            throw new IllegalStateException(".workspaces path escapes baseWorkspace: " + root);
        }
        return root;
    }

    private void ensureInsideWorkspaces(Path baseWorkspace, Path path) {
        Path base = normalize(baseWorkspace);
        Path root = safeWorkspacesRoot(base);
        Path normalized = normalize(path);
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IllegalStateException("workspace path is outside safe .workspaces root: " + normalized);
        }
    }

    private String git(Path directory, String... args) {
        Path dir = normalize(directory);
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

    private Path normalize(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("baseWorkspace is required");
        }
        return path.toAbsolutePath().normalize();
    }

    private String cleanMessage(Exception e) {
        String message = e != null ? e.getMessage() : "";
        return message == null || message.isBlank() ? "unknown error" : message.trim();
    }
}
