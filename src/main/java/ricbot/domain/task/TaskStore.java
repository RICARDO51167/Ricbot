package ricbot.domain.task;

import java.util.List;
import java.util.Optional;

public interface TaskStore {
    TaskRecord create(TaskRecord record);
    Optional<TaskRecord> load(String taskId);
    TaskRecord save(TaskRecord record, long expectedVersion);
    List<TaskRecord> listByParent(String parentRunId);
    List<TaskRecord> listAll();
    TaskResult saveResult(TaskResult result);
    Optional<TaskResult> loadResult(String taskId);
}
