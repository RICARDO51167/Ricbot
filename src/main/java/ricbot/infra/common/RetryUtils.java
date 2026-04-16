package ricbot.infra.common;

import java.util.concurrent.Callable;

/**
 * 通用的指数退避重试和熔断机制。
 */
public class RetryUtils {

    public static <T> T withExponentialBackoff(Callable<T> task, int maxRetries, long initialWait, double factor) throws Exception {
        long currentWait = initialWait;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return task.call();
            } catch (CircuitBreaker.CircuitBreakerOpenException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(currentWait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                    currentWait = (long) (currentWait * factor);
                }
            }
        }
        
        throw lastException;
    }

    public static <T> T executeWithRetry(Callable<T> task) throws Exception {
        return withExponentialBackoff(task, 3, 1000, 2.0);
    }
}
