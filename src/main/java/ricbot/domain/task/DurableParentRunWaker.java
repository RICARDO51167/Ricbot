package ricbot.domain.task;

import java.util.List;
import java.util.function.Consumer;

/** Durable delivery outbox consumed by the parent graph. */
public interface DurableParentRunWaker extends ParentRunWaker {
    List<TaskDelivery> pending(String parentRunId);
    void acknowledge(String parentRunId, String deliveryId);
    default void register(String parentRunId, Consumer<TaskDelivery> listener) { }
    default void unregister(String parentRunId) { }
}
