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
 * Cron 定时任务管理工具
 */
public class CronTool extends Tool {

    private final CronService cronService;
    private final String defaultTimezone;
    private String channel = "cli";
    private String chatId = "direct";

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
        return "管理定时任务：add、list、remove、enable、disable、run、status。";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("action", "string", "操作：add、list、remove、enable、disable、run、status", true),
                ToolParam.of("name", "string", "任务名称", false),
                ToolParam.of("job_id", "string", "已有任务 ID", false),
                ToolParam.of("message", "string", "任务消息/负载", false),
                ToolParam.of("schedule_type", "string", "at / every / cron", false),
                ToolParam.of("at_ms", "integer", "按 epoch 毫秒时间戳执行", false),
                ToolParam.of("every_ms", "integer", "间隔毫秒数", false),
                ToolParam.of("cron_expr", "string", "Cron 表达式", false),
                ToolParam.of("deliver", "boolean", "是否将结果回传到聊天", false)
        );
    }

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
            return "错误：cron 服务不可用。";
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
            default -> "错误：未知的 cron action：'" + action + "'";
        };
    }

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
            return "错误：cron add 需要提供 name";
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
                return "错误：不支持的 schedule_type：'" + scheduleType + "'";
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

        return "已添加 cron 任务：" + job.getId() + "（" + job.getName() + "）";
    }

    private String doList() {
        List<CronJob> jobs = cronService.listJobs(true);
        if (jobs.isEmpty()) {
            return "当前没有定时任务。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("定时任务列表：\n");

        for (CronJob job : jobs) {
            sb.append("- ")
                    .append(job.getId())
                    .append(" | ")
                    .append(job.getName())
                    .append(" | 启用=")
                    .append(job.isEnabled())
                    .append(" | 下次执行=")
                    .append(job.getState() != null ? job.getState().getNextRunAtMs() : null)
                    .append("\n");
        }

        return sb.toString().trim();
    }

    private String doRemove(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return "错误：remove 需要提供 job_id";
        }

        String result = cronService.removeJob(jobId);
        return switch (result) {
            case "removed" -> "已删除 cron 任务：" + jobId;
            case "protected" -> "错误：该 cron 任务受保护，无法删除";
            default -> "错误：未找到 cron 任务";
        };
    }

    private String doEnable(String jobId, boolean enabled) {
        if (jobId == null || jobId.isBlank()) {
            return "错误：必须提供 job_id";
        }

        CronJob job = cronService.enableJob(jobId, enabled);
        if (job == null) {
            return "错误：未找到 cron 任务";
        }

        return (enabled ? "已启用" : "已禁用") + " cron 任务：" + jobId;
    }

    private String doRun(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return "错误：run 需要提供 job_id";
        }

        boolean ok = cronService.runJob(jobId, true);
        return ok ? "已执行 cron 任务：" + jobId : "错误：未找到 cron 任务或无法执行";
    }

    private String doStatus() {
        Map<String, Object> status = cronService.status();
        return "Cron 状态：启用=" + status.get("enabled")
                + "，任务数=" + status.get("jobs")
                + "，下次唤醒时间(ms)=" + status.get("next_wake_at_ms");
    }
}
