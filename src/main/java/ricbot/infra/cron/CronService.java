package ricbot.infra.cron;

import com.fasterxml.jackson.core.type.TypeReference; // 导入 Jackson 的类型引用类，用于泛型反序列化
import com.fasterxml.jackson.databind.ObjectMapper; // 导入 Jackson 的对象映射器，用于 JSON 处理
import ricbot.infra.cron.CronTypes.CronJob; // 导入定时任务实体类
import ricbot.infra.cron.CronTypes.CronJobState; // 导入定时任务状态类
import ricbot.infra.cron.CronTypes.CronPayload; // 导入定时任务负载类
import ricbot.infra.cron.CronTypes.CronRunRecord; // 导入定时任务运行记录类
import ricbot.infra.cron.CronTypes.CronSchedule; // 导入定时任务调度配置类
import ricbot.infra.cron.CronTypes.CronStore; // 导入定时任务存储类
import ricbot.infra.cron.CronTypes.PayloadKind; // 导入负载类型枚举
import ricbot.infra.cron.CronTypes.RunStatus; // 导入运行状态枚举
import ricbot.infra.cron.CronTypes.ScheduleKind; // 导入调度类型枚举

import org.slf4j.Logger; // 导入 SLF4J 日志接口
import org.slf4j.LoggerFactory; // 导入 SLF4J 日志工厂

import java.io.IOException; // 导入 IO 异常类
import java.nio.file.Files; // 导入文件操作工具类
import java.nio.file.Path; // 导入文件路径类
import java.time.*; // 导入时间相关类（虽然当前选中代码未直接使用，但保留以维持完整性）
import java.util.*; // 导入集合框架类
import java.util.concurrent.*; // 导入并发工具类
import java.util.concurrent.locks.ReentrantLock; // 导入重入锁，用于线程安全控制
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * 对应 Python: service.py
 *
 * 主要目标：
 * 1. 管理和执行定时任务
 * 2. 支持 at / every / cron
 * 3. 支持 action log 合并
 * 4. 支持 job CRUD
 * 5. 支持运行历史
 */
public class CronService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CronService.class); // 初始化日志记录器

    /**
     * 单条 job 最多保留多少条运行历史
     *
     * 对应 Python: _MAX_RUN_HISTORY = 20
     */
    private static final int MAX_RUN_HISTORY = 20; // 定义最大运行历史记录数常量

    /**
     * action file lock
     */
    private final ReentrantLock actionLock = new ReentrantLock(); // 初始化重入锁，用于保护 action.jsonl 文件的并发访问

    private final ReentrantReadWriteLock storeLock = new ReentrantReadWriteLock();

    /**
     * store.json 路径
     */
    private final Path storePath; // 存储任务定义的 JSON 文件路径

    /**
     * action.jsonl 路径
     */
    private final Path actionPath; // 存储操作日志的 JSONL 文件路径

    /**
     * 任务执行回调
     *
     * 对应 Python:
     * on_job: Callable[[CronJob], Coroutine[Any, Any, str | None]] | None
     */
    private JobHandler onJob; // 任务执行时的回调处理器

    /**
     * 最长睡眠时间，避免没任务时无限 sleep
     *
     * 对应 Python: max_sleep_ms = 300_000
     */
    private final long maxSleepMs; // 定义最大休眠时间（毫秒），防止无任务时长时间阻塞

    private final ObjectMapper mapper = new ObjectMapper(); // 初始化 Jackson ObjectMapper，用于 JSON 序列化与反序列化

    private CronStore store; // 内存中的任务存储对象
    private volatile boolean running = false; // 标记服务是否正在运行，使用 volatile 保证可见性
    private volatile ScheduledExecutorService scheduler; // 延迟初始化，统一通过 ensureExecutors 创建
    private volatile ExecutorService jobExecutor; // 延迟初始化，统一通过 ensureExecutors 创建
    private ScheduledFuture<?> timerTask; // 保存当前调度的定时任务句柄，用于取消或管理

    public CronService(Path storePath) {
        this(storePath, null, 300_000); // 调用全参构造函数，默认无回调且最大休眠时间为 300 秒
    }

    public CronService(Path storePath, JobHandler onJob, long maxSleepMs) {
        this.storePath = storePath; // 初始化存储路径
        this.actionPath = storePath.getParent().resolve("action.jsonl"); // 根据存储路径父目录生成 action 日志路径
        this.onJob = onJob; // 初始化任务回调处理器
        this.maxSleepMs = maxSleepMs; // 初始化最大休眠时间
    }

    // =========================================================
    // Callback interface
    // =========================================================

    @FunctionalInterface
    public interface JobHandler {
        String handle(CronJob job) throws Exception; // 定义任务处理函数式接口，接收任务并返回结果字符串
    }

    // =========================================================
    // Internal time helpers
    // =========================================================

    /**
     * 对应 Python: _now_ms()
     */
    public static long nowMs() {
        return System.currentTimeMillis(); // 获取当前系统时间的毫秒戳
    }

    /**
     * 对应 Python: _compute_next_run(schedule, now_ms)
     */
    public static Long computeNextRun(CronSchedule schedule, long nowMs) {
        if (schedule == null || schedule.getKind() == null) { // 如果调度配置为空或类型为空，返回 null
            return null;
        }

        if (schedule.getKind() == ScheduleKind.AT) { // 如果是 AT 类型（一次性任务）
            Long atMs = schedule.getAtMs(); // 获取指定执行时间戳
            return (atMs != null && atMs > nowMs) ? atMs : null; // 如果指定时间在未来，则返回该时间，否则返回 null
        }

        if (schedule.getKind() == ScheduleKind.EVERY) { // 如果是 EVERY 类型（周期性任务）
            Long everyMs = schedule.getEveryMs(); // 获取间隔毫秒数
            if (everyMs == null || everyMs <= 0) { // 如果间隔无效，返回 null
                return null;
            }
            return nowMs + everyMs; // 返回当前时间加上间隔时间作为下次执行时间
        }

        if (schedule.getKind() == ScheduleKind.CRON) { // 如果是 CRON 类型（cron 表达式任务）
            String expr = schedule.getExpr(); // 获取 cron 表达式
            if (expr == null || expr.isBlank()) { // 如果表达式为空，返回 null
                return null;
            }

            try {
                ZoneId zone = schedule.getTz() != null && !schedule.getTz().isBlank()
                        ? ZoneId.of(schedule.getTz()) // 如果指定了时区，使用时区 ID
                        : ZoneId.systemDefault(); // 否则使用系统默认时区

                return CronExpressionUtils.nextExecutionMillis(expr, zone, nowMs); // 计算下一次执行时间戳
            } catch (Exception e) {
                log.warn("Cron: cron 表达式解析失败 expr='{}' tz='{}': {}", expr, schedule.getTz(), e.getMessage(), e);
                return null; // 如果解析失败，返回 null
            }
        }

        return null; // 其他情况返回 null
    }

    /**
     * 对应 Python: _validate_schedule_for_add(schedule)
     */
    public static void validateScheduleForAdd(CronSchedule schedule) {
        if (schedule == null) {
            throw new IllegalArgumentException("schedule.kind is required");
        }
        schedule.validateForAdd(nowMs());

        if (schedule.getKind() == ScheduleKind.CRON) {
            ZoneId zone;
            if (schedule.getTz() != null && !schedule.getTz().isBlank()) {
                try {
                    zone = ZoneId.of(schedule.getTz());
                } catch (Exception e) {
                    throw new IllegalArgumentException("unknown timezone '" + schedule.getTz() + "'");
                }
            } else {
                zone = ZoneId.systemDefault();
            }

            try {
                CronExpressionUtils.nextExecutionMillis(schedule.getExpr(), zone, nowMs());
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid cron expr '" + schedule.getExpr() + "'");
            }
        }
    }

    // =========================================================
    // Store load/save
    // =========================================================

    /**
     * 对应 Python: _load_jobs()
     */
    private LoadedJobs loadJobs() {
        List<CronJob> jobs = new ArrayList<>(); // 初始化任务列表
        int version = 1; // 默认版本号

        if (Files.exists(storePath)) { // 如果存储文件存在
            try {
                String raw = Files.readString(storePath); // 读取文件内容
                Map<String, Object> data = mapper.readValue(raw, new TypeReference<>() {}); // 反序列化为 Map

                Number versionNum = data.get("version") instanceof Number n ? n : null; // 提取版本号
                version = versionNum != null ? versionNum.intValue() : 1; // 设置版本号，默认为 1

                Object jobsObj = data.get("jobs"); // 获取 jobs 字段
                if (jobsObj instanceof List<?> list) { // 如果 jobs 是列表
                    for (Object item : list) { // 遍历列表项
                        if (item instanceof Map<?, ?> rawJob) { // 如果项是 Map
                            @SuppressWarnings("unchecked")
                            Map<String, Object> jobMap = (Map<String, Object>) rawJob; // 强制转换为 String-Object Map
                            try {
                                CronJob job = CronJob.fromMap(jobMap); // 从 Map 构建 CronJob 对象
                                if (job != null) { // 如果构建成功
                                    jobs.add(job); // 添加到任务列表
                                }
                            } catch (Exception e) {
                                log.warn("Cron: 解析 job 失败: {}", e.getMessage(), e);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Cron: 加载存储失败 path='{}': {}", storePath, e.getMessage(), e);
            }
        }

        return new LoadedJobs(jobs, version); // 返回加载结果对象
    }

    /**
     * 对应 Python: _merge_action()
     */
    private void mergeActionLocked() {
        if (!Files.exists(actionPath) || store == null) { // 如果 action 文件不存在或 store 未初始化，直接返回
            return;
        }

        Map<String, CronJob> jobsMap = new LinkedHashMap<>(); // 使用 LinkedHashMap 保持插入顺序
        for (CronJob job : store.getJobs()) { // 遍历当前 store 中的任务
            jobsMap.put(job.getId(), job); // 将任务放入 Map，key 为 ID
        }

        actionLock.lock(); // 加锁，保证线程安全
        try {
            boolean changed = false; // 标记是否有变更
            List<String> failedLines = new ArrayList<>();
            try (var reader = Files.newBufferedReader(actionPath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        if (line.isBlank()) { // 跳过空行
                            continue;
                        }

                        Map<String, Object> action = mapper.readValue(line, new TypeReference<>() {}); // 反序列化行动记录
                        String type = String.valueOf(action.get("action")); // 获取行动类型
                        @SuppressWarnings("unchecked")
                        Map<String, Object> params = action.get("params") instanceof Map<?, ?> p
                                ? (Map<String, Object>) p
                                : Collections.emptyMap(); // 获取参数 Map

                        if ("del".equals(type)) { // 如果是删除操作
                            String jobId = String.valueOf(params.get("job_id")); // 获取任务 ID
                            if (jobId != null) { // 如果 ID 有效
                                jobsMap.remove(jobId); // 从 Map 中移除任务
                                changed = true; // 标记已变更
                            }
                        } else { // 其他操作（如 add/update）
                            CronJob job = CronJob.fromMap(params); // 从参数构建任务对象
                            if (job != null) { // 如果构建成功
                                jobsMap.put(job.getId(), job); // 更新或添加任务到 Map
                                changed = true; // 标记已变更
                            }
                        }
                    } catch (Exception e) {
                        failedLines.add(line);
                        log.warn("Cron: action 行解析失败: {}", e.getMessage(), e);
                    }
                }
            }

            if (changed) {
                store.setJobs(new ArrayList<>(jobsMap.values()));
            }

            if (failedLines.isEmpty()) {
                Files.writeString(actionPath, "");
            } else {
                Files.writeString(actionPath, String.join("\n", failedLines) + "\n");
            }
        } catch (IOException e) {
            log.warn("Cron: 合并 action 文件失败: {}", e.getMessage(), e);
        } finally {
            actionLock.unlock(); // 释放锁
        }
    }

    /**
     * 对应 Python: _load_store()
     */
    private CronStore loadStore() {
        return withStoreRead(() -> store);
    }

    private <T> T withStoreRead(Supplier<T> op) {
        storeLock.readLock().lock();
        try {
            if (store != null) {
                return op.get();
            }
        } finally {
            storeLock.readLock().unlock();
        }

        storeLock.writeLock().lock();
        try {
            ensureStoreLoadedLocked();
            storeLock.readLock().lock();
        } finally {
            storeLock.writeLock().unlock();
        }

        try {
            return op.get();
        } finally {
            storeLock.readLock().unlock();
        }
    }

    private <T> T withStoreWrite(Supplier<T> op) {
        storeLock.writeLock().lock();
        try {
            ensureStoreLoadedLocked();
            return op.get();
        } finally {
            storeLock.writeLock().unlock();
        }
    }

    private void ensureStoreLoadedLocked() {
        if (store != null) {
            return;
        }
        LoadedJobs loaded = loadJobs();
        store = new CronStore(loaded.version(), loaded.jobs());
        mergeActionLocked();
        saveStoreLocked();
    }

    private void saveStoreLocked() {
        if (store == null) {
            return;
        }
        try {
            Files.createDirectories(storePath.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(store.toMap());
            Files.writeString(storePath, json);
        } catch (IOException e) {
            log.error("Cron: 保存存储失败 path='{}': {}", storePath, e.getMessage(), e);
            throw new RuntimeException("保存 cron 存储失败", e);
        }
    }

    // =========================================================
    // Lifecycle
    // =========================================================

    /**
     * 对应 Python: async start()
     */
    public synchronized void start() {
        running = true;
        ensureExecutors();
        withStoreWrite(() -> {
            recomputeNextRunsLocked(nowMs());
            saveStoreLocked();
            return null;
        });
        armTimer();
        int count = withStoreRead(() -> store != null ? store.getJobs().size() : 0);
        log.info("Cron: 服务已启动，任务数={}", count);
    }

    /**
     * 对应 Python: stop()
     */
    public synchronized void stop() {
        running = false;
        if (timerTask != null) {
            timerTask.cancel(true);
            timerTask = null;
        }
        shutdownExecutors();
    }

    @Override
    public void close() {
        stop();
    }

    private void ensureExecutors() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cron-scheduler");
                t.setDaemon(true);
                return t;
            });
        }
        if (jobExecutor == null || jobExecutor.isShutdown()) {
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            jobExecutor = new ThreadPoolExecutor(
                    threads,
                    threads,
                    30L,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r, "cron-worker");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );
        }
    }

    private void shutdownExecutors() {
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) {
            s.shutdownNow();
        }
        ExecutorService w = jobExecutor;
        jobExecutor = null;
        if (w != null) {
            w.shutdownNow();
        }
    }

    /**
     * 对应 Python: _recompute_next_runs()
     */
    private void recomputeNextRunsLocked(long now) {
        if (store == null) {
            return;
        }
        for (CronJob job : store.getJobs()) {
            if (job.isEnabled()) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
            }
        }
    }

    /**
     * 对应 Python: _get_next_wake_ms()
     */
    private Long getNextWakeMsLocked() {
        if (store == null) {
            return null;
        }
        Long min = null;
        for (CronJob job : store.getJobs()) {
            if (!job.isEnabled()) continue;
            Long next = job.getState().getNextRunAtMs();
            if (next == null) continue;
            if (min == null || next < min) {
                min = next;
            }
        }
        return min;
    }

    /**
     * 对应 Python: _arm_timer()
     * 启动或重新调度定时器，确保在最近的到期时间唤醒
     */
    private synchronized void armTimer() {
        // 如果存在已调度的定时任务，先取消它（不中断正在执行的任务）
        if (timerTask != null) {
            timerTask.cancel(false);
        }

        // 如果服务未处于运行状态，则不再调度新任务
        if (!running) {
            return;
        }

        ScheduledExecutorService localScheduler = this.scheduler;
        if (localScheduler == null || localScheduler.isShutdown()) {
            return;
        }

        Long nextWake = withStoreRead(this::getNextWakeMsLocked);
        long delayMs;
        if (nextWake == null) {
            // 如果没有待执行的任务，使用最大休眠时间，避免无限等待
            delayMs = maxSleepMs;
        } else {
            // 计算距离下次执行的延迟时间，确保不为负数，且不超过最大休眠时间
            delayMs = Math.min(maxSleepMs, Math.max(0, nextWake - nowMs()));
        }

        // 调度一个新的定时任务，在 delayMs 毫秒后执行 onTimer 方法
        timerTask = localScheduler.schedule(() -> {
            // 再次检查服务是否仍在运行，防止在休眠期间服务被停止
            if (running) {
                onTimer();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 对应 Python: async _on_timer()
     * 定时器触发时的回调方法，负责检查并执行到期的任务
     */
    private void onTimer() {
        ensureExecutors();
        long now = nowMs();
        List<String> dueJobIds = withStoreWrite(() -> {
            List<String> due = new ArrayList<>();
            if (store == null) {
                return due;
            }
            for (CronJob job : store.getJobs()) {
                Long next = job.getState().getNextRunAtMs();
                if (!job.isEnabled() || next == null) {
                    continue;
                }
                if (next <= now + 500) {
                    due.add(job.getId());
                    if (job.getSchedule() != null && job.getSchedule().getKind() != ScheduleKind.AT) {
                        job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
                    } else {
                        job.getState().setNextRunAtMs(null);
                    }
                }
            }
            if (!due.isEmpty()) {
                saveStoreLocked();
            }
            return due;
        });

        if (!dueJobIds.isEmpty()) {
            log.info("Cron: 找到到期任务数={}", dueJobIds.size());
            for (String jobId : dueJobIds) {
                try {
                    jobExecutor.submit(() -> executeJobById(jobId, false, false));
                } catch (RejectedExecutionException e) {
                    log.warn("Cron: 任务提交被拒绝 jobId={}", jobId);
                }
            }
        }

        armTimer();
    }

    /**
     * 对应 Python: async _execute_job(job)
     * 执行单个定时任务，并记录执行结果和历史
     */
    private void executeJobById(String jobId, boolean force, boolean manual) {
        CronJob snapshot = withStoreRead(() -> {
            if (store == null) {
                return null;
            }
            for (CronJob job : store.getJobs()) {
                if (Objects.equals(job.getId(), jobId)) {
                    if (!force && !job.isEnabled()) {
                        return null;
                    }
                    return CronJob.fromMap(job.toMap());
                }
            }
            return null;
        });

        if (snapshot == null) {
            return;
        }

        long startMs = nowMs();
        String name = snapshot.getName();
        log.info("Cron: 执行任务 name='{}' id={}", name, jobId);

        RunStatus status = RunStatus.OK;
        String error = null;

        try {
            if (onJob != null) {
                onJob.handle(snapshot);
            }
        } catch (Exception e) {
            status = RunStatus.ERROR;
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Cron: 任务执行失败 name='{}' id={}", name, jobId, e);
        }

        long endMs = nowMs();
        RunStatus finalStatus = status;
        String finalError = error;

        withStoreWrite(() -> {
            if (store == null) {
                return null;
            }
            CronJob job = null;
            for (CronJob j : store.getJobs()) {
                if (Objects.equals(j.getId(), jobId)) {
                    job = j;
                    break;
                }
            }
            if (job == null) {
                return null;
            }

            job.getState().setLastStatus(finalStatus);
            job.getState().setLastError(finalError);
            job.getState().setLastRunAtMs(startMs);
            job.setUpdatedAtMs(endMs);
            job.getState().addRunRecord(new CronRunRecord(
                    startMs,
                    finalStatus,
                    endMs - startMs,
                    finalError
            ));
            job.getState().trimRunHistory(MAX_RUN_HISTORY);

            if (job.getSchedule() != null && job.getSchedule().getKind() == ScheduleKind.AT) {
                if (job.isDeleteAfterRun()) {
                    store.setJobs(store.getJobs().stream().filter(x -> !Objects.equals(x.getId(), jobId)).toList());
                } else {
                    job.setEnabled(false);
                    job.getState().setNextRunAtMs(null);
                }
            } else if (job.getSchedule() != null) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), startMs));
            }

            saveStoreLocked();
            return null;
        });

        if (running) {
            armTimer();
        }
    }

    // =========================================================
    // Action append
    // =========================================================

    /**
     * 对应 Python: _append_action(action, params)
     * 将操作日志追加到 action.jsonl 文件中，用于在服务未运行时记录变更
     */
    private void appendAction(String action, Map<String, Object> params) {
        try {
            // 确保 action 文件的父目录存在
            Files.createDirectories(actionPath.getParent());
            // 获取锁，保证对 action 文件的写操作是线程安全的
            actionLock.lock();
            try {
                // 构建操作日志行
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("action", action);
                row.put("params", params);
                String line = mapper.writeValueAsString(row) + "\n";

                // 将日志行追加写入文件，如果文件不存在则创建
                Files.writeString(
                        actionPath,
                        line,
                        Files.exists(actionPath)
                                ? java.nio.file.StandardOpenOption.APPEND
                                : java.nio.file.StandardOpenOption.CREATE
                );
            } finally {
                // 释放锁
                actionLock.unlock();
            }
        } catch (IOException e) {
            // 如果发生 IO 异常，抛出运行时异常
            throw new RuntimeException("追加 cron action 失败", e);
        }
    }

    // =========================================================
    // Public API
    // =========================================================

    /**
     * 对应 Python: list_jobs(include_disabled=False)
     * 列出所有任务，可选择是否包含已禁用的任务
     */
    public List<CronJob> listJobs(boolean includeDisabled) {
        return withStoreRead(() -> {
            if (store == null) {
                return List.of();
            }

            List<CronJob> selected = new ArrayList<>();
            for (CronJob job : store.getJobs()) {
                if (includeDisabled || job.isEnabled()) {
                    selected.add(CronJob.fromMap(job.toMap()));
                }
            }

            selected.sort(Comparator.comparing(
                    j -> j.getState().getNextRunAtMs() != null ? j.getState().getNextRunAtMs() : Long.MAX_VALUE
            ));
            return selected;
        });
    }

    /**
     * 列出所有启用的任务
     */
    public List<CronJob> listJobs() {
        return listJobs(false);
    }

    /**
     * 对应 Python: add_job(...)
     * 添加一个新的定时任务
     */
    public CronJob addJob(
            String name,
            CronSchedule schedule,
            String message,
            boolean deliver,
            String channel,
            String to,
            boolean deleteAfterRun
    ) {
        // 验证调度配置的合法性
        validateScheduleForAdd(schedule);
        long now = nowMs();

        // 创建新的任务对象
        CronJob job = new CronJob();
        job.setId(UUID.randomUUID().toString().substring(0, 8)); // 生成简短的唯一 ID
        job.setName(name);
        job.setEnabled(true);
        job.setSchedule(schedule);

        // 创建任务负载
        CronPayload payload = new CronPayload();
        payload.setKind(PayloadKind.AGENT_TURN);
        payload.setMessage(message);
        payload.setDeliver(deliver);
        payload.setChannel(channel);
        payload.setTo(to);
        job.setPayload(payload);

        // 创建任务状态
        CronJobState state = new CronJobState();
        state.setNextRunAtMs(computeNextRun(schedule, now));
        job.setState(state);

        // 设置创建和更新时间
        job.setCreatedAtMs(now);
        job.setUpdatedAtMs(now);
        job.setDeleteAfterRun(deleteAfterRun);

        if (running) {
            CronJob created = withStoreWrite(() -> {
                store.getJobs().add(job);
                saveStoreLocked();
                return CronJob.fromMap(job.toMap());
            });
            armTimer();
            log.info("Cron: 已添加任务 name='{}' id={}", name, job.getId());
            return created;
        }

        appendAction("add", job.toMap());
        log.info("Cron: 已记录待添加任务 name='{}' id={}", name, job.getId());
        return CronJob.fromMap(job.toMap());
    }

    /**
     * 对应 Python: register_system_job(job)
     * 注册一个系统级任务，通常用于内部维护或监控
     */
    public CronJob registerSystemJob(CronJob job) {
        long now = nowMs();
        CronJob created = withStoreWrite(() -> {
            if (job.getState() == null) {
                job.setState(new CronJobState());
            }
            job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
            job.setCreatedAtMs(now);
            job.setUpdatedAtMs(now);

            List<CronJob> filtered = new ArrayList<>();
            for (CronJob j : store.getJobs()) {
                if (!Objects.equals(j.getId(), job.getId())) {
                    filtered.add(j);
                }
            }
            filtered.add(job);
            store.setJobs(filtered);

            saveStoreLocked();
            return CronJob.fromMap(job.toMap());
        });

        armTimer();
        log.info("Cron: 已注册系统任务 name='{}' id={}", created.getName(), created.getId());
        return created;
    }

    /**
     * 对应 Python: remove_job(job_id)
     *
     * 返回:
     * - removed: 成功删除
     * - protected: 任务是受保护的系统任务，拒绝删除
     * - not_found: 未找到指定 ID 的任务
     */
    public String removeJob(String jobId) {
        String result = withStoreWrite(() -> {
            CronJob target = null;
            for (CronJob job : store.getJobs()) {
                if (Objects.equals(job.getId(), jobId)) {
                    target = job;
                    break;
                }
            }

            if (target == null) {
                return "not_found";
            }

            if (target.getPayload() != null && target.getPayload().getKind() == PayloadKind.SYSTEM_EVENT) {
                return "protected";
            }

            int before = store.getJobs().size();
            store.setJobs(store.getJobs().stream().filter(j -> !Objects.equals(j.getId(), jobId)).toList());
            if (store.getJobs().size() >= before) {
                return "not_found";
            }

            saveStoreLocked();
            return "removed";
        });

        if ("removed".equals(result)) {
            if (!running) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("job_id", jobId);
                appendAction("del", params);
            } else {
                armTimer();
            }
            log.info("Cron: 已删除任务 id={}", jobId);
        }

        if ("protected".equals(result)) {
            log.info("Cron: 拒绝删除受保护的系统任务 id={}", jobId);
        }

        return result;
    }

    /**
     * 对应 Python: enable_job(job_id, enabled=True)
     * 启用或禁用指定的任务
     */
    public CronJob enableJob(String jobId, boolean enabled) {
        CronJob updated = withStoreWrite(() -> {
            for (CronJob job : store.getJobs()) {
                if (Objects.equals(job.getId(), jobId)) {
                    job.setEnabled(enabled);
                    job.setUpdatedAtMs(nowMs());

                    if (enabled) {
                        job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), nowMs()));
                    } else {
                        job.getState().setNextRunAtMs(null);
                    }

                    saveStoreLocked();
                    return CronJob.fromMap(job.toMap());
                }
            }
            return null;
        });

        if (updated == null) {
            return null;
        }

        if (running) {
            armTimer();
        } else {
            appendAction("update", updated.toMap());
        }

        return updated;
    }

    /**
     * 禁用指定的任务
     */
    public CronJob disableJob(String jobId) {
        return enableJob(jobId, false);
    }

    /**
     * 对应 Python: update_job(...)
     * 更新现有任务的属性
     */
    public Object updateJob(
            String jobId,
            String name,
            CronSchedule schedule,
            String message,
            Boolean deliver,
            Object channelSentinel,
            Object toSentinel,
            Boolean deleteAfterRun
    ) {
        Object result = withStoreWrite(() -> {
            CronJob job = null;
            for (CronJob j : store.getJobs()) {
                if (Objects.equals(j.getId(), jobId)) {
                    job = j;
                    break;
                }
            }

            if (job == null) {
                return "not_found";
            }

            if (job.getPayload() != null && job.getPayload().getKind() == PayloadKind.SYSTEM_EVENT) {
                return "protected";
            }

            if (schedule != null) {
                validateScheduleForAdd(schedule);
                job.setSchedule(schedule);
            }
            if (name != null) {
                job.setName(name);
            }
            if (message != null) {
                job.getPayload().setMessage(message);
            }
            if (deliver != null) {
                job.getPayload().setDeliver(deliver);
            }
            if (!(channelSentinel instanceof UnchangedSentinel)) {
                job.getPayload().setChannel((String) channelSentinel);
            }
            if (!(toSentinel instanceof UnchangedSentinel)) {
                job.getPayload().setTo((String) toSentinel);
            }
            if (deleteAfterRun != null) {
                job.setDeleteAfterRun(deleteAfterRun);
            }

            job.setUpdatedAtMs(nowMs());
            if (job.isEnabled()) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), nowMs()));
            }

            saveStoreLocked();
            return CronJob.fromMap(job.toMap());
        });

        if (result instanceof CronJob updated) {
            if (running) {
                armTimer();
            } else {
                appendAction("update", updated.toMap());
            }
            log.info("Cron: 已更新任务 name='{}' id={}", updated.getName(), updated.getId());
        }

        return result;
    }

    /**
     * 对应 Python: run_job(job_id, force=False)
     * 手动触发执行指定的任务
     */
    public boolean runJob(String jobId, boolean force) {
        boolean canRun = withStoreRead(() -> {
            if (store == null) {
                return false;
            }
            for (CronJob job : store.getJobs()) {
                if (Objects.equals(job.getId(), jobId)) {
                    return force || job.isEnabled();
                }
            }
            return false;
        });

        if (!canRun) {
            return false;
        }

        executeJobById(jobId, force, true);
        return true;
    }

    /**
     * 手动触发执行指定的任务（默认非强制）
     */
    public boolean runJob(String jobId) {
        return runJob(jobId, false);
    }

    /**
     * 对应 Python: get_job(job_id)
     * 获取指定 ID 的任务详情
     */
    public CronJob getJob(String jobId) {
        return withStoreRead(() -> {
            if (store == null) {
                return null;
            }
            for (CronJob job : store.getJobs()) {
                if (Objects.equals(job.getId(), jobId)) {
                    return CronJob.fromMap(job.toMap());
                }
            }
            return null;
        });
    }

    /**
     * 对应 Python: status()
     * 获取 Cron 服务的当前状态信息
     */
    public Map<String, Object> status() {
        return withStoreRead(() -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("enabled", running);
            map.put("jobs", store != null ? store.getJobs().size() : 0);
            map.put("next_wake_at_ms", getNextWakeMsLocked());
            return map;
        });
    }

    /**
     * 设置任务执行回调处理器
     */
    public void setOnJob(JobHandler onJob) {
        this.onJob = onJob;
    }

    // =========================================================
    // Helper DTOs / sentinels
    // =========================================================

    /**
     * 内部记录类，用于封装从磁盘加载的任务列表和版本信息
     */
    private record LoadedJobs(List<CronJob> jobs, int version) {
    }

    /**
     * 用来模拟 Python 中 channel=... / to=... 的“未传入”语义
     * 当参数为此类型的实例时，表示该字段不应被更新
     */
    public static final class UnchangedSentinel {
        private UnchangedSentinel() {
        }
    }

    public static final UnchangedSentinel UNCHANGED = new UnchangedSentinel();
}
