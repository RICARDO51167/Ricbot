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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

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
public class CronService {

    /**
     * 单条 job 最多保留多少条运行历史
     *
     * 对应 Python: _MAX_RUN_HISTORY = 20
     */
    private static final int MAX_RUN_HISTORY = 20;

    /**
     * action file lock
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * store.json 路径
     */
    private final Path storePath;

    /**
     * action.jsonl 路径
     */
    private final Path actionPath;

    /**
     * 任务执行回调
     *
     * 对应 Python:
     * on_job: Callable[[CronJob], Coroutine[Any, Any, str | None]] | None
     */
    private JobHandler onJob;

    /**
     * 最长睡眠时间，避免没任务时无限 sleep
     *
     * 对应 Python: max_sleep_ms = 300_000
     */
    private final long maxSleepMs;

    private final ObjectMapper mapper = new ObjectMapper();

    private CronStore store;
    private volatile boolean running = false;
    private volatile boolean timerActive = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
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

    // =========================================================
    // Callback interface
    // =========================================================

    @FunctionalInterface
    public interface JobHandler {
        String handle(CronJob job) throws Exception;
    }

    // =========================================================
    // Internal time helpers
    // =========================================================

    /**
     * 对应 Python: _now_ms()
     */
    public static long nowMs() {
        return System.currentTimeMillis();
    }

    /**
     * 对应 Python: _compute_next_run(schedule, now_ms)
     */
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

                // 这里做一个简化 cron 解析器：
                // 只支持标准 5 段 cron: min hour day month weekday
                // 后续如果你想完全对齐 Python croniter，可以换成 cron-utils。
                return CronExpressionUtils.nextExecutionMillis(expr, zone, nowMs);
            } catch (Exception e) {
                return null;
            }
        }

        return null;
    }

    /**
     * 对应 Python: _validate_schedule_for_add(schedule)
     */
    public static void validateScheduleForAdd(CronSchedule schedule) {
        if (schedule == null || schedule.getKind() == null) {
            throw new IllegalArgumentException("schedule.kind is required");
        }

        if (schedule.getTz() != null && !schedule.getTz().isBlank()
                && schedule.getKind() != ScheduleKind.CRON) {
            throw new IllegalArgumentException("tz can only be used with cron schedules");
        }

        if (schedule.getKind() == ScheduleKind.CRON && schedule.getTz() != null && !schedule.getTz().isBlank()) {
            try {
                ZoneId.of(schedule.getTz());
            } catch (Exception e) {
                throw new IllegalArgumentException("unknown timezone '" + schedule.getTz() + "'");
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
                            CronJob job = CronJob.fromMap(jobMap);
                            if (job != null) {
                                jobs.add(job);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to load cron store: " + e.getMessage());
            }
        }

        return new LoadedJobs(jobs, version);
    }

    /**
     * 对应 Python: _merge_action()
     */
    private void mergeAction() {
        if (!Files.exists(actionPath) || store == null) {
            return;
        }

        Map<String, CronJob> jobsMap = new LinkedHashMap<>();
        for (CronJob job : store.getJobs()) {
            jobsMap.put(job.getId(), job);
        }

        lock.lock();
        try {
            List<String> lines = Files.readAllLines(actionPath);
            boolean changed = false;

            for (String line : lines) {
                try {
                    if (line == null || line.isBlank()) {
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
                    System.err.println("load action line error: " + e.getMessage());
                }
            }

            store.setJobs(new ArrayList<>(jobsMap.values()));

            if (running && changed) {
                Files.writeString(actionPath, "");
                saveStore();
            }
        } catch (IOException e) {
            System.err.println("Failed to merge action file: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 对应 Python: _load_store()
     */
    private CronStore loadStore() {
        if (timerActive && store != null) {
            return store;
        }

        LoadedJobs loaded = loadJobs();
        store = new CronStore(loaded.version(), loaded.jobs());
        mergeAction();
        return store;
    }

    /**
     * 对应 Python: _save_store()
     */
    private void saveStore() {
        if (store == null) {
            return;
        }

        try {
            Files.createDirectories(storePath.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(store.toMap());
            Files.writeString(storePath, json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save cron store", e);
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
        loadStore();
        recomputeNextRuns();
        saveStore();
        armTimer();
        System.out.println("Cron service started with " + (store != null ? store.getJobs().size() : 0) + " jobs");
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
    }

    /**
     * 对应 Python: _recompute_next_runs()
     */
    private void recomputeNextRuns() {
        if (store == null) {
            return;
        }

        long now = nowMs();
        for (CronJob job : store.getJobs()) {
            if (job.isEnabled()) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
            }
        }
    }

    /**
     * 对应 Python: _get_next_wake_ms()
     */
    private Long getNextWakeMs() {
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
     */
    private synchronized void armTimer() {
        if (timerTask != null) {
            timerTask.cancel(false);
        }

        if (!running) {
            return;
        }

        Long nextWake = getNextWakeMs();
        long delayMs;
        if (nextWake == null) {
            delayMs = maxSleepMs;
        } else {
            delayMs = Math.min(maxSleepMs, Math.max(0, nextWake - nowMs()));
        }

        timerTask = scheduler.schedule(() -> {
            if (running) {
                onTimer();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 对应 Python: async _on_timer()
     */
    private void onTimer() {
        loadStore();
        if (store == null) {
            armTimer();
            return;
        }

        timerActive = true;
        try {
            long now = nowMs();
            List<CronJob> dueJobs = new ArrayList<>();

            for (CronJob job : store.getJobs()) {
                Long nextRun = job.getState().getNextRunAtMs();
                if (job.isEnabled() && nextRun != null && now >= nextRun) {
                    dueJobs.add(job);
                }
            }

            for (CronJob job : dueJobs) {
                executeJob(job);
            }

            saveStore();
        } finally {
            timerActive = false;
        }

        armTimer();
    }

    /**
     * 对应 Python: async _execute_job(job)
     */
    private void executeJob(CronJob job) {
        long startMs = nowMs();
        System.out.println("Cron: executing job '" + job.getName() + "' (" + job.getId() + ")");

        try {
            if (onJob != null) {
                onJob.handle(job);
            }

            job.getState().setLastStatus(RunStatus.OK);
            job.getState().setLastError(null);
            System.out.println("Cron: job '" + job.getName() + "' completed");
        } catch (Exception e) {
            job.getState().setLastStatus(RunStatus.ERROR);
            job.getState().setLastError(e.getMessage());
            System.err.println("Cron: job '" + job.getName() + "' failed: " + e.getMessage());
        }

        long endMs = nowMs();
        job.getState().setLastRunAtMs(startMs);
        job.setUpdatedAtMs(endMs);

        job.getState().getRunHistory().add(new CronRunRecord(
                startMs,
                job.getState().getLastStatus(),
                endMs - startMs,
                job.getState().getLastError()
        ));

        List<CronRunRecord> history = job.getState().getRunHistory();
        if (history.size() > MAX_RUN_HISTORY) {
            job.getState().setRunHistory(new ArrayList<>(history.subList(history.size() - MAX_RUN_HISTORY, history.size())));
        }

        if (job.getSchedule().getKind() == ScheduleKind.AT) {
            if (job.isDeleteAfterRun()) {
                store.setJobs(store.getJobs().stream().filter(j -> !Objects.equals(j.getId(), job.getId())).toList());
            } else {
                job.setEnabled(false);
                job.getState().setNextRunAtMs(null);
            }
        } else {
            job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), nowMs()));
        }
    }

    // =========================================================
    // Action append
    // =========================================================

    /**
     * 对应 Python: _append_action(action, params)
     */
    private void appendAction(String action, Map<String, Object> params) {
        try {
            Files.createDirectories(actionPath.getParent());
            lock.lock();
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
                lock.unlock();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to append cron action", e);
        }
    }

    // =========================================================
    // Public API
    // =========================================================

    /**
     * 对应 Python: list_jobs(include_disabled=False)
     */
    public List<CronJob> listJobs(boolean includeDisabled) {
        CronStore s = loadStore();
        List<CronJob> jobs = includeDisabled
                ? new ArrayList<>(s.getJobs())
                : s.getJobs().stream().filter(CronJob::isEnabled).toList();

        jobs.sort(Comparator.comparing(
                j -> j.getState().getNextRunAtMs() != null ? j.getState().getNextRunAtMs() : Long.MAX_VALUE
        ));
        return jobs;
    }

    public List<CronJob> listJobs() {
        return listJobs(false);
    }

    /**
     * 对应 Python: add_job(...)
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
            CronStore s = loadStore();
            s.getJobs().add(job);
            saveStore();
            armTimer();
        } else {
            appendAction("add", job.toMap());
        }

        System.out.println("Cron: added job '" + name + "' (" + job.getId() + ")");
        return job;
    }

    /**
     * 对应 Python: register_system_job(job)
     */
    public CronJob registerSystemJob(CronJob job) {
        CronStore s = loadStore();
        long now = nowMs();

        job.setState(new CronJobState());
        job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
        job.setCreatedAtMs(now);
        job.setUpdatedAtMs(now);

        List<CronJob> filtered = new ArrayList<>();
        for (CronJob j : s.getJobs()) {
            if (!Objects.equals(j.getId(), job.getId())) {
                filtered.add(j);
            }
        }
        filtered.add(job);
        s.setJobs(filtered);

        saveStore();
        armTimer();

        System.out.println("Cron: registered system job '" + job.getName() + "' (" + job.getId() + ")");
        return job;
    }

    /**
     * 对应 Python: remove_job(job_id)
     *
     * 返回:
     * - removed
     * - protected
     * - not_found
     */
    public String removeJob(String jobId) {
        CronStore s = loadStore();
        CronJob target = null;

        for (CronJob job : s.getJobs()) {
            if (Objects.equals(job.getId(), jobId)) {
                target = job;
                break;
            }
        }

        if (target == null) {
            return "not_found";
        }

        if (target.getPayload() != null && target.getPayload().getKind() == PayloadKind.SYSTEM_EVENT) {
            System.out.println("Cron: refused to remove protected system job " + jobId);
            return "protected";
        }

        int before = s.getJobs().size();
        s.setJobs(s.getJobs().stream().filter(j -> !Objects.equals(j.getId(), jobId)).toList());

        boolean removed = s.getJobs().size() < before;
        if (!removed) {
            return "not_found";
        }

        if (running) {
            saveStore();
            armTimer();
        } else {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("job_id", jobId);
            appendAction("del", params);
        }

        System.out.println("Cron: removed job " + jobId);
        return "removed";
    }

    /**
     * 对应 Python: enable_job(job_id, enabled=True)
     */
    public CronJob enableJob(String jobId, boolean enabled) {
        CronStore s = loadStore();

        for (CronJob job : s.getJobs()) {
            if (Objects.equals(job.getId(), jobId)) {
                job.setEnabled(enabled);
                job.setUpdatedAtMs(nowMs());

                if (enabled) {
                    job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), nowMs()));
                } else {
                    job.getState().setNextRunAtMs(null);
                }

                if (running) {
                    saveStore();
                    armTimer();
                } else {
                    appendAction("update", job.toMap());
                }

                return job;
            }
        }

        return null;
    }

    public CronJob disableJob(String jobId) {
        return enableJob(jobId, false);
    }

    /**
     * 对应 Python: update_job(...)
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
        CronStore s = loadStore();
        CronJob job = null;

        for (CronJob j : s.getJobs()) {
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

        // 对齐 Python 的 sentinel 语义:
        // channel / to 使用显式 sentinel 判断是否修改
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

        if (running) {
            saveStore();
            armTimer();
        } else {
            appendAction("update", job.toMap());
        }

        System.out.println("Cron: updated job '" + job.getName() + "' (" + job.getId() + ")");
        return job;
    }

    /**
     * 对应 Python: run_job(job_id, force=False)
     */
    public boolean runJob(String jobId, boolean force) {
        boolean wasRunning = running;
        running = true;

        try {
            CronStore s = loadStore();
            for (CronJob job : s.getJobs()) {
                if (Objects.equals(job.getId(), jobId)) {
                    if (!force && !job.isEnabled()) {
                        return false;
                    }
                    executeJob(job);
                    saveStore();
                    return true;
                }
            }
            return false;
        } finally {
            running = wasRunning;
            if (wasRunning) {
                armTimer();
            }
        }
    }

    public boolean runJob(String jobId) {
        return runJob(jobId, false);
    }

    /**
     * 对应 Python: get_job(job_id)
     */
    public CronJob getJob(String jobId) {
        CronStore s = loadStore();
        for (CronJob job : s.getJobs()) {
            if (Objects.equals(job.getId(), jobId)) {
                return job;
            }
        }
        return null;
    }

    /**
     * 对应 Python: status()
     */
    public Map<String, Object> status() {
        CronStore s = loadStore();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", running);
        map.put("jobs", s.getJobs().size());
        map.put("next_wake_at_ms", getNextWakeMs());
        return map;
    }

    public void setOnJob(JobHandler onJob) {
        this.onJob = onJob;
    }

    // =========================================================
    // Helper DTOs / sentinels
    // =========================================================

    private record LoadedJobs(List<CronJob> jobs, int version) {
    }

    /**
     * 用来模拟 Python 中 channel=... / to=... 的“未传入”语义
     */
    public static final class UnchangedSentinel {
        private UnchangedSentinel() {
        }
    }

    public static final UnchangedSentinel UNCHANGED = new UnchangedSentinel();

    // =========================================================
    // Minimal cron parser helper
    // =========================================================

    /**
     * 这是一个轻量 cron 解析器，只支持 5 段标准 cron。
     * 用来替代 Python 里的 croniter。
     *
     * 支持：
     * minute hour day month weekday
     *
     * 不追求完全 croniter 兼容，但足够覆盖常见场景。
     */
    public static final class CronExpressionUtils {

        private CronExpressionUtils() {
        }

        public static Long nextExecutionMillis(String expr, ZoneId zone, long nowMs) {
            String[] parts = expr.trim().split("\\s+");
            if (parts.length != 5) {
                throw new IllegalArgumentException("Unsupported cron expression: " + expr);
            }

            CronField minutes = CronField.parse(parts[0], 0, 59);
            CronField hours = CronField.parse(parts[1], 0, 23);
            CronField days = CronField.parse(parts[2], 1, 31);
            CronField months = CronField.parse(parts[3], 1, 12);
            CronField weekdays = CronField.parse(parts[4], 0, 6);

            ZonedDateTime time = Instant.ofEpochMilli(nowMs).atZone(zone)
                    .withSecond(0).withNano(0)
                    .plusMinutes(1);

            // 最多向后找 5 年，避免死循环
            for (int i = 0; i < 60 * 24 * 366 * 5; i++) {
                int minute = time.getMinute();
                int hour = time.getHour();
                int day = time.getDayOfMonth();
                int month = time.getMonthValue();
                int weekday = time.getDayOfWeek().getValue() % 7; // Sunday -> 0

                if (minutes.matches(minute)
                        && hours.matches(hour)
                        && days.matches(day)
                        && months.matches(month)
                        && weekdays.matches(weekday)) {
                    return time.toInstant().toEpochMilli();
                }

                time = time.plusMinutes(1);
            }

            return null;
        }

        private static final class CronField {
            private final boolean any;
            private final Set<Integer> values;

            private CronField(boolean any, Set<Integer> values) {
                this.any = any;
                this.values = values;
            }

            static CronField parse(String raw, int min, int max) {
                raw = raw.trim();
                if ("*".equals(raw)) {
                    return new CronField(true, Collections.emptySet());
                }

                Set<Integer> values = new LinkedHashSet<>();
                String[] segments = raw.split(",");

                for (String seg : segments) {
                    seg = seg.trim();

                    if (seg.contains("/")) {
                        String[] stepParts = seg.split("/", 2);
                        String base = stepParts[0];
                        int step = Integer.parseInt(stepParts[1]);

                        int rangeStart = min;
                        int rangeEnd = max;

                        if (!"*".equals(base)) {
                            if (base.contains("-")) {
                                String[] range = base.split("-", 2);
                                rangeStart = Integer.parseInt(range[0]);
                                rangeEnd = Integer.parseInt(range[1]);
                            } else {
                                rangeStart = Integer.parseInt(base);
                                rangeEnd = max;
                            }
                        }

                        for (int v = rangeStart; v <= rangeEnd; v += step) {
                            if (v >= min && v <= max) {
                                values.add(v);
                            }
                        }
                    } else if (seg.contains("-")) {
                        String[] range = seg.split("-", 2);
                        int start = Integer.parseInt(range[0]);
                        int end = Integer.parseInt(range[1]);
                        for (int v = start; v <= end; v++) {
                            if (v >= min && v <= max) {
                                values.add(v);
                            }
                        }
                    } else {
                        int v = Integer.parseInt(seg);
                        if (v < min || v > max) {
                            throw new IllegalArgumentException("cron field out of range: " + raw);
                        }
                        values.add(v);
                    }
                }

                return new CronField(false, values);
            }

            boolean matches(int value) {
                return any || values.contains(value);
            }
        }
    }
}