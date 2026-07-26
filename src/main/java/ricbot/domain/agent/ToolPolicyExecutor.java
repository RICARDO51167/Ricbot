package ricbot.domain.agent;

import ricbot.tool.api.ToolEffectPolicy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/** Enforces the declared timeout and concurrency contract around an external tool call. */
final class ToolPolicyExecutor {
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final ExecutorService CALLS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ricbot-tool-effect");
        thread.setDaemon(true);
        return thread;
    });

    private ToolPolicyExecutor() { }

    static <T> T invoke(ToolEffectPolicy policy, SideEffectExecutionIdentity identity,
                        String toolName, Callable<T> call) {
        ToolEffectPolicy required = java.util.Objects.requireNonNull(policy, "tool policy");
        if (!required.declared()) throw new IllegalStateException("tool effect policy is undeclared");
        String lockKey = switch (required.concurrency()) {
            case SHARED -> "";
            case SERIAL_PER_RUN -> "run:" + identity.runId() + ":" + toolName;
            case EXCLUSIVE_WORKSPACE -> "exclusive:" + toolName;
        };
        Future<T> future = CALLS.submit(() -> {
            ReentrantLock lock = lockKey.isBlank() ? null : LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
            if (lock != null) lock.lockInterruptibly();
            try { return call.call(); }
            finally { if (lock != null && lock.isHeldByCurrentThread()) lock.unlock(); }
        });
        Duration timeout = required.timeout();
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true);
            throw new ToolInvocationTimeoutException("tool timed out after " + timeout, timeoutFailure);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool execution interrupted", interrupted);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause != null ? cause.getMessage() : "tool execution failed", cause);
        }
    }

    static final class ToolInvocationTimeoutException extends IllegalStateException {
        ToolInvocationTimeoutException(String message, Throwable cause) { super(message, cause); }
    }
}
