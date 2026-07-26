package ricbot.domain.task;

/** Task projection capable of committing result, terminal state and parent delivery as one fact transaction. */
public interface TransactionalTaskStore extends TaskStore {
    TaskRecord settleAndDeliver(TaskRecord current, TaskResult result);
}
