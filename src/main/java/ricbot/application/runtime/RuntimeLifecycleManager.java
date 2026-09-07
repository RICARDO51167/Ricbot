package ricbot.application.runtime;

import ricbot.domain.runtime.dto.RuntimeInstanceRecord;
import ricbot.domain.runtime.enump.RuntimeInstanceStatus;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 显式进程生命周期管理器。
 * 负责启动、心跳维持、租约管理和死锁恢复。
 * 存储构建时有意不执行崩溃恢复，由运行时逻辑处理。
 */
public final class RuntimeLifecycleManager implements AutoCloseable {
    // 默认心跳间隔：5秒
    public static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(5);
    // 默认租约时长：30秒
    public static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);

    private final SqliteRuntimeStore store;
    private final Clock clock;
    private final Duration heartbeatInterval;
    private final Duration leaseDuration;
    private final String hostId;
    private final ScheduledExecutorService heartbeats;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile RuntimeInstanceRecord instance;

    /**
     * 使用默认配置创建生命周期管理器。
     * @param store 运行时数据存储接口
     */
    public RuntimeLifecycleManager(SqliteRuntimeStore store) {
        this(store, Clock.systemUTC(), DEFAULT_HEARTBEAT, DEFAULT_LEASE, localHostId());
    }

    /**
     * 完整构造函数，允许自定义时间源、心跳间隔和主机ID。
     * @param store 运行时数据存储接口
     * @param clock 时钟实例，用于获取当前时间
     * @param heartbeatInterval 心跳发送间隔
     * @param leaseDuration 租约过期时长
     * @param hostId 主机标识符
     */
    RuntimeLifecycleManager(SqliteRuntimeStore store, Clock clock, Duration heartbeatInterval,
                            Duration leaseDuration, String hostId) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeatInterval = positive(heartbeatInterval, "heartbeatInterval");
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.hostId = required(hostId, "hostId");
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ricbot-runtime-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 启动运行时生命周期。
     * 如果已经启动，则直接返回当前实例记录。
     * 注册新实例并启动心跳调度器。
     * @return 已注册的运行时实例记录
     */
    public synchronized RuntimeInstanceRecord start() {
        // 确保只启动一次
        if (!started.compareAndSet(false, true)) return instance;
        
        Instant now = clock.instant();
        ProcessHandle process = ProcessHandle.current();
        Instant processStart = process.info().startInstant().orElse(now);
        
        // 注册新的运行时实例
        instance = store.registerRuntimeInstance(new RuntimeInstanceRecord(
                UUID.randomUUID().toString(), 
                hostId,
                process.pid(), 
                processStart, 
                now, 
                now.plus(leaseDuration), 
                RuntimeInstanceStatus.ACTIVE, 
                0));
        
        // 尝试恢复之前可能挂起的死锁所有者
        recoverDeadOwners(now);
        
        // 启动定期心跳任务
        heartbeats.scheduleAtFixedRate(this::heartbeatSafely, heartbeatInterval.toMillis(),
                heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        
        return instance;
    }

    /**
     * 获取当前运行的实例记录。
     * @return 当前运行时实例记录
     * @throws IllegalStateException 如果生命周期尚未启动
     */
    public RuntimeInstanceRecord instance() {
        RuntimeInstanceRecord current = instance;
        if (current == null) throw new IllegalStateException("runtime lifecycle has not started");
        return current;
    }

    /**
     * 使用当前系统时间恢复死锁的所有者。
     * @return 恢复的死锁数量
     */
    public int recoverDeadOwners() { 
        return recoverDeadOwners(clock.instant()); 
    }

    /**
     * 检查并恢复过期的运行时实例。
     * 遍历所有过期的实例，如果对应的进程仍然匹配（同一主机且进程未重启），则跳过；
     * 否则标记为已过期。Durable Effect 的租约恢复由 v6 Store 负责。
     * @param now 当前时间点
     * @return 恢复的死锁数量
     */
    private int recoverDeadOwners(Instant now) {
        int recovered = 0;
        for (RuntimeInstanceRecord candidate : store.expiredRuntimeInstances(now)) {
            // 如果进程仍然匹配（同一主机且PID和启动时间一致），说明是同一个进程在运行，跳过
            if (processStillMatches(candidate)) continue;
            
            RuntimeInstanceRecord expired;
            try {
                // 将实例状态更新为过期，并尝试保存
                expired = store.saveRuntimeInstance(candidate.expire(now), candidate.version(),
                        RuntimeInstanceStatus.ACTIVE);
            } catch (IllegalStateException raced) {
                // 发生竞态条件，跳过该实例
                continue;
            }
            recovered++;
        }
        return recovered;
    }

    /**
     * 判断候选实例是否仍属于当前进程。
     * 检查条件：主机ID相同，且进程存在、存活，且启动时间与记录一致。
     * @param candidate 待检查的实例记录
     * @return 如果匹配返回true，否则false
     */
    private boolean processStillMatches(RuntimeInstanceRecord candidate) {
        if (!hostId.equals(candidate.hostId())) return false;
        return ProcessHandle.of(candidate.pid()).filter(ProcessHandle::isAlive)
                .flatMap(handle -> handle.info().startInstant())
                .map(start -> start.equals(candidate.processStartedAt()))
                .orElse(false);
    }

    /**
     * 安全地发送心跳。
     * 更新实例的心跳时间和租约到期时间，并触发死锁恢复。
     * 捕获异常以避免心跳失败影响主线程。
     */
    private synchronized void heartbeatSafely() {
        if (!started.get()) return;
        RuntimeInstanceRecord current = instance;
        if (current == null || current.status() != RuntimeInstanceStatus.ACTIVE) return;
        
        Instant now = clock.instant();
        try {
            // 更新心跳时间和租约到期时间
            instance = store.saveRuntimeInstance(current.heartbeat(now, now.plus(leaseDuration)), 
                    current.version(), RuntimeInstanceStatus.ACTIVE);
            // 检查是否有需要恢复的死锁
            recoverDeadOwners(now);
        } catch (RuntimeException ignored) {
            // 心跳丢失将由持久化的租约过期表示。调度器将在租约丢失时停止。
        }
    }

    /**
     * 关闭运行时生命周期。
     * 停止心跳调度器，并尝试优雅地关闭实例记录。
     * 如果无法提交关闭操作，租约过期机制将作为崩溃恢复的最终权威。
     */
    @Override public void close() {
        // 确保只关闭一次
        if (!started.compareAndSet(true, false)) return;
        
        // 立即停止心跳调度器
        heartbeats.shutdownNow();
        try {
            heartbeats.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        
        synchronized (this) {
            RuntimeInstanceRecord current = instance;
            if (current != null && current.status() == RuntimeInstanceStatus.ACTIVE) {
                try {
                    // 尝试优雅关闭实例
                    instance = store.saveRuntimeInstance(current.close(clock.instant()), 
                            current.version(), RuntimeInstanceStatus.ACTIVE);
                } catch (RuntimeException ignored) {
                    // 如果优雅关闭失败，租约过期机制将作为崩溃恢复的最终权威
                }
            }
        }
    }

    /**
     * 验证持续时间是否为正数。
     * @param value 待验证的持续时间
     * @param field 字段名称（用于错误消息）
     * @return 验证后的持续时间
     * @throws IllegalArgumentException 如果持续时间为null、零或负数
     */
    private static Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    /**
     * 获取本地主机名作为主机ID。
     * @return 本地主机名，如果获取失败则返回"localhost"
     */
    private static String localHostId() {
        try { 
            return InetAddress.getLocalHost().getHostName(); 
        }
        catch (Exception ignored) { 
            return "localhost"; 
        }
    }

    /**
     * 验证字符串是否为空。
     * @param value 待验证的字符串
     * @param field 字段名称（用于错误消息）
     * @return 清理后的非空字符串
     * @throws IllegalArgumentException 如果字符串为空或空白
     */
    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
