package ricbot.domain.runtime;

import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TaskResult;

public record TaskView(TaskRecord task, TaskResult result) { }
