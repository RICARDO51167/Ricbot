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
     * 获取当前系统时间的毫秒时间戳
     */
    public static long nowMs() {
        return System.currentTimeMillis(); // 获取当前系统时间的毫秒戳
    }

    /**
     * 对应 Python: _compute_next_run(schedule, now_ms)
     * 根据调度配置和当前时间，计算下一次执行的时间戳
     *
     * @param schedule 调度配置对象，包含任务类型（AT/EVERY/CRON）及具体参数
     * @param nowMs    当前时间的毫秒时间戳，作为计算基准
     * @return 下一次执行的毫秒时间戳；如果无法计算或任务已过期，则返回 null
     */
    public static Long computeNextRun(CronSchedule schedule, long nowMs) {
        // 检查调度配置是否为空，或者调度类型是否为空，若是则无法计算，返回 null
        if (schedule == null || schedule.getKind() == null) {
            return null;
        }

        // 处理 AT 类型：一次性执行任务
        if (schedule.getKind() == ScheduleKind.AT) {
            Long atMs = schedule.getAtMs(); // 获取预设的执行时间戳
            // 只有当预设时间存在且在未来时，才返回该时间；否则视为已过期，返回 null
            return (atMs != null && atMs > nowMs) ? atMs : null;
        }

        // 处理 EVERY 类型：周期性间隔执行任务
        if (schedule.getKind() == ScheduleKind.EVERY) {
            Long everyMs = schedule.getEveryMs(); // 获取执行间隔毫秒数
            // 检查间隔是否有效：必须非空且大于 0
            if (everyMs == null || everyMs <= 0) {
                return null;
            }
            // 下次执行时间为当前时间加上间隔时间
            return nowMs + everyMs;
        }

        // 处理 CRON 类型：基于 Cron 表达式执行的任务
        if (schedule.getKind() == ScheduleKind.CRON) {
            String expr = schedule.getExpr(); // 获取 Cron 表达式字符串
            // 检查表达式是否有效：非空且非空白
            if (expr == null || expr.isBlank()) {
                return null;
            }

            try {
                // 确定时区：如果配置中指定了有效时区字符串，则使用它；否则使用系统默认时区
                ZoneId zone = schedule.getTz() != null && !schedule.getTz().isBlank()
                        ? ZoneId.of(schedule.getTz())
                        : ZoneId.systemDefault();

                // 调用工具类计算基于 Cron 表达式的下一次执行时间戳
                return CronExpressionUtils.nextExecutionMillis(expr, zone, nowMs);
            } catch (Exception e) {
                // 如果 Cron 表达式解析失败或计算出错，记录警告日志并返回 null
                log.warn("Cron: cron 表达式解析失败 expr='{}' tz='{}': {}", expr, schedule.getTz(), e.getMessage(), e);
                return null;
            }
        }

        // 如果调度类型未知或未匹配上述任何类型，返回 null
        return null;
    }

    /**
     * 验证调度配置的合法性，用于添加新任务时
     * 对应 Python: _validate_schedule_for_add(schedule)
     *
     * @param schedule 待验证的调度配置对象
     * @throws IllegalArgumentException 如果调度配置无效
     */
    public static void validateScheduleForAdd(CronSchedule schedule) {
        // 检查调度配置是否为空
        if (schedule == null) {
            throw new IllegalArgumentException("schedule.kind is required");
        }
        // 调用调度配置自身的验证逻辑（如检查必填字段）
        schedule.validateForAdd(nowMs());

        // 如果是 CRON 表达式类型，需要额外验证表达式和时区
        if (schedule.getKind() == ScheduleKind.CRON) {
            ZoneId zone; // 定义时区变量
            // 检查是否指定了时区且非空
            if (schedule.getTz() != null && !schedule.getTz().isBlank()) {
                try {
                    // 尝试根据时区字符串创建 ZoneId 对象
                    zone = ZoneId.of(schedule.getTz());
                } catch (Exception e) {
                    // 如果时区字符串无效，抛出异常
                    throw new IllegalArgumentException("unknown timezone '" + schedule.getTz() + "'");
                }
            } else {
                // 如果未指定时区，使用系统默认时区
                zone = ZoneId.systemDefault();
            }

            try {
                // 尝试计算下一次执行时间，以验证 Cron 表达式是否合法
                CronExpressionUtils.nextExecutionMillis(schedule.getExpr(), zone, nowMs());
            } catch (Exception e) {
                // 如果表达式解析失败，抛出异常
                throw new IllegalArgumentException("invalid cron expr '" + schedule.getExpr() + "'");
            }
        }
    }

    // =========================================================
    // Store load/save
    // =========================================================

    /**
     * 从磁盘加载任务列表和版本信息
     * 对应 Python: _load_jobs()
     *
     * @return LoadedJobs 包含任务列表和版本号的记录对象
     */
    private LoadedJobs loadJobs() {
        List<CronJob> jobs = new ArrayList<>(); // 初始化任务列表，用于存储加载到的任务
        int version = 1; // 设置默认版本号为 1

        // 检查存储文件是否存在
        if (Files.exists(storePath)) {
            try {
                String raw = Files.readString(storePath); // 读取存储文件的原始内容
                // 将 JSON 字符串反序列化为 Map 对象，以便提取字段
                Map<String, Object> data = mapper.readValue(raw, new TypeReference<>() {});

                // 提取版本号，如果不存在或不是数字类型则默认为 null
                Number versionNum = data.get("version") instanceof Number n ? n : null;
                // 如果版本号有效则使用它，否则保持默认值 1
                version = versionNum != null ? versionNum.intValue() : 1;

                // 获取 "jobs" 字段，它应该是一个列表
                Object jobsObj = data.get("jobs");
                // 检查 jobs 字段是否是 List 类型
                if (jobsObj instanceof List<?> list) {
                    // 遍历列表中的每一项
                    for (Object item : list) {
                        // 检查每一项是否是 Map 类型（即原始的任务数据）
                        if (item instanceof Map<?, ?> rawJob) {
                            @SuppressWarnings("unchecked")
                            // 将原始 Map 强制转换为 String-Object 类型的 Map，方便后续处理
                            Map<String, Object> jobMap = (Map<String, Object>) rawJob;
                            try {
                                // 尝试从 Map 构建 CronJob 对象
                                CronJob job = CronJob.fromMap(jobMap);
                                // 如果构建成功（非 null），则添加到任务列表中
                                if (job != null) {
                                    jobs.add(job);
                                }
                            } catch (Exception e) {
                                // 如果单个任务解析失败，记录警告日志但不中断整个加载过程
                                log.warn("Cron: 解析 job 失败: {}", e.getMessage(), e);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 如果文件读取或整体反序列化失败，记录警告日志
                log.warn("Cron: 加载存储失败 path='{}': {}", storePath, e.getMessage(), e);
            }
        }

        // 返回包含加载到的任务列表和版本号的记录对象
        return new LoadedJobs(jobs, version);
    }

    /**
     * 合并 action.jsonl 文件中的待处理操作到内存存储中
     * 对应 Python: _merge_action()
     *
     * 该方法会读取 action.jsonl 文件，按顺序应用其中的增删改操作到当前的任务列表中，
     * 然后清空或保留解析失败的行。此方法必须在持有 actionLock 的情况下调用（内部会再次加锁以确保安全，或者假设外部已加锁，此处实现为内部加锁）。
     * 注意：当前实现中，外层调用 ensureStoreLoadedLocked 时已经持有了 storeLock 的写锁，
     * 而 mergeActionLocked 内部使用 actionLock 保护对 actionPath 文件的读写。
     */
    private void mergeActionLocked() {
        // 如果 action 日志文件不存在，或者内存中的 store 尚未初始化，则无需合并，直接返回
        if (!Files.exists(actionPath) || store == null) {
            return;
        }

        // 创建一个 LinkedHashMap 来暂存任务，Key 为任务 ID，Value 为任务对象
        // 使用 LinkedHashMap 以保持任务的插入顺序，确保最终保存的顺序可预测
        Map<String, CronJob> jobsMap = new LinkedHashMap<>();
        
        // 将当前内存 store 中的所有任务加载到 jobsMap 中作为基础数据
        for (CronJob job : store.getJobs()) {
            jobsMap.put(job.getId(), job);
        }

        // 获取 action 文件的独占锁，防止与其他线程同时修改 action 文件或读取不一致的状态
        actionLock.lock();
        try {
            // 标记任务列表是否发生了变更，如果未变更则无需更新 store 和文件
            boolean changed = false;
            
            // 用于存储解析失败的行，以便后续写回文件，避免丢失未处理的操作
            List<String> failedLines = new ArrayList<>();
            
            // 使用 try-with-resources 自动关闭 BufferedReader
            try (var reader = Files.newBufferedReader(actionPath)) {
                String line;
                // 逐行读取 action 文件
                while ((line = reader.readLine()) != null) {
                    try {
                        // 跳过空白行
                        if (line.isBlank()) {
                            continue;
                        }

                        // 将 JSON 行反序列化为 Map，提取 action 类型和参数
                        Map<String, Object> action = mapper.readValue(line, new TypeReference<>() {});
                        
                        // 获取操作类型，如 "add", "update", "del"
                        String type = String.valueOf(action.get("action"));
                        
                        // 获取操作参数，确保其为 Map 类型，否则默认为空 Map
                        @SuppressWarnings("unchecked")
                        Map<String, Object> params = action.get("params") instanceof Map<?, ?> p
                                ? (Map<String, Object>) p
                                : Collections.emptyMap();

                        // 根据操作类型执行相应的逻辑
                        if ("del".equals(type)) {
                            // 如果是删除操作
                            // 从参数中获取要删除的任务 ID
                            String jobId = String.valueOf(params.get("job_id"));
                            // 如果 ID 有效且不为 null
                            if (jobId != null && !"null".equals(jobId)) {
                                // 从 jobsMap 中移除该任务
                                jobsMap.remove(jobId);
                                // 标记状态已变更
                                changed = true;
                            }
                        } else {
                            // 如果是添加或更新操作（add/update）
                            // 从参数 Map 中构建 CronJob 对象
                            CronJob job = CronJob.fromMap(params);
                            // 如果构建成功（非 null）
                            if (job != null) {
                                // 将任务放入或更新到 jobsMap 中
                                jobsMap.put(job.getId(), job);
                                // 标记状态已变更
                                changed = true;
                            }
                        }
                    } catch (Exception e) {
                        // 如果某一行解析失败，将其加入失败列表，并记录警告日志
                        failedLines.add(line);
                        log.warn("Cron: action 行解析失败: {}", e.getMessage(), e);
                    }
                }
            }

            // 如果任务列表发生了变更，则更新内存中的 store
            if (changed) {
                // 将 Map 中的值转换为 List 并设置到 store 中
                store.setJobs(new ArrayList<>(jobsMap.values()));
            }

            // 处理 action 文件本身：
            // 如果所有行都解析成功，则清空文件（因为操作已应用到 store）
            // 如果有解析失败的行，则将这些行写回文件，以便下次启动时重试
            if (failedLines.isEmpty()) {
                Files.writeString(actionPath, "");
            } else {
                // 将失败的行重新写入文件，每行之间用换行符分隔，末尾添加换行符
                Files.writeString(actionPath, String.join("\n", failedLines) + "\n");
            }
        } catch (IOException e) {
            // 如果发生 IO 异常（如读取或写入文件失败），记录警告日志
            log.warn("Cron: 合并 action 文件失败: {}", e.getMessage(), e);
        } finally {
            // 无论是否发生异常，最终都要释放 action 锁
            actionLock.unlock();
        }
    }

    /**
     * 在获取读锁的情况下执行存储读取操作
     * 如果存储尚未加载，则升级为写锁进行初始化，然后降级为读锁执行操作
     *
     * @param op 要执行的读取操作
     * @return 操作的结果
     */
    private <T> T withStoreRead(Supplier<T> op) {
        // 首先尝试获取读锁
        storeLock.readLock().lock();
        try {
            // 如果存储已加载，直接执行操作并返回结果
            if (store != null) {
                return op.get();
            }
        } finally {
            // 释放读锁
            storeLock.readLock().unlock();
        }

        // 如果存储未加载，需要获取写锁进行初始化
        storeLock.writeLock().lock();
        try {
            // 确保存储数据已加载到内存中（双重检查，防止其他线程已加载）
            ensureStoreLoadedLocked();
            // 在释放写锁前，先获取读锁，实现锁降级
            storeLock.readLock().lock();
        } finally {
            // 释放写锁，此时仍持有读锁
            storeLock.writeLock().unlock();
        }

        try {
            // 在持有读锁的情况下执行操作并返回结果
            return op.get();
        } finally {
            // 释放读锁
            storeLock.readLock().unlock();
        }
    }

    /**
     * 在获取写锁的情况下执行存储操作
     * 确保在执行操作前存储已加载，并在操作完成后释放锁
     *
     * @param op 要执行的操作
     * @return 操作的结果
     */
    private <T> T withStoreWrite(Supplier<T> op) {
        // 获取写锁，确保对存储的独占访问
        storeLock.writeLock().lock();
        try {
            // 确保存储已加载到内存中
            ensureStoreLoadedLocked();
            // 执行传入的操作并返回结果
            return op.get();
        } finally {
            // 无论操作是否成功，都释放写锁
            storeLock.writeLock().unlock();
        }
    }

    /**
     * 确保存储数据已加载到内存中
     * 该方法必须在持有 storeLock 写锁的情况下调用
     */
    private void ensureStoreLoadedLocked() {
        // 如果 store 已经初始化，则直接返回，避免重复加载
        if (store != null) {
            return;
        }
        // 从磁盘加载任务数据和版本信息
        LoadedJobs loaded = loadJobs();
        // 使用加载的数据初始化内存中的 CronStore 对象
        store = new CronStore(loaded.version(), loaded.jobs());
        // 合并 action.jsonl 文件中的待处理操作（如服务停止期间的增删改）
        mergeActionLocked();
        // 将合并后的最新状态保存回 store.json 文件，确保数据一致性
        saveStoreLocked();
    }

    /**
     * 将当前内存中的任务存储保存到磁盘文件
     * 该方法必须在持有 storeLock 写锁的情况下调用，以确保数据一致性
     */
    private void saveStoreLocked() {
        // 如果内存中的存储对象为空，则无需保存，直接返回
        if (store == null) {
            return;
        }
        try {
            // 确保存储文件的父目录存在，如果不存在则创建（包括必要的中间目录）
            Files.createDirectories(storePath.getParent());
            
            // 将内存中的 CronStore 对象转换为 Map，并使用 Jackson 序列化为格式化的 JSON 字符串
            // writerWithDefaultPrettyPrinter() 用于生成易读的格式化 JSON
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(store.toMap());
            
            // 将生成的 JSON 字符串写入到 storePath 指定的文件中，覆盖原有内容
            Files.writeString(storePath, json);
        } catch (IOException e) {
            // 如果发生 IO 异常（如磁盘满、权限不足等），记录错误日志
            log.error("Cron: 保存存储失败 path='{}': {}", storePath, e.getMessage(), e);
            
            // 抛出运行时异常，中断当前操作，通知上层调用者保存失败
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
        // 设置服务运行状态为true，表示服务已启动
        running = true;
        
        // 确保执行器已初始化（定时任务调度器和工作线程池）
        ensureExecutors();
        
        // 在存储写锁下执行以下操作：
        withStoreWrite(() -> {
            // 重新计算所有任务的下次运行时间
            recomputeNextRunsLocked(nowMs());
            // 将更新后的存储状态保存到文件
            saveStoreLocked();
            // 返回null（因为Supplier需要返回值，这里不需要返回有意义的值）
            return null;
        });
        
        // 设置/重新设置定时器，使其在下一个任务到期时唤醒
        armTimer();
        
        // 获取当前任务数量用于日志输出
        int count = withStoreRead(() -> store != null ? store.getJobs().size() : 0);
        
        // 记录服务启动信息，包括任务数量
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

    /**
     * 确保定时任务调度器和工作线程池已初始化且处于运行状态
     * 如果执行器为空或已关闭，则重新创建它们
     */
    private void ensureExecutors() {
        // 检查调度器是否为空或已关闭
        if (scheduler == null || scheduler.isShutdown()) {
            // 创建单线程的定时任务调度器
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                // 创建名为 "cron-scheduler" 的线程
                Thread t = new Thread(r, "cron-scheduler");
                // 设置为守护线程，当所有非守护线程结束时，JVM 会自动退出
                t.setDaemon(true);
                return t;
            });
        }

        // 检查工作线程池是否为空或已关闭
        if (jobExecutor == null || jobExecutor.isShutdown()) {
            // 计算核心线程数：至少为 2，或者 CPU 核心数的一半
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            
            // 创建固定大小的线程池用于执行具体的定时任务
            jobExecutor = new ThreadPoolExecutor(
                    threads,                // 核心线程数
                    threads,                // 最大线程数（与核心线程数相同，表示固定大小）
                    30L,                    // 空闲线程存活时间
                    TimeUnit.SECONDS,       // 时间单位
                    new LinkedBlockingQueue<>(256), // 工作队列，容量为 256
                    r -> {                  // 线程工厂
                        // 创建名为 "cron-worker" 的线程
                        Thread t = new Thread(r, "cron-worker");
                        // 设置为守护线程
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy() // 拒绝策略：当队列满时抛出 RejectedExecutionException
            );
        }
    }

    /**
     * 关闭并清理执行器资源
     * 立即停止调度器和工作线程池，中断正在执行的任务
     */
    private void shutdownExecutors() {
        // 获取当前调度器的引用并清空成员变量，防止后续误用
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        // 如果调度器存在，则立即强制关闭（中断所有正在执行和等待的任务）
        if (s != null) {
            s.shutdownNow();
        }

        // 获取当前工作线程池的引用并清空成员变量
        ExecutorService w = jobExecutor;
        jobExecutor = null;
        // 如果工作线程池存在，则立即强制关闭
        if (w != null) {
            w.shutdownNow();
        }
    }

    /**
     * 重新计算所有启用任务的下次运行时间
     * 对应 Python: _recompute_next_runs()
     *
     * @param now 当前时间戳（毫秒），用于计算基准时间
     */
    private void recomputeNextRunsLocked(long now) {
        // 如果内存存储未初始化，直接返回
        if (store == null) {
            return;
        }
        // 遍历所有任务
        for (CronJob job : store.getJobs()) {
            // 仅处理已启用的任务
            if (job.isEnabled()) {
                // 根据调度配置和当前时间，计算下一次执行时间并更新到任务状态中
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
            }
        }
    }

    /**
     * 获取所有启用任务中最早的下次运行时间
     * 对应 Python: _get_next_wake_ms()
     *
     * @return 最早的下一次运行时间戳（毫秒），如果没有待执行任务则返回 null
     */
    private Long getNextWakeMsLocked() {
        // 如果内存存储未初始化，返回 null
        if (store == null) {
            return null;
        }
        // 初始化最小时间为 null
        Long min = null;
        // 遍历所有任务寻找最小的 nextRunAtMs
        for (CronJob job : store.getJobs()) {
            // 跳过已禁用的任务
            if (!job.isEnabled()) continue;
            
            // 获取任务的下次运行时间
            Long next = job.getState().getNextRunAtMs();
            // 如果下次运行时间为 null（例如一次性任务已过时或 cron 解析失败），跳过
            if (next == null) continue;
            
            // 如果当前最小值为 null，或者找到的时间更早，则更新最小值
            if (min == null || next < min) {
                min = next;
            }
        }
        // 返回找到的最早执行时间，若无则返回 null
        return min;
    }

    /**
     * 启动或重新调度定时器，确保在最近的到期时间唤醒
     * 对应 Python: _arm_timer()
     */
    private synchronized void armTimer() {
        // 如果存在已调度的定时任务句柄，先取消它
        // cancel(false) 表示如果任务正在执行，允许其执行完成，但不重复执行
        if (timerTask != null) {
            timerTask.cancel(false);
        }

        // 如果服务未处于运行状态，则不再调度新任务，直接返回
        if (!running) {
            return;
        }

        // 获取当前调度器的本地引用，避免并发修改问题
        ScheduledExecutorService localScheduler = this.scheduler;
        // 如果调度器为空或已关闭，无法进行调度，直接返回
        if (localScheduler == null || localScheduler.isShutdown()) {
            return;
        }

        // 在读取锁保护下获取下一个唤醒时间点
        Long nextWake = withStoreRead(this::getNextWakeMsLocked);
        
        long delayMs;
        if (nextWake == null) {
            // 如果没有待执行的任务，使用最大休眠时间，避免 CPU 空转或无限等待
            delayMs = maxSleepMs;
        } else {
            // 计算距离下次执行的延迟时间：
            // 1. nextWake - nowMs(): 理论延迟
            // 2. Math.max(0, ...): 确保延迟不为负数（防止过去的时间导致立即执行或异常）
            // 3. Math.min(maxSleepMs, ...): 确保延迟不超过最大限制，保证定期有机会检查状态变化
            delayMs = Math.min(maxSleepMs, Math.max(0, nextWake - nowMs()));
        }

        // 调度一个新的定时任务，在计算出的 delayMs 毫秒后执行 onTimer 方法
        timerTask = localScheduler.schedule(() -> {
            // 再次检查服务是否仍在运行，防止在休眠期间服务被停止后仍执行逻辑
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
