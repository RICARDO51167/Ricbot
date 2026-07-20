package ricbot.domain.workspace;

import java.nio.file.Path;

public interface WorkspaceBackend {
    WorkspaceSession createSession(Path baseWorkspace, String goal);

    Path getWorkspacePath(String sessionId);

    WorkspaceSessionStatus status(String sessionId);

    String diff(String sessionId);

    WorkspaceSession cleanup(String sessionId);

    boolean supports(Path baseWorkspace);
}
