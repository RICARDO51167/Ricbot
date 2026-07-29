package ricbot.domain.workspace.interfacep;

import ricbot.domain.workspace.dto.WorkspaceSession;
import ricbot.domain.workspace.enump.WorkspaceSessionStatus;

import java.nio.file.Path;

public interface WorkspaceBackend {
    WorkspaceSession createSession(Path baseWorkspace, String goal);

    Path getWorkspacePath(String sessionId);

    WorkspaceSessionStatus status(String sessionId);

    String diff(String sessionId);

    WorkspaceSession cleanup(String sessionId);

    boolean supports(Path baseWorkspace);
}
