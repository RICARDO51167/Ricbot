package ricbot.domain.agent.graph;

import ricbot.domain.task.DurableParentRunWaker;
import ricbot.domain.task.TaskDelivery;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.time.Duration;

/** Connects durable child completion deliveries to a live or recovered parent graph run. */
public final class GraphRunCoordinator implements AutoCloseable {
    private final AgentGraphRuntime runtime;
    private final DurableParentRunWaker waker;
    private final Supplier<Map<String, Object>> inputSupplier;
    private final java.util.concurrent.atomic.AtomicBoolean deliveryAvailable = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.locks.ReentrantLock deliveryLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition deliveryArrived = deliveryLock.newCondition();

    public GraphRunCoordinator(AgentGraphRuntime runtime, DurableParentRunWaker waker,
                               Supplier<Map<String, Object>> inputSupplier) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.waker = Objects.requireNonNull(waker, "waker");
        this.inputSupplier = inputSupplier != null ? inputSupplier : Map::of;
        // Delivery callbacks may run while the task scheduler holds its own lock. They only
        // signal availability; the coordinator's driver performs the graph mutation.
        waker.register(runtime.state().runId(), ignored -> {
            deliveryAvailable.set(true);
            deliveryLock.lock();
            try { deliveryArrived.signalAll(); }
            finally { deliveryLock.unlock(); }
        });
    }

    /** Drives synchronously until terminal or an external wait. */
    public synchronized GraphExecutionState drive() {
        while (!runtime.state().status().terminal()) {
            if (runtime.state().status() == GraphExecutionStatus.PAUSED
                    || runtime.state().status() == GraphExecutionStatus.WAITING) {
                if (!injectPending()) break;
            } else {
                runtime.executeOne(inputSupplier.get());
            }
        }
        return runtime.state();
    }

    /** Atomically materializes every undelivered child result, then resumes the parent. */
    public synchronized GraphExecutionState resumePending() {
        injectPending();
        return drive();
    }

    private boolean injectPending() {
        List<TaskDelivery> deliveries = waker.pending(runtime.state().runId());
        if (deliveries.isEmpty() || runtime.state().status().terminal()) {
            deliveryAvailable.set(false);
            return false;
        }
        if (runtime.state().status() != GraphExecutionStatus.PAUSED
                && runtime.state().status() != GraphExecutionStatus.WAITING
                && runtime.state().status() != GraphExecutionStatus.RECOVERING) return false;
        List<Object> orderedResults = deliveries.stream().map(delivery -> (Object) delivery.result()).toList();
        runtime.resume(Map.of("workerResults", orderedResults),
                deliveries.stream().map(TaskDelivery::deliveryId).toList());
        deliveries.forEach(delivery -> waker.acknowledge(delivery.parentRunId(), delivery.deliveryId()));
        deliveryAvailable.set(false);
        return true;
    }

    public GraphExecutionState state() { return runtime.state(); }

    public GraphExecutionState awaitTerminalOrHumanPause(Duration timeout) {
        long deadline = System.nanoTime() + (timeout != null ? timeout : Duration.ofHours(2)).toNanos();
        drive();
        while (System.nanoTime() < deadline) {
            GraphExecutionState current = resumePending();
            if (current.status().terminal()) return current;
            if (!current.waits().isEmpty() && current.waits().stream().anyMatch(wait -> !"tasks".equals(wait.type()))) {
                return current;
            }
            deliveryLock.lock();
            try {
                if (!deliveryAvailable.get()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0) deliveryArrived.awaitNanos(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return runtime.state();
            } finally {
                deliveryLock.unlock();
            }
        }
        return runtime.state();
    }

    @Override
    public void close() {
        waker.unregister(runtime.state().runId());
        runtime.close();
    }
}
