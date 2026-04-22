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

    // 私有构造函数，防止实例化此类，因为只包含静态内部类和工具方法
    private CronTypes() {
    }

    // =========================================================
    // Enums (枚举定义)
    // =========================================================

    /**
     * 调度类型枚举
     * 对应 Python 中的 ScheduleKind
     */
    public enum ScheduleKind {
        AT("at"),       // 一次性调度
        EVERY("every"), // 周期性调度
        CRON("cron");   // Cron 表达式调度

        private final String value; // 枚举对应的字符串值

        // 构造函数，初始化枚举的字符串值
        ScheduleKind(String value) {
            this.value = value;
        }

        // 获取枚举的字符串值
        public String value() {
            return value;
        }

        /**
         * 根据字符串值获取对应的枚举实例
         * @param value 字符串值
         * @return 对应的 ScheduleKind 枚举，如果未匹配或为 null 则返回 null
         */
        public static ScheduleKind fromValue(String value) {
            if (value == null) {
                return null;
            }
            // 遍历所有枚举值进行忽略大小写的匹配
            for (ScheduleKind k : values()) {
                if (k.value.equalsIgnoreCase(value)) {
                    return k;
                }
            }
            return null;
        }
    }

    /**
     * 负载类型枚举
     * 对应 Python 中的 PayloadKind
     */
    public enum PayloadKind {
        SYSTEM_EVENT("system_event"), // 系统事件
        AGENT_TURN("agent_turn");     // Agent 轮次

        private final String value; // 枚举对应的字符串值

        // 构造函数，初始化枚举的字符串值
        PayloadKind(String value) {
            this.value = value;
        }

        // 获取枚举的字符串值
        public String value() {
            return value;
        }

        /**
         * 根据字符串值获取对应的枚举实例
         * @param value 字符串值
         * @return 对应的 PayloadKind 枚举，如果未匹配或为 null 则返回 null
         */
        public static PayloadKind fromValue(String value) {
            if (value == null) {
                return null;
            }
            // 遍历所有枚举值进行忽略大小写的匹配
            for (PayloadKind k : values()) {
                if (k.value.equalsIgnoreCase(value)) {
                    return k;
                }
            }
            return null;
        }
    }

    /**
     * 运行状态枚举
     * 对应 Python 中的 RunStatus
     */
    public enum RunStatus {
        OK("ok"),           // 成功
        ERROR("error"),     // 错误
        SKIPPED("skipped"); // 跳过

        private final String value; // 枚举对应的字符串值

        // 构造函数，初始化枚举的字符串值
        RunStatus(String value) {
            this.value = value;
        }

        // 获取枚举的字符串值
        public String value() {
            return value;
        }

        /**
         * 根据字符串值获取对应的枚举实例
         * @param value 字符串值
         * @return 对应的 RunStatus 枚举，如果未匹配或为 null 则返回 null
         */
        public static RunStatus fromValue(String value) {
            if (value == null) {
                return null; // null 输入返回 null
            }
            // 遍历所有枚举值进行忽略大小写的匹配
            for (RunStatus s : values()) {
                if (s.value.equalsIgnoreCase(value)) {
                    return s;
                }
            }
            return null; // 未匹配到则返回 null
        }
    }

    // =========================================================
    // CronSchedule (Cron 调度配置)
    // =========================================================

    /**
     * 对应 Python: CronSchedule
     * 表示一个调度规则，可以是定点时间、间隔时间或 Cron 表达式
     */
    public static class CronSchedule {
        private ScheduleKind kind; // 调度类型
        private Long atMs;         // 定点执行的时间戳（毫秒）
        private Long everyMs;      // 间隔执行的毫秒数
        private String expr;       // Cron 表达式
        private String tz;         // 时区

        // 默认构造函数，初始化类型为 EVERY
        public CronSchedule() {
            this.kind = ScheduleKind.EVERY;
        }

        // 指定类型的构造函数
        public CronSchedule(ScheduleKind kind) {
            this.kind = kind;
        }

        // 全参构造函数
        public CronSchedule(ScheduleKind kind, Long atMs, Long everyMs, String expr, String tz) {
            this.kind = kind;
            this.atMs = atMs;
            this.everyMs = everyMs;
            this.expr = expr;
            this.tz = tz;
        }

        // Getter 和 Setter 方法
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

        /**
         * 将对象转换为 Map，用于序列化
         * @return 包含当前对象属性的 Map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind != null ? kind.value() : null);
            map.put("atMs", atMs);
            map.put("everyMs", everyMs);
            map.put("expr", expr);
            map.put("tz", tz);
            return map;
        }

        /**
         * 从 Map 中构建 CronSchedule 对象，用于反序列化
         * 支持 snake_case 和 camelCase 键名
         * @param map 源数据 Map
         * @return 构建好的 CronSchedule 对象
         */
        @SuppressWarnings("unchecked")
        public static CronSchedule fromMap(Map<String, Object> map) {
            if (map == null) {
                return new CronSchedule(ScheduleKind.EVERY);
            }

            CronSchedule s = new CronSchedule();
            // 解析 kind，使用辅助方法 string 处理 null
            String kindRaw = string(map.get("kind"));
            ScheduleKind parsedKind = ScheduleKind.fromValue(kindRaw);
            if (parsedKind == null) {
                if (kindRaw != null) {
                    throw new IllegalArgumentException("unknown schedule kind '" + kindRaw + "'");
                }
                parsedKind = ScheduleKind.EVERY;
            }
            s.setKind(parsedKind);
            // 解析 atMs，兼容 "at_ms" 和 "atMs"
            s.setAtMs(longValue(map.get("at_ms") != null ? map.get("at_ms") : map.get("atMs")));
            // 解析 everyMs，兼容 "every_ms" 和 "everyMs"
            s.setEveryMs(longValue(map.get("every_ms") != null ? map.get("every_ms") : map.get("everyMs")));
            s.setExpr(string(map.get("expr")));
            s.setTz(string(map.get("tz")));
            return s;
        }

        public void validateForAdd(long nowMs) {
            if (kind == null) {
                throw new IllegalArgumentException("schedule.kind is required");
            }
            if (tz != null && !tz.isBlank() && kind != ScheduleKind.CRON) {
                throw new IllegalArgumentException("tz can only be used with cron schedules");
            }
            if (kind == ScheduleKind.AT) {
                if (atMs == null) {
                    throw new IllegalArgumentException("atMs is required for AT schedules");
                }
                if (atMs <= nowMs) {
                    throw new IllegalArgumentException("atMs must be in the future");
                }
            }
            if (kind == ScheduleKind.EVERY) {
                if (everyMs == null || everyMs <= 0) {
                    throw new IllegalArgumentException("everyMs must be > 0 for EVERY schedules");
                }
            }
            if (kind == ScheduleKind.CRON) {
                if (expr == null || expr.isBlank()) {
                    throw new IllegalArgumentException("expr is required for CRON schedules");
                }
            }
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
    // CronPayload (Cron 任务负载)
    // =========================================================

    /**
     * 对应 Python: CronPayload
     * 表示 Cron 触发时携带的数据负载
     */
    public static class CronPayload {
        private PayloadKind kind = PayloadKind.AGENT_TURN; // 负载类型，默认为 AGENT_TURN
        private String message = "";                       // 消息内容，默认为空字符串
        private boolean deliver = false;                   // 是否发送消息
        private String channel;                            // 发送渠道
        private String to;                                 // 接收者

        // 默认构造函数
        public CronPayload() {
        }

        // Getter 和 Setter 方法
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

        /**
         * 将对象转换为 Map，用于序列化
         * @return 包含当前对象属性的 Map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind != null ? kind.value() : null);
            map.put("message", message);
            map.put("deliver", deliver);
            map.put("channel", channel);
            map.put("to", to);
            return map;
        }

        /**
         * 从 Map 中构建 CronPayload 对象，用于反序列化
         * @param map 源数据 Map
         * @return 构建好的 CronPayload 对象
         */
        public static CronPayload fromMap(Map<String, Object> map) {
            CronPayload p = new CronPayload();
            if (map == null) {
                return p;
            }
            String kindRaw = string(map.get("kind"));
            PayloadKind parsedKind = PayloadKind.fromValue(kindRaw);
            if (parsedKind == null) {
                if (kindRaw != null) {
                    throw new IllegalArgumentException("unknown payload kind '" + kindRaw + "'");
                }
                parsedKind = PayloadKind.AGENT_TURN;
            }
            p.setKind(parsedKind);
            // 确保 message 不为 null，若为 null 则设为空字符串
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
    // CronRunRecord (Cron 运行记录)
    // =========================================================

    /**
     * 对应 Python: CronRunRecord
     * 记录一次 Cron 任务的执行情况
     */
    public static class CronRunRecord {
        private long runAtMs;      // 执行时间戳（毫秒）
        private RunStatus status;  // 执行状态
        private long durationMs = 0; // 执行耗时（毫秒）
        private String error;      // 错误信息（如果有）

        // 默认构造函数
        public CronRunRecord() {
        }

        // 全参构造函数
        public CronRunRecord(long runAtMs, RunStatus status, long durationMs, String error) {
            this.runAtMs = runAtMs;
            this.status = status;
            this.durationMs = durationMs;
            this.error = error;
        }

        // Getter 和 Setter 方法
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

        /**
         * 将对象转换为 Map，用于序列化
         * @return 包含当前对象属性的 Map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("runAtMs", runAtMs);
            map.put("status", status != null ? status.value() : null);
            map.put("durationMs", durationMs);
            map.put("error", error);
            return map;
        }

        /**
         * 从 Map 中构建 CronRunRecord 对象，用于反序列化
         * 支持 snake_case 和 camelCase 键名
         * @param map 源数据 Map
         * @return 构建好的 CronRunRecord 对象，如果 map 为 null 则返回 null
         */
        public static CronRunRecord fromMap(Map<String, Object> map) {
            if (map == null) {
                return null;
            }
            // 解析 runAtMs，如果解析结果为 null 则默认为 0L
            long runAt = longValue(map.get("run_at_ms") != null ? map.get("run_at_ms") : map.get("runAtMs")) != null 
                    ? longValue(map.get("run_at_ms") != null ? map.get("run_at_ms") : map.get("runAtMs")) 
                    : 0L;
            
            // 解析 status
            RunStatus status = RunStatus.fromValue(string(map.get("status")));
            
            // 解析 durationMs，如果解析结果为 null 则默认为 0L
            long duration = longValue(map.get("duration_ms") != null ? map.get("duration_ms") : map.get("durationMs")) != null 
                    ? longValue(map.get("duration_ms") != null ? map.get("duration_ms") : map.get("durationMs")) 
                    : 0L;
            
            // 解析 error
            String error = string(map.get("error"));

            return new CronRunRecord(runAt, status, duration, error);
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
    // CronJobState (Cron 任务状态)
    // =========================================================

    /**
     * 对应 Python: CronJobState
     * 维护 Cron 任务的运行时状态和历史记录
     */
    public static class CronJobState {
        private Long nextRunAtMs;              // 下次执行时间戳
        private Long lastRunAtMs;              // 上次执行时间戳
        private RunStatus lastStatus;          // 上次执行状态
        private String lastError;              // 上次执行错误信息
        private List<CronRunRecord> runHistory = new ArrayList<>(); // 执行历史记录列表

        // 默认构造函数
        public CronJobState() {
        }

        // Getter 和 Setter 方法
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
            return List.copyOf(runHistory);
        }

        public void setRunHistory(List<CronRunRecord> runHistory) {
            this.runHistory = runHistory != null ? new ArrayList<>(runHistory) : new ArrayList<>();
        }

        public void addRunRecord(CronRunRecord record) {
            if (record == null) {
                return;
            }
            runHistory.add(record);
        }

        public void trimRunHistory(int max) {
            if (max <= 0) {
                runHistory = new ArrayList<>();
                return;
            }
            if (runHistory.size() <= max) {
                return;
            }
            runHistory = new ArrayList<>(runHistory.subList(runHistory.size() - max, runHistory.size()));
        }

        /**
         * 将对象转换为 Map，用于序列化
         * @return 包含当前对象属性的 Map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("nextRunAtMs", nextRunAtMs);
            map.put("lastRunAtMs", lastRunAtMs);
            map.put("lastStatus", lastStatus != null ? lastStatus.value() : null);
            map.put("lastError", lastError);

            // 转换历史记录列表
            List<Map<String, Object>> history = new ArrayList<>();
            for (CronRunRecord record : runHistory) {
                history.add(record.toMap());
            }
            map.put("runHistory", history);
            return map;
        }

        /**
         * 从 Map 中构建 CronJobState 对象，用于反序列化
         * 支持 snake_case 和 camelCase 键名
         * @param map 源数据 Map
         * @return 构建好的 CronJobState 对象
         */
        @SuppressWarnings("unchecked")
        public static CronJobState fromMap(Map<String, Object> map) {
            CronJobState s = new CronJobState();
            if (map == null) {
                return s;
            }

            // 解析基本字段，兼容不同命名风格
            s.setNextRunAtMs(longValue(map.get("next_run_at_ms") != null ? map.get("next_run_at_ms") : map.get("nextRunAtMs")));
            s.setLastRunAtMs(longValue(map.get("last_run_at_ms") != null ? map.get("last_run_at_ms") : map.get("lastRunAtMs")));
            s.setLastStatus(RunStatus.fromValue(string(map.get("last_status") != null ? map.get("last_status") : map.get("lastStatus"))));
            s.setLastError(string(map.get("last_error") != null ? map.get("last_error") : map.get("lastError")));

            // 解析历史记录列表
            Object historyObj = map.get("run_history") != null ? map.get("run_history") : map.get("runHistory");
            List<CronRunRecord> history = new ArrayList<>();
            if (historyObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> raw) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> recMap = (Map<String, Object>) raw;
                        // 递归构建 CronRunRecord
                        CronRunRecord rec = CronRunRecord.fromMap(recMap);
                        if (rec != null) {
                            history.add(rec);
                        }
                    } else if (item instanceof CronRunRecord rec) {
                        // 如果已经是对象类型，直接添加
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
    // CronJob (Cron 任务定义)
    // =========================================================

    /**
     * 对应 Python: CronJob
     * 表示一个完整的 Cron 任务，包含调度、负载和状态
     */
    public static class CronJob {
        private String id;                      // 任务 ID
        private String name;                    // 任务名称
        private boolean enabled = true;         // 是否启用，默认 true
        private CronSchedule schedule = new CronSchedule(ScheduleKind.EVERY); // 调度配置，默认 EVERY
        private CronPayload payload = new CronPayload(); // 负载配置，默认空负载
        private CronJobState state = new CronJobState(); // 任务状态，默认初始状态
        private long createdAtMs = 0;           // 创建时间戳
        private long updatedAtMs = 0;           // 更新时间戳
        private boolean deleteAfterRun = false; // 执行后是否删除

        // 默认构造函数
        public CronJob() {
        }

        // Getter 和 Setter 方法
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

        /**
         * 将对象转换为 Map，用于序列化
         * @return 包含当前对象属性的 Map
         */
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

        /**
         * 从 Map 中构建 CronJob 对象，用于反序列化
         * 支持 snake_case 和 camelCase 键名
         * @param map 源数据 Map
         * @return 构建好的 CronJob 对象，如果 map 为 null 则返回 null
         */
        @SuppressWarnings("unchecked")
        public static CronJob fromMap(Map<String, Object> map) {
            if (map == null) {
                return null;
            }

            CronJob job = new CronJob();
            job.setId(string(map.get("id")));
            job.setName(string(map.get("name")));
            // 解析 enabled，默认值为 true
            job.setEnabled(boolDefault(map.get("enabled"), true));

            // 解析 schedule 嵌套对象
            Object scheduleObj = map.get("schedule");
            if (scheduleObj instanceof Map<?, ?> rawSchedule) {
                job.setSchedule(CronSchedule.fromMap((Map<String, Object>) rawSchedule));
            } else {
                // 如果缺失或格式不对，使用默认值
                job.setSchedule(new CronSchedule(ScheduleKind.EVERY));
            }

            // 解析 payload 嵌套对象
            Object payloadObj = map.get("payload");
            if (payloadObj instanceof Map<?, ?> rawPayload) {
                job.setPayload(CronPayload.fromMap((Map<String, Object>) rawPayload));
            } else {
                // 如果缺失或格式不对，使用默认值
                job.setPayload(new CronPayload());
            }

            // 解析 state 嵌套对象
            Object stateObj = map.get("state");
            if (stateObj instanceof Map<?, ?> rawState) {
                job.setState(CronJobState.fromMap((Map<String, Object>) rawState));
            } else {
                // 如果缺失或格式不对，使用默认值
                job.setState(new CronJobState());
            }

            // 解析时间戳，兼容 snake_case 和 camelCase，null 时默认为 0L
            job.setCreatedAtMs(longValue(map.get("created_at_ms") != null ? map.get("created_at_ms") : map.get("createdAtMs")) != null 
                    ? longValue(map.get("created_at_ms") != null ? map.get("created_at_ms") : map.get("createdAtMs")) 
                    : 0L);
            job.setUpdatedAtMs(longValue(map.get("updated_at_ms") != null ? map.get("updated_at_ms") : map.get("updatedAtMs")) != null 
                    ? longValue(map.get("updated_at_ms") != null ? map.get("updated_at_ms") : map.get("updatedAtMs")) 
                    : 0L);
            
            // 解析 deleteAfterRun，兼容不同命名风格
            job.setDeleteAfterRun(bool(map.get("delete_after_run") != null ? map.get("delete_after_run") : map.get("deleteAfterRun")));

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
    // CronStore (Cron 存储结构)
    // =========================================================

    /**
     * 对应 Python: CronStore
     * 表示所有 Cron 任务的集合存储
     */
    public static class CronStore {
        private int version = 1;             // 存储版本号，默认为 1
        private List<CronJob> jobs = new ArrayList<>(); // 任务列表

        // 全参构造函数
        public CronStore(int version, List<CronJob> jobs) {
            this.version = version;
            this.jobs = jobs;
        }

        // Getter 和 Setter 方法
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

        /**
         * 将对象转换为 Map，用于序列化
         * @return 包含当前对象属性的 Map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("version", version);

            // 转换任务列表
            List<Map<String, Object>> items = new ArrayList<>();
            for (CronJob job : jobs) {
                items.add(job.toMap());
            }
            map.put("jobs", items);
            return map;
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
    // Helpers (辅助工具方法)
    // =========================================================

    /**
     * 安全地将对象转换为字符串
     * @param value 输入对象
     * @return 字符串表示，如果为 null 则返回 null
     */
    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 安全地将对象转换为 Long
     * @param value 输入对象
     * @return Long 值，如果无法转换或为 null 则返回 null
     */
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

    /**
     * 安全地将对象转换为 Number
     * @param value 输入对象
     * @return Number 对象，如果不是 Number 类型则返回 null
     */
    private static Number number(Object value) {
        return value instanceof Number n ? n : null;
    }

    /**
     * 安全地将对象转换为 boolean
     * @param value 输入对象
     * @return boolean 值，如果为 null 则返回 false
     */
    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 安全地将对象转换为 boolean，支持默认值
     * @param value 输入对象
     * @param defaultValue 默认值
     * @return boolean 值，如果为 null 则返回 defaultValue
     */
    private static boolean boolDefault(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return bool(value);
    }

    /**
     * 确保字符串不为 null
     * @param value 输入字符串
     * @return 原字符串，如果为 null 则返回空字符串 ""
     */
    private static String defaultString(String value) {
        return value == null ? "" : value;
    }
}
