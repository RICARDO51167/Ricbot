package ricbot.domain.task;

import java.nio.file.Path;
import java.util.List;

public record TaskExecutionContext(TaskRecord task, List<TaskResult> dependencyResults, Path workspace) {
    public TaskExecutionContext {
        dependencyResults = List.copyOf(dependencyResults != null ? dependencyResults : List.of());
    }
}
