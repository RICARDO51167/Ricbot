package ricbot.application.runtime;

import ricbot.domain.agent.graph.AgentGraphRuntime;
import ricbot.domain.agent.graph.dto.GraphExecutionState;
import ricbot.domain.agent.graph.enump.GraphExecutionStatus;

import java.util.Map;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import ricbot.infra.runtime.SqliteRuntimeStore;

/**
 * 策略无关的执行驱动：执行处于 READY 状态的激活任务，并在遇到等待或终止状态时停止。
 */
public final class RuntimeDriver implements AutoCloseable {
    // 激活租约时长，用于心跳续期
    private static final Duration ACTIVATION_LEASE = Duration.ofSeconds(30);
    
    // 用于控制唤醒和等待的锁
    private final ReentrantLock wakeLock = new ReentrantLock();
    private final Condition dueOrSignal = wakeLock.newCondition();
    
    // 持久化存储（可选），实例ID，以及用于心跳的调度器
    private final SqliteRuntimeStore store;
    private final String instanceId;
    private final ScheduledExecutorService heartbeats;

    public RuntimeDriver() { this(null, ""); }

    public RuntimeDriver(SqliteRuntimeStore store, String instanceId) {
        this.store = store;
        this.instanceId = instanceId != null ? instanceId.trim() : "";
        // 如果提供了存储，则启动守护线程进行心跳；否则为 null
        this.heartbeats = store == null ? null : Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ricbot-activation-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 主驱动循环：不断尝试执行 READY 状态的激活，或在 RETRY_WAIT 状态下等待到期时间。
     */
    public GraphExecutionState drive(AgentGraphRuntime runtime) {
        while (true) {
            // 1. 处理 READY 状态：尝试获取租约并执行一步
            if (runtime.state().status() == GraphExecutionStatus.READY) {
                String runId = runtime.state().runId();
                // 尝试抢占激活，失败则返回当前状态（可能被其他实例抢占）
                if (!claim(runId)) return runtime.state();
                
                // 启动心跳续期任务
                ScheduledFuture<?> renewal = startRenewal(runId);
                try {
                    // 执行单个激活步骤
                    runtime.executeOne(Map.of());
                } finally {
                    // 无论成功与否，都取消心跳并释放租约
                    if (renewal != null) renewal.cancel(false);
                    release(runId);
                }
                continue;
            }
            
            // 2. 处理 RETRY_WAIT 状态：等待直到重试时间到达
            if (runtime.state().status() != GraphExecutionStatus.RETRY_WAIT) break;
            
            Instant due = runtime.nextRetryAt().orElseThrow(() ->
                    new IllegalStateException("retry-wait run has no persisted due time"));
            
            awaitUntil(due);
            // 超时后激活所有到期的重试任务
            runtime.activateDueRetries(Instant.now());
        }
        return runtime.state();
    }

    /**
     * 尝试抢占一个运行 ID 的激活权限。
     * 如果未配置存储，则视为总是抢占成功。
     */
    private boolean claim(String runId) {
        return store == null || store.claimReadyActivations(runId, instanceId, Instant.now(),
                Instant.now().plus(ACTIVATION_LEASE));
    }

    /**
     * 启动定时任务以定期续期激活租约。
     */
    private ScheduledFuture<?> startRenewal(String runId) {
        if (heartbeats == null) return null;
        return heartbeats.scheduleAtFixedRate(() -> {
            try { 
                // 续期租约，如果数据库 CAS 失败，说明租约已丢失或被其他实例抢占
                store.renewActivationClaims(runId, instanceId, Instant.now().plus(ACTIVATION_LEASE)); 
            }
            catch (RuntimeException ignored) { /* 异常由 DB CAS 机制处理，此处静默忽略 */ }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 释放当前实例对指定运行 ID 的激活租约。
     */
    private void release(String runId) {
        if (store != null) store.releaseActivationClaims(runId, instanceId);
    }

    /**
     * 唤醒所有正在等待的线程（例如在 RETRY_WAIT 期间）。
     */
    public void wake() {
        wakeLock.lock();
        try { dueOrSignal.signalAll(); }
        finally { wakeLock.unlock(); }
    }

    /**
     * 阻塞当前线程直到指定的时间点。支持被外部 wake() 方法中断。
     */
    private void awaitUntil(Instant due) {
        wakeLock.lock();
        try {
            while (Instant.now().isBefore(due)) {
                long nanos = Math.max(1L, Duration.between(Instant.now(), due).toNanos());
                try { 
                    dueOrSignal.awaitNanos(nanos); 
                }
                catch (InterruptedException interrupted) {
                    // 恢复中断状态并抛出运行时异常
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("runtime driver interrupted while waiting for durable retry",
                            interrupted);
                }
            }
        } finally {
            wakeLock.unlock();
        }
    }

    @Override 
    public void close() {
        // 首先唤醒可能正在等待的线程，使其退出 awaitUntil 循环
        wake();
        
        if (heartbeats == null) return;
        
        // 关闭心跳调度器
        heartbeats.shutdownNow();
        try {
            heartbeats.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
