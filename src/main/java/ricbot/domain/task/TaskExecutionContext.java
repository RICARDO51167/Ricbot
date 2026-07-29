package ricbot.domain.task;

import java.nio.file.Path;
import java.util.List;

public record TaskExecutionContext(TaskRecord task, List<TaskResult> dependencyResults, Path workspace,
                                   int siblingCount) {
    public TaskExecutionContext {
        dependencyResults = List.copyOf(dependencyResults != null ? dependencyResults : List.of());
        siblingCount = Math.max(1, siblingCount);
    }
    public TaskExecutionContext(TaskRecord task, List<TaskResult> dependencyResults, Path workspace) {
        this(task, dependencyResults, workspace, 1);
    }
}
