package ricbot.infra.common;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 熔断器实现，用于在外部服务不可用时快速失败，避免雪崩效应。
 */
public class CircuitBreaker {

    private final int failureThreshold;
    private final long resetTimeoutMs;

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    public CircuitBreaker(int failureThreshold, long resetTimeoutMs) {
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
    }

    public boolean allowRequest() {
        if (failureCount.get() >= failureThreshold) {
            long now = System.currentTimeMillis();
            if (now - lastFailureTime.get() > resetTimeoutMs) {
                return true;
            }
            return false; 
        }
        return true; 
    }

    public void recordSuccess() {
        failureCount.set(0);
    }

    public void recordFailure() {
        failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
    }

    public <T> T execute(Callable<T> task) throws Exception {
        if (!allowRequest()) {
            throw new CircuitBreakerOpenException("熔断器已开启。请求快速失败。");
        }

        try {
            T result = task.call();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    /**
     * 熔断器开启时抛出的异常
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
}
