package ricbot.domain.task;

import ricbot.domain.workspace.dto.WorkspaceSession;
import java.nio.file.Path;

@FunctionalInterface
public interface TaskWorkerRunner {
    TaskWorkerResult run(TaskWorkerRequest task, WorkspaceSession workspaceSession, Path workspaceRoot);
}
