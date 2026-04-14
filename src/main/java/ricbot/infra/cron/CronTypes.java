package ricbot.infra.cron;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对应 Python: types.py
 *
 * 主要目标：
 * 1. 定义 Cron 调度相关的数据结构
 * 2. 对应 Python dataclass:
 *    - CronSchedule
 *    - CronPayload
 *    - CronRunRecord
 *    - CronJobState
 *    - CronJob
 *    - CronStore
 */
public final class CronTypes {

    private CronTypes() {
    }

    // =========================================================
    // Enums
    // =========================================================

    public enum ScheduleKind {
        AT("at"),
        EVERY("every"),
        CRON("cron");

        private final String value;

        ScheduleKind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static ScheduleKind fromValue(String value) {
            if (value == null) {
                return EVERY;
            }
            for (ScheduleKind k : values()) {
                if (k.value.equalsIgnoreCase(value)) {
                    return k;
                }
            }
            return EVERY;
        }
    }

    public enum PayloadKind {
        SYSTEM_EVENT("system_event"),
        AGENT_TURN("agent_turn");

        private final String value;

        PayloadKind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static PayloadKind fromValue(String value) {
            if (value == null) {
                return AGENT_TURN;
            }
            for (PayloadKind k : values()) {
                if (k.value.equalsIgnoreCase(value)) {
                    return k;
                }
            }
            return AGENT_TURN;
        }
    }

    public enum RunStatus {
        OK("ok"),
        ERROR("error"),
        SKIPPED("skipped");

        private final String value;

        RunStatus(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static RunStatus fromValue(String value) {
            if (value == null) {
                return null;
            }
            for (RunStatus s : values()) {
                if (s.value.equalsIgnoreCase(value)) {
                    return s;
                }
            }
            return null;
        }
    }

    // =========================================================
    // CronSchedule
    // =========================================================

    /**
     * 对应 Python: CronSchedule
     */
    public static class CronSchedule {
        private ScheduleKind kind;
        private Long atMs;
        private Long everyMs;
        private String expr;
        private String tz;

        public CronSchedule() {
            this.kind = ScheduleKind.EVERY;
        }

        public CronSchedule(ScheduleKind kind) {
            this.kind = kind;
        }

        public CronSchedule(ScheduleKind kind, Long atMs, Long everyMs, String expr, String tz) {
            this.kind = kind;
            this.atMs = atMs;
            this.everyMs = everyMs;
            this.expr = expr;
            this.tz = tz;
        }

        public ScheduleKind getKind() {
            return kind;
        }

        public void setKind(ScheduleKind kind) {
            this.kind = kind;
        }

        public Long getAtMs() {
            return atMs;
        }

        public void setAtMs(Long atMs) {
            this.atMs = atMs;
        }

        public Long getEveryMs() {
            return everyMs;
        }

        public void setEveryMs(Long everyMs) {
            this.everyMs = everyMs;
        }

        public String getExpr() {
            return expr;
        }

        public void setExpr(String expr) {
            this.expr = expr;
        }

        public String getTz() {
            return tz;
        }

        public void setTz(String tz) {
            this.tz = tz;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind != null ? kind.value() : null);
            map.put("atMs", atMs);
            map.put("everyMs", everyMs);
            map.put("expr", expr);
            map.put("tz", tz);
            return map;
        }

        @SuppressWarnings("unchecked")
        public static CronSchedule fromMap(Map<String, Object> map) {
            if (map == null) {
                return new CronSchedule(ScheduleKind.EVERY);
            }

            CronSchedule s = new CronSchedule();
            s.setKind(ScheduleKind.fromValue(string(map.get("kind"))));
            s.setAtMs(longValue(map.get("atMs")));
            s.setEveryMs(longValue(map.get("everyMs")));
            s.setExpr(string(map.get("expr")));
            s.setTz(string(map.get("tz")));
            return s;
        }

        @Override
        public String toString() {
            return "CronSchedule{" +
                    "kind=" + kind +
                    ", atMs=" + atMs +
                    ", everyMs=" + everyMs +
                    ", expr='" + expr + '\'' +
                    ", tz='" + tz + '\'' +
                    '}';
        }
    }

    // =========================================================
    // CronPayload
    // =========================================================

    /**
     * 对应 Python: CronPayload
     */
    public static class CronPayload {
        private PayloadKind kind = PayloadKind.AGENT_TURN;
        private String message = "";
        private boolean deliver = false;
        private String channel;
        private String to;

        public CronPayload() {
        }

        public PayloadKind getKind() {
            return kind;
        }

        public void setKind(PayloadKind kind) {
            this.kind = kind;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public boolean isDeliver() {
            return deliver;
        }

        public void setDeliver(boolean deliver) {
            this.deliver = deliver;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind != null ? kind.value() : null);
            map.put("message", message);
            map.put("deliver", deliver);
            map.put("channel", channel);
            map.put("to", to);
            return map;
        }

        public static CronPayload fromMap(Map<String, Object> map) {
            CronPayload p = new CronPayload();
            if (map == null) {
                return p;
            }
            p.setKind(PayloadKind.fromValue(string(map.get("kind"))));
            p.setMessage(defaultString(string(map.get("message"))));
            p.setDeliver(bool(map.get("deliver")));
            p.setChannel(string(map.get("channel")));
            p.setTo(string(map.get("to")));
            return p;
        }

        @Override
        public String toString() {
            return "CronPayload{" +
                    "kind=" + kind +
                    ", message='" + message + '\'' +
                    ", deliver=" + deliver +
                    ", channel='" + channel + '\'' +
                    ", to='" + to + '\'' +
                    '}';
        }
    }

    // =========================================================
    // CronRunRecord
    // =========================================================

    /**
     * 对应 Python: CronRunRecord
     */
    public static class CronRunRecord {
        private long runAtMs;
        private RunStatus status;
        private long durationMs = 0;
        private String error;

        public CronRunRecord() {
        }

        public CronRunRecord(long runAtMs, RunStatus status, long durationMs, String error) {
            this.runAtMs = runAtMs;
            this.status = status;
            this.durationMs = durationMs;
            this.error = error;
        }

        public long getRunAtMs() {
            return runAtMs;
        }

        public void setRunAtMs(long runAtMs) {
            this.runAtMs = runAtMs;
        }

        public RunStatus getStatus() {
            return status;
        }

        public void setStatus(RunStatus status) {
            this.status = status;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public void setDurationMs(long durationMs) {
            this.durationMs = durationMs;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("runAtMs", runAtMs);
            map.put("status", status != null ? status.value() : null);
            map.put("durationMs", durationMs);
            map.put("error", error);
            return map;
        }

        public static CronRunRecord fromMap(Map<String, Object> map) {
            if (map == null) {
                return null;
            }
            return new CronRunRecord(
                    longValue(map.get("runAtMs")) != null ? longValue(map.get("runAtMs")) : 0L,
                    RunStatus.fromValue(string(map.get("status"))),
                    longValue(map.get("durationMs")) != null ? longValue(map.get("durationMs")) : 0L,
                    string(map.get("error"))
            );
        }

        @Override
        public String toString() {
            return "CronRunRecord{" +
                    "runAtMs=" + runAtMs +
                    ", status=" + status +
                    ", durationMs=" + durationMs +
                    ", error='" + error + '\'' +
                    '}';
        }
    }

    // =========================================================
    // CronJobState
    // =========================================================

    /**
     * 对应 Python: CronJobState
     */
    public static class CronJobState {
        private Long nextRunAtMs;
        private Long lastRunAtMs;
        private RunStatus lastStatus;
        private String lastError;
        private List<CronRunRecord> runHistory = new ArrayList<>();

        public CronJobState() {
        }

        public Long getNextRunAtMs() {
            return nextRunAtMs;
        }

        public void setNextRunAtMs(Long nextRunAtMs) {
            this.nextRunAtMs = nextRunAtMs;
        }

        public Long getLastRunAtMs() {
            return lastRunAtMs;
        }

        public void setLastRunAtMs(Long lastRunAtMs) {
            this.lastRunAtMs = lastRunAtMs;
        }

        public RunStatus getLastStatus() {
            return lastStatus;
        }

        public void setLastStatus(RunStatus lastStatus) {
            this.lastStatus = lastStatus;
        }

        public String getLastError() {
            return lastError;
        }

        public void setLastError(String lastError) {
            this.lastError = lastError;
        }

        public List<CronRunRecord> getRunHistory() {
            return runHistory;
        }

        public void setRunHistory(List<CronRunRecord> runHistory) {
            this.runHistory = runHistory;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("nextRunAtMs", nextRunAtMs);
            map.put("lastRunAtMs", lastRunAtMs);
            map.put("lastStatus", lastStatus != null ? lastStatus.value() : null);
            map.put("lastError", lastError);

            List<Map<String, Object>> history = new ArrayList<>();
            for (CronRunRecord record : runHistory) {
                history.add(record.toMap());
            }
            map.put("runHistory", history);
            return map;
        }

        @SuppressWarnings("unchecked")
        public static CronJobState fromMap(Map<String, Object> map) {
            CronJobState s = new CronJobState();
            if (map == null) {
                return s;
            }

            s.setNextRunAtMs(longValue(map.get("nextRunAtMs")));
            s.setLastRunAtMs(longValue(map.get("lastRunAtMs")));
            s.setLastStatus(RunStatus.fromValue(string(map.get("lastStatus"))));
            s.setLastError(string(map.get("lastError")));

            Object historyObj = map.get("runHistory");
            List<CronRunRecord> history = new ArrayList<>();
            if (historyObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> raw) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> recMap = (Map<String, Object>) raw;
                        CronRunRecord rec = CronRunRecord.fromMap(recMap);
                        if (rec != null) {
                            history.add(rec);
                        }
                    } else if (item instanceof CronRunRecord rec) {
                        history.add(rec);
                    }
                }
            }

            s.setRunHistory(history);
            return s;
        }

        @Override
        public String toString() {
            return "CronJobState{" +
                    "nextRunAtMs=" + nextRunAtMs +
                    ", lastRunAtMs=" + lastRunAtMs +
                    ", lastStatus=" + lastStatus +
                    ", lastError='" + lastError + '\'' +
                    ", runHistory=" + runHistory +
                    '}';
        }
    }

    // =========================================================
    // CronJob
    // =========================================================

    /**
     * 对应 Python: CronJob
     */
    public static class CronJob {
        private String id;
        private String name;
        private boolean enabled = true;
        private CronSchedule schedule = new CronSchedule(ScheduleKind.EVERY);
        private CronPayload payload = new CronPayload();
        private CronJobState state = new CronJobState();
        private long createdAtMs = 0;
        private long updatedAtMs = 0;
        private boolean deleteAfterRun = false;

        public CronJob() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public CronSchedule getSchedule() {
            return schedule;
        }

        public void setSchedule(CronSchedule schedule) {
            this.schedule = schedule;
        }

        public CronPayload getPayload() {
            return payload;
        }

        public void setPayload(CronPayload payload) {
            this.payload = payload;
        }

        public CronJobState getState() {
            return state;
        }

        public void setState(CronJobState state) {
            this.state = state;
        }

        public long getCreatedAtMs() {
            return createdAtMs;
        }

        public void setCreatedAtMs(long createdAtMs) {
            this.createdAtMs = createdAtMs;
        }

        public long getUpdatedAtMs() {
            return updatedAtMs;
        }

        public void setUpdatedAtMs(long updatedAtMs) {
            this.updatedAtMs = updatedAtMs;
        }

        public boolean isDeleteAfterRun() {
            return deleteAfterRun;
        }

        public void setDeleteAfterRun(boolean deleteAfterRun) {
            this.deleteAfterRun = deleteAfterRun;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("enabled", enabled);
            map.put("schedule", schedule != null ? schedule.toMap() : null);
            map.put("payload", payload != null ? payload.toMap() : null);
            map.put("state", state != null ? state.toMap() : null);
            map.put("createdAtMs", createdAtMs);
            map.put("updatedAtMs", updatedAtMs);
            map.put("deleteAfterRun", deleteAfterRun);
            return map;
        }

        @SuppressWarnings("unchecked")
        public static CronJob fromMap(Map<String, Object> map) {
            if (map == null) {
                return null;
            }

            CronJob job = new CronJob();
            job.setId(string(map.get("id")));
            job.setName(string(map.get("name")));
            job.setEnabled(boolDefault(map.get("enabled"), true));

            Object scheduleObj = map.get("schedule");
            if (scheduleObj instanceof Map<?, ?> rawSchedule) {
                job.setSchedule(CronSchedule.fromMap((Map<String, Object>) rawSchedule));
            } else {
                job.setSchedule(new CronSchedule(ScheduleKind.EVERY));
            }

            Object payloadObj = map.get("payload");
            if (payloadObj instanceof Map<?, ?> rawPayload) {
                job.setPayload(CronPayload.fromMap((Map<String, Object>) rawPayload));
            } else {
                job.setPayload(new CronPayload());
            }

            Object stateObj = map.get("state");
            if (stateObj instanceof Map<?, ?> rawState) {
                job.setState(CronJobState.fromMap((Map<String, Object>) rawState));
            } else {
                job.setState(new CronJobState());
            }

            job.setCreatedAtMs(longValue(map.get("createdAtMs")) != null ? longValue(map.get("createdAtMs")) : 0L);
            job.setUpdatedAtMs(longValue(map.get("updatedAtMs")) != null ? longValue(map.get("updatedAtMs")) : 0L);
            job.setDeleteAfterRun(bool(map.get("deleteAfterRun")));

            return job;
        }

        @Override
        public String toString() {
            return "CronJob{" +
                    "id='" + id + '\'' +
                    ", name='" + name + '\'' +
                    ", enabled=" + enabled +
                    ", schedule=" + schedule +
                    ", payload=" + payload +
                    ", state=" + state +
                    ", createdAtMs=" + createdAtMs +
                    ", updatedAtMs=" + updatedAtMs +
                    ", deleteAfterRun=" + deleteAfterRun +
                    '}';
        }
    }

    // =========================================================
    // CronStore
    // =========================================================

    /**
     * 对应 Python: CronStore
     */
    public static class CronStore {
        private int version = 1;
        private List<CronJob> jobs = new ArrayList<>();

        public CronStore() {
        }

        public CronStore(int version, List<CronJob> jobs) {
            this.version = version;
            this.jobs = jobs;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public List<CronJob> getJobs() {
            return jobs;
        }

        public void setJobs(List<CronJob> jobs) {
            this.jobs = jobs;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("version", version);

            List<Map<String, Object>> items = new ArrayList<>();
            for (CronJob job : jobs) {
                items.add(job.toMap());
            }
            map.put("jobs", items);
            return map;
        }

        @SuppressWarnings("unchecked")
        public static CronStore fromMap(Map<String, Object> map) {
            CronStore store = new CronStore();
            if (map == null) {
                return store;
            }

            Number versionNum = number(map.get("version"));
            store.setVersion(versionNum != null ? versionNum.intValue() : 1);

            List<CronJob> jobs = new ArrayList<>();
            Object jobsObj = map.get("jobs");
            if (jobsObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> raw) {
                        CronJob job = CronJob.fromMap((Map<String, Object>) raw);
                        if (job != null) {
                            jobs.add(job);
                        }
                    }
                }
            }

            store.setJobs(jobs);
            return store;
        }

        @Override
        public String toString() {
            return "CronStore{" +
                    "version=" + version +
                    ", jobs=" + jobs +
                    '}';
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : null;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean boolDefault(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return bool(value);
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }
}