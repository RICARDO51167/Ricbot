package ricbot.domain.task;

/** Persists exactly-once child delivery before notifying an in-process parent. */
@FunctionalInterface
public interface ParentRunWaker {
    String deliver(TaskResult result);
}
