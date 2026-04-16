package ricbot.infra.cron;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.cron.CronTypes.CronJob;
import ricbot.infra.cron.CronTypes.CronJobState;
import ricbot.infra.cron.CronTypes.CronPayload;
import ricbot.infra.cron.CronTypes.CronRunRecord;
import ricbot.infra.cron.CronTypes.CronSchedule;
import ricbot.infra.cron.CronTypes.CronStore;
import ricbot.infra.cron.CronTypes.PayloadKind;
import ricbot.infra.cron.CronTypes.RunStatus;
import ricbot.infra.cron.CronTypes.ScheduleKind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * 定时任务服务，负责任务的管理、调度与执行。
 */
public class CronService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CronService.class);

    private static final int MAX_RUN_HISTORY = 20;

    private final ReentrantLock actionLock = new ReentrantLock();

    private final ReentrantReadWriteLock storeLock = new ReentrantReadWriteLock();

    private final Path storePath;

    private final Path actionPath;

    private JobHandler onJob;

    private final long maxSleepMs;

    private final ObjectMapper mapper = new ObjectMapper();

    private CronStore store;
    private volatile boolean running = false;
    private volatile ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile ExecutorService jobExecutor = Executors.newCachedThreadPool();
    private ScheduledFuture<?> timerTask;

    public CronService(Path storePath) {
        this(storePath, null, 300_000);
    }

    public CronService(Path storePath, JobHandler onJob, long maxSleepMs) {
        this.storePath = storePath;
        this.actionPath = storePath.getParent().resolve("action.jsonl");
        this.onJob = onJob;
        this.maxSleepMs = maxSleepMs;
    }

    @FunctionalInterface
    public interface JobHandler {
        String handle(CronJob job) throws Exception;
    }

    public static long nowMs() {
        return System.currentTimeMillis();
    }

    public static Long computeNextRun(CronSchedule schedule, long nowMs) {
        if (schedule == null || schedule.getKind() == null) {
            return null;
        }

        if (schedule.getKind() == ScheduleKind.AT) {
            Long atMs = schedule.getAtMs();
            return (atMs != null && atMs > nowMs) ? atMs : null;
        }

        if (schedule.getKind() == ScheduleKind.EVERY) {
            Long everyMs = schedule.getEveryMs();
            if (everyMs == null || everyMs <= 0) {
                return null;
            }
            return nowMs + everyMs;
        }

        if (schedule.getKind() == ScheduleKind.CRON) {
            String expr = schedule.getExpr();
            if (expr == null || expr.isBlank()) {
                return null;
            }

            try {
                ZoneId zone = schedule.getTz() != null && !schedule.getTz().isBlank()
                        ? ZoneId.of(schedule.getTz())
                        : ZoneId.systemDefault();

                return CronExpressionUtils.nextExecutionMillis(expr, zone, nowMs);
            } catch (Exception e) {
                log.warn("Cron: cron 表达式解析失败 expr='{}' tz='{}': {}", expr, schedule.getTz(), e.getMessage(), e);
                return null;
            }
        }

        return null;
    }

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

    private LoadedJobs loadJobs() {
        List<CronJob> jobs = new ArrayList<>();
        int version = 1;

        if (Files.exists(storePath)) {
            try {
                String raw = Files.readString(storePath);
                Map<String, Object> data = mapper.readValue(raw, new TypeReference<>() {});

                Number versionNum = data.get("version") instanceof Number n ? n : null;
                version = versionNum != null ? versionNum.intValue() : 1;

                Object jobsObj = data.get("jobs");
                if (jobsObj instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> rawJob) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> jobMap = (Map<String, Object>) rawJob;
                            try {
                                CronJob job = CronJob.fromMap(jobMap);
                                if (job != null) {
                                    jobs.add(job);
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

        return new LoadedJobs(jobs, version);
    }

    private void mergeActionLocked() {
        if (!Files.exists(actionPath) || store == null) {
            return;
        }

        Map<String, CronJob> jobsMap = new LinkedHashMap<>();
        for (CronJob job : store.getJobs()) {
            jobsMap.put(job.getId(), job);
        }

        actionLock.lock();
        try {
            boolean changed = false;
            List<String> failedLines = new ArrayList<>();
            try (var reader = Files.newBufferedReader(actionPath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        if (line.isBlank()) {
                            continue;
                        }

                        Map<String, Object> action = mapper.readValue(line, new TypeReference<>() {});
                        String type = String.valueOf(action.get("action"));
                        @SuppressWarnings("unchecked")
                        Map<String, Object> params = action.get("params") instanceof Map<?, ?> p
                                ? (Map<String, Object>) p
                                : Collections.emptyMap();

                        if ("del".equals(type)) {
                            String jobId = String.valueOf(params.get("job_id"));
                            if (jobId != null) {
                                jobsMap.remove(jobId);
                                changed = true;
                            }
                        } else {
                            CronJob job = CronJob.fromMap(params);
                            if (job != null) {
                                jobsMap.put(job.getId(), job);
                                changed = true;
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
            actionLock.unlock();
        }
    }

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

    private synchronized void armTimer() {
        if (timerTask != null) {
            timerTask.cancel(false);
        }

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
            delayMs = maxSleepMs;
        } else {
            delayMs = Math.min(maxSleepMs, Math.max(0, nextWake - nowMs()));
        }

        timerTask = localScheduler.schedule(() -> {
            if (running) {
                onTimer();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

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

    private void appendAction(String action, Map<String, Object> params) {
        try {
            Files.createDirectories(actionPath.getParent());
            actionLock.lock();
            try {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("action", action);
                row.put("params", params);
                String line = mapper.writeValueAsString(row) + "\n";

                Files.writeString(
                        actionPath,
                        line,
                        Files.exists(actionPath)
                                ? java.nio.file.StandardOpenOption.APPEND
                                : java.nio.file.StandardOpenOption.CREATE
                );
            } finally {
                actionLock.unlock();
            }
        } catch (IOException e) {
            throw new RuntimeException("追加 cron action 失败", e);
        }
    }

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

    public List<CronJob> listJobs() {
        return listJobs(false);
    }

    public CronJob addJob(
            String name,
            CronSchedule schedule,
            String message,
            boolean deliver,
            String channel,
            String to,
            boolean deleteAfterRun
    ) {
        validateScheduleForAdd(schedule);
        long now = nowMs();

        CronJob job = new CronJob();
        job.setId(UUID.randomUUID().toString().substring(0, 8));
        job.setName(name);
        job.setEnabled(true);
        job.setSchedule(schedule);

        CronPayload payload = new CronPayload();
        payload.setKind(PayloadKind.AGENT_TURN);
        payload.setMessage(message);
        payload.setDeliver(deliver);
        payload.setChannel(channel);
        payload.setTo(to);
        job.setPayload(payload);

        CronJobState state = new CronJobState();
        state.setNextRunAtMs(computeNextRun(schedule, now));
        job.setState(state);

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

    public CronJob disableJob(String jobId) {
        return enableJob(jobId, false);
    }

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

    public boolean runJob(String jobId) {
        return runJob(jobId, false);
    }

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

    public Map<String, Object> status() {
        return withStoreRead(() -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("enabled", running);
            map.put("jobs", store != null ? store.getJobs().size() : 0);
            map.put("next_wake_at_ms", getNextWakeMsLocked());
            return map;
        });
    }

    public void setOnJob(JobHandler onJob) {
        this.onJob = onJob;
    }

    private record LoadedJobs(List<CronJob> jobs, int version) {
    }

    public static final class UnchangedSentinel {
        private UnchangedSentinel() {
        }
    }

    public static final UnchangedSentinel UNCHANGED = new UnchangedSentinel();
}
