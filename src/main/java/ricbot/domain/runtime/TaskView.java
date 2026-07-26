package ricbot.domain.runtime;

import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TaskResult;
import java.util.List;

public record TaskView(TaskRecord task, List<TaskResult> attempts, TaskResult result) {
    public TaskView { attempts = List.copyOf(attempts != null ? attempts : List.of()); }
    public TaskView(TaskRecord task, TaskResult result) {
        this(task, result != null ? List.of(result) : List.of(), result);
    }
}
