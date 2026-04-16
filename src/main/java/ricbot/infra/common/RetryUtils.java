package ricbot.infra.common;

import java.util.concurrent.Callable;

/**
 * 通用的指数退避重试和熔断机制。
 */
public class RetryUtils {

    /**
     * 带有指数退避重试的执行包装器。
     *
     * @param task        要执行的任务
     * @param maxRetries  最大重试次数
     * @param initialWait 初始等待时间（毫秒）
     * @param factor      指数因子
     * @param <T>         返回类型
     * @return 任务的返回值
     * @throws Exception 抛出最后一次失败的异常
     */
    public static <T> T withExponentialBackoff(Callable<T> task, int maxRetries, long initialWait, double factor) throws Exception {
        // 初始化当前等待时间为初始等待时间
        long currentWait = initialWait;
        // 用于记录最后一次捕获的异常
        Exception lastException = null;

        // 循环执行任务，最多尝试 maxRetries + 1 次（首次执行 + maxRetries 次重试）
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // 尝试执行任务，如果成功则直接返回结果
                return task.call();
            } catch (CircuitBreaker.CircuitBreakerOpenException e) {
                // 如果熔断器开启，直接抛出异常，不进行重试
                throw e;
            } catch (Exception e) {
                // 捕获其他异常，记录为最后一次异常
                lastException = e;
                // 如果当前尝试次数小于最大重试次数，则进行等待和下一次重试
                if (attempt < maxRetries) {
                    try {
                        // 根据当前等待时间休眠线程
                        Thread.sleep(currentWait);
                    } catch (InterruptedException ie) {
                        // 如果休眠被中断，恢复中断状态并抛出运行时异常
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                    // 计算下一次的等待时间，应用指数退避策略
                    currentWait = (long) (currentWait * factor);
                }
            }
        }
        
        // 如果所有尝试都失败，抛出最后一次捕获的异常
        throw lastException;
    }

    /**
     * 默认配置的重试方法（3次重试，1秒起步，2倍指数）。
     */
    public static <T> T executeWithRetry(Callable<T> task) throws Exception {
        // 使用默认参数调用指数退避重试方法
        return withExponentialBackoff(task, 3, 1000, 2.0);
    }
}
