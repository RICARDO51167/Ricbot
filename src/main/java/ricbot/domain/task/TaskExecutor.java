package ricbot.domain.task;

@FunctionalInterface
public interface TaskExecutor {
    TaskResult execute(TaskExecutionContext context) throws Exception;
}
