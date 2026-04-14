package ricbot.tool.cron;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.infra.cron.CronService;
import ricbot.infra.cron.CronTypes.CronJob;
import ricbot.infra.cron.CronTypes.CronSchedule;
import ricbot.infra.cron.CronTypes.ScheduleKind;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对应 Python: CronTool
 * <p>
 * 主要目标：
 * 1. 暴露 cron 增删改查能力给 agent
 * 2. 支持：
 *    - add: 添加定时任务
 *    - list: 列出所有定时任务
 *    - remove: 删除指定定时任务
 *    - enable: 启用指定定时任务
 *    - disable: 禁用指定定时任务
 *    - run: 立即执行指定定时任务
 *    - status: 获取 cron 服务状态
 */
public class CronTool extends Tool {

    /**
     * Cron 服务实例，用于执行具体的定时任务管理操作
     */
    private final CronService cronService;

    /**
     * 默认时区，用于 cron 表达式类型的任务
     */
    private final String defaultTimezone;

    /**
     * 消息通道，默认为 "cli"
     */
    private String channel = "cli";

    /**
     * 聊天 ID，默认为 "direct"
     */
    private String chatId = "direct";

    /**
     * 构造函数
     *
     * @param cronService    Cron 服务实例
     * @param defaultTimezone 默认时区，如果为 null 则使用 "UTC"
     */
    public CronTool(CronService cronService, String defaultTimezone) {
        this.cronService = cronService;
        this.defaultTimezone = defaultTimezone != null ? defaultTimezone : "UTC";
    }

    @Override
    public String getName() {
        return "cron";
    }

    @Override
    public String getDescription() {
        return "Manage scheduled jobs: add, list, remove, enable, disable, run, status.";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("action", "string", "Action: add, list, remove, enable, disable, run, status", true),
                ToolParam.of("name", "string", "Job name", false),
                ToolParam.of("job_id", "string", "Existing job id", false),
                ToolParam.of("message", "string", "Message/payload for the job", false),
                ToolParam.of("schedule_type", "string", "at / every / cron", false),
                ToolParam.of("at_ms", "integer", "Run at epoch millis", false),
                ToolParam.of("every_ms", "integer", "Interval in milliseconds", false),
                ToolParam.of("cron_expr", "string", "Cron expression", false),
                ToolParam.of("deliver", "boolean", "Whether to deliver result back to chat", false)
        );
    }

    /**
     * 设置上下文信息（渠道和聊天 ID）
     *
     * @param channel 消息通道
     * @param chatId  聊天 ID
     */
    public void setContext(String channel, String chatId) {
        if (channel != null && !channel.isBlank()) {
            this.channel = channel;
        }
        if (chatId != null && !chatId.isBlank()) {
            this.chatId = chatId;
        }
    }

    @Override
    public String execute(Map<String, Object> params) throws Exception {
        String action = (String) params.get("action");
        String name = (String) params.get("name");
        String jobId = (String) params.get("job_id");
        String message = (String) params.get("message");
        String scheduleType = (String) params.get("schedule_type");
        Long atMs = params.get("at_ms") instanceof Number n ? n.longValue() : null;
        Long everyMs = params.get("every_ms") instanceof Number n ? n.longValue() : null;
        String cronExpr = (String) params.get("cron_expr");
        Boolean deliver = (Boolean) params.get("deliver");

        return execute(action, name, jobId, message, scheduleType, atMs, everyMs, cronExpr, deliver);
    }

    /**
     * 执行 cron 相关操作
     *
     * @param action       操作类型：add, list, remove, enable, disable, run, status
     * @param name         任务名称（add 操作时需要）
     * @param jobId        任务 ID（remove, enable, disable, run 操作时需要）
     * @param message      任务消息/负载
     * @param scheduleType 调度类型：at, every, cron
     * @param atMs         指定时间戳（毫秒），用于 at 类型
     * @param everyMs      间隔时间（毫秒），用于 every 类型
     * @param cronExpr     Cron 表达式，用于 cron 类型
     * @param deliver      是否将结果返回到聊天
     * @return 操作结果字符串
     * @throws Exception 执行异常
     */
    public String execute(
            String action,
            String name,
            String jobId,
            String message,
            String scheduleType,
            Long atMs,
            Long everyMs,
            String cronExpr,
            Boolean deliver
    ) throws Exception {
        if (cronService == null) {
            return "Error: cron service is not available.";
        }

        String act = action != null ? action.trim().toLowerCase(Locale.ROOT) : "";

        return switch (act) {
            case "add" -> doAdd(name, message, scheduleType, atMs, everyMs, cronExpr, deliver);
            case "list" -> doList();
            case "remove" -> doRemove(jobId);
            case "enable" -> doEnable(jobId, true);
            case "disable" -> doEnable(jobId, false);
            case "run" -> doRun(jobId);
            case "status" -> doStatus();
            default -> "Error: unknown cron action '" + action + "'";
        };
    }

    /**
     * 添加新的定时任务
     *
     * @param name         任务名称
     * @param message      任务消息
     * @param scheduleType 调度类型
     * @param atMs         指定时间戳
     * @param everyMs      间隔时间
     * @param cronExpr     Cron 表达式
     * @param deliver      是否交付结果
     * @return 操作结果字符串
     */
    private String doAdd(
            String name,
            String message,
            String scheduleType,
            Long atMs,
            Long everyMs,
            String cronExpr,
            Boolean deliver
    ) {
        if (name == null || name.isBlank()) {
            return "Error: name is required for cron add";
        }

        CronSchedule schedule = new CronSchedule();
        String type = scheduleType != null ? scheduleType.trim().toLowerCase(Locale.ROOT) : "every";

        switch (type) {
            case "at" -> {
                schedule.setKind(ScheduleKind.AT);
                schedule.setAtMs(atMs);
            }
            case "every" -> {
                schedule.setKind(ScheduleKind.EVERY);
                schedule.setEveryMs(everyMs != null ? everyMs : 60_000L);
            }
            case "cron" -> {
                schedule.setKind(ScheduleKind.CRON);
                schedule.setExpr(cronExpr);
                schedule.setTz(defaultTimezone);
            }
            default -> {
                return "Error: unsupported schedule_type '" + scheduleType + "'";
            }
        }

        CronJob job = cronService.addJob(
                name,
                schedule,
                message != null ? message : "",
                deliver != null && deliver,
                channel,
                chatId,
                false
        );

        return "Cron job added: " + job.getId() + " (" + job.getName() + ")";
    }

    /**
     * 列出所有定时任务
     *
     * @return 任务列表字符串
     */
    private String doList() {
        List<CronJob> jobs = cronService.listJobs(true);
        if (jobs.isEmpty()) {
            return "No scheduled jobs.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Scheduled jobs:\n");

        for (CronJob job : jobs) {
            sb.append("- ")
                    .append(job.getId())
                    .append(" | ")
                    .append(job.getName())
                    .append(" | enabled=")
                    .append(job.isEnabled())
                    .append(" | next=")
                    .append(job.getState() != null ? job.getState().getNextRunAtMs() : null)
                    .append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * 删除指定的定时任务
     *
     * @param jobId 任务 ID
     * @return 操作结果字符串
     */
    private String doRemove(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return "Error: job_id is required for remove";
        }

        String result = cronService.removeJob(jobId);
        return switch (result) {
            case "removed" -> "Cron job removed: " + jobId;
            case "protected" -> "Error: cron job is protected and cannot be removed";
            default -> "Error: cron job not found";
        };
    }

    /**
     * 启用或禁用指定的定时任务
     *
     * @param jobId   任务 ID
     * @param enabled 是否启用
     * @return 操作结果字符串
     */
    private String doEnable(String jobId, boolean enabled) {
        if (jobId == null || jobId.isBlank()) {
            return "Error: job_id is required";
        }

        CronJob job = cronService.enableJob(jobId, enabled);
        if (job == null) {
            return "Error: cron job not found";
        }

        return (enabled ? "Enabled" : "Disabled") + " cron job: " + jobId;
    }

    /**
     * 立即执行指定的定时任务
     *
     * @param jobId 任务 ID
     * @return 操作结果字符串
     */
    private String doRun(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return "Error: job_id is required for run";
        }

        boolean ok = cronService.runJob(jobId, true);
        return ok ? "Cron job executed: " + jobId : "Error: cron job not found or could not run";
    }

    /**
     * 获取 Cron 服务的当前状态
     *
     * @return 状态信息字符串
     */
    private String doStatus() {
        Map<String, Object> status = cronService.status();
        return "Cron status: enabled=" + status.get("enabled")
                + ", jobs=" + status.get("jobs")
                + ", next_wake_at_ms=" + status.get("next_wake_at_ms");
    }
}