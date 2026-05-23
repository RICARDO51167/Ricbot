package ricbot.domain.team;

import ricbot.domain.workspace.WorkspaceSession;

import java.nio.file.Path;

@FunctionalInterface
public interface TeamWorkerRunner {
    TeamWorkerResult run(TeamTask task, WorkspaceSession workspaceSession, Path workspaceRoot);
}
