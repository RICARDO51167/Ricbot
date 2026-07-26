package ricbot.domain.task;

import java.nio.file.Path;

public record TaskWorkspaceLease(String workspaceId, String parentRunId, String taskId, Path path,
                                 boolean integration) {
    public TaskWorkspaceLease {
        if (workspaceId == null || workspaceId.isBlank() || parentRunId == null || parentRunId.isBlank()
                || path == null) throw new IllegalArgumentException("workspace lease identity is required");
        taskId = taskId != null ? taskId : "";
        path = path.toAbsolutePath().normalize();
    }
}
