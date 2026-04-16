package ricbot.infra.common;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 熔断器实现，用于在外部服务不可用时快速失败，避免雪崩效应。
 */
public class CircuitBreaker {

    // 失败阈值，当连续失败次数达到该值时，熔断器开启
    private final int failureThreshold;
    // 重置超时时间（毫秒），熔断器开启后，经过该时间尝试半开状态
    private final long resetTimeoutMs;

    // 原子整数，记录连续失败的次数
    private final AtomicInteger failureCount = new AtomicInteger(0);
    // 原子长整型，记录最后一次失败的时间戳
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    /**
     * 构造函数
     * @param failureThreshold 失败阈值
     * @param resetTimeoutMs 重置超时时间（毫秒）
     */
    public CircuitBreaker(int failureThreshold, long resetTimeoutMs) {
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
    }

    /**
     * 判断是否允许请求通过
     * @return true 如果允许请求，false 如果拒绝请求
     */
    public boolean allowRequest() {
        // 如果当前失败次数大于等于阈值
        if (failureCount.get() >= failureThreshold) {
            // 获取当前时间
            long now = System.currentTimeMillis();
            // 如果当前时间与上次失败时间的差值大于重置超时时间
            if (now - lastFailureTime.get() > resetTimeoutMs) {
                // 进入半开状态，允许一个请求进行测试
                return true;
            }
            // 否则处于开启状态，快速失败，拒绝请求
            return false; 
        }
        // 失败次数未达阈值，处于关闭状态，允许请求
        return true; 
    }

    /**
     * 记录成功调用，重置失败计数
     */
    public void recordSuccess() {
        // 将失败计数重置为0
        failureCount.set(0);
    }

    /**
     * 记录失败调用，增加失败计数并更新最后失败时间
     */
    public void recordFailure() {
        // 失败计数加1
        failureCount.incrementAndGet();
        // 更新最后失败时间为当前时间
        lastFailureTime.set(System.currentTimeMillis());
    }

    /**
     * 执行任务，包含熔断逻辑
     * @param task 要执行的任务
     * @param <T> 任务返回类型
     * @return 任务执行结果
     * @throws Exception 如果任务执行异常或熔断器开启
     */
    public <T> T execute(Callable<T> task) throws Exception {
        // 如果不允许请求（熔断器开启且未过半开等待期）
        if (!allowRequest()) {
            // 抛出熔断器开启异常
            throw new CircuitBreakerOpenException("熔断器已开启。请求快速失败。");
        }

        try {
            // 执行任务
            T result = task.call();
            // 任务成功，记录成功
            recordSuccess();
            // 返回结果
            return result;
        } catch (Exception e) {
            // 任务异常，记录失败
            recordFailure();
            // 重新抛出异常
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
