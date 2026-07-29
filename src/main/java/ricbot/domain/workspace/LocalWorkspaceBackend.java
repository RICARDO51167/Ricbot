package ricbot.domain.workspace;

import ricbot.domain.workspace.dto.WorkspaceSession;
import ricbot.domain.workspace.enump.WorkspaceBackendType;
import ricbot.domain.workspace.enump.WorkspaceSessionStatus;
import ricbot.domain.workspace.interfacep.WorkspaceBackend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LocalWorkspaceBackend implements WorkspaceBackend {
    private final WorkspaceSessionStore store;

    public LocalWorkspaceBackend(Path baseWorkspace) {
        this.store = new WorkspaceSessionStore(baseWorkspace);
    }

    public LocalWorkspaceBackend(WorkspaceSessionStore store) {
        this.store = store;
    }

    @Override
    public WorkspaceSession createSession(Path baseWorkspace, String goal) {
        Path base = normalize(baseWorkspace);
        WorkspaceSession session = new WorkspaceSession(
                WorkspaceSession.newId(),
                WorkspaceBackendType.LOCAL,
                base.toString(),
                base.toString(),
                "",
                goal,
                WorkspaceSessionStatus.ACTIVE,
                null,
                null,
                Map.of("mode", "local")
        );
        return store.save(session);
    }

    @Override
    public Path getWorkspacePath(String sessionId) {
        WorkspaceSession session = require(sessionId);
        return normalize(Path.of(session.workspacePath()));
    }

    @Override
    public WorkspaceSessionStatus status(String sessionId) {
        return require(sessionId).status();
    }

    @Override
    public String diff(String sessionId) {
        WorkspaceSession session = require(sessionId);
        Path path = normalize(Path.of(session.workspacePath()));
        if (!isGitRepository(path)) {
            return "";
        }
        return git(path, "diff", "--");
    }

    @Override
    public WorkspaceSession cleanup(String sessionId) {
        return store.cleanup(sessionId);
    }

    @Override
    public boolean supports(Path baseWorkspace) {
        return baseWorkspace != null;
    }

    private WorkspaceSession require(String sessionId) {
        WorkspaceSession session = store.load(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("workspace session not found: " + sessionId);
        }
        if (session.type() != WorkspaceBackendType.LOCAL) {
            throw new IllegalArgumentException("workspace session is not LOCAL: " + sessionId);
        }
        return session;
    }

    private boolean isGitRepository(Path path) {
        try {
            git(path, "rev-parse", "--is-inside-work-tree");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String git(Path directory, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory.toFile());
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
}
