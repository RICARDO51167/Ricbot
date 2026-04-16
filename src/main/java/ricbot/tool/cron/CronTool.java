package ricbot.tool.cron; // 定义当前类所在的包路径

import ricbot.tool.api.Tool; // 导入 Tool 基类
import ricbot.tool.api.ToolParam; // 导入工具参数定义类
import ricbot.infra.cron.CronService; // 导入 Cron 服务接口
import ricbot.infra.cron.CronTypes.CronJob; // 导入 Cron 任务实体类
import ricbot.infra.cron.CronTypes.CronSchedule; // 导入 Cron 调度配置类
import ricbot.infra.cron.CronTypes.ScheduleKind; // 导入调度类型枚举

import java.util.List; // 导入 List 集合接口
import java.util.Locale; // 导入 Locale 用于本地化操作（如大小写转换）
import java.util.Map; // 导入 Map 集合接口

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
public class CronTool extends Tool { // 定义 CronTool 类，继承自 Tool 基类

    /**
     * Cron 服务实例，用于执行具体的定时任务管理操作
     */
    private final CronService cronService; // 声明私有的最终 CronService 成员变量

    /**
     * 默认时区，用于 cron 表达式类型的任务
     */
    private final String defaultTimezone; // 声明私有的最终默认时区字符串成员变量

    /**
     * 消息通道，默认为 "cli"
     */
    private String channel = "cli"; // 声明私有消息通道成员变量，默认值为 "cli"

    /**
     * 聊天 ID，默认为 "direct"
     */
    private String chatId = "direct"; // 声明私有聊天 ID 成员变量，默认值为 "direct"

    /**
     * 构造函数
     *
     * @param cronService    Cron 服务实例
     * @param defaultTimezone 默认时区，如果为 null 则使用 "UTC"
     */
    public CronTool(CronService cronService, String defaultTimezone) { // 定义构造函数，接收 CronService 和默认时区
        this.cronService = cronService; // 初始化 cronService 成员变量
        this.defaultTimezone = defaultTimezone != null ? defaultTimezone : "UTC"; // 初始化 defaultTimezone，若传入 null 则默认为 "UTC"
    }

    @Override // 标记重写父类方法
    public String getName() { // 获取工具名称的方法
        return "cron"; // 返回工具名称 "cron"
    }

    @Override // 标记重写父类方法
    public String getDescription() { // 获取工具描述的方法
        return "管理定时任务：add、list、remove、enable、disable、run、status。"; // 返回工具的中文描述
    }

    @Override // 标记重写父类方法
    public List<ToolParam> getParams() { // 获取工具参数列表的方法
        return List.of( // 返回不可变的参数列表
                ToolParam.of("action", "string", "操作：add、list、remove、enable、disable、run、status", true), // 定义 action 参数，字符串类型，必填
                ToolParam.of("name", "string", "任务名称", false), // 定义 name 参数，字符串类型，选填
                ToolParam.of("job_id", "string", "已有任务 ID", false), // 定义 job_id 参数，字符串类型，选填
                ToolParam.of("message", "string", "任务消息/负载", false), // 定义 message 参数，字符串类型，选填
                ToolParam.of("schedule_type", "string", "at / every / cron", false), // 定义 schedule_type 参数，字符串类型，选填
                ToolParam.of("at_ms", "integer", "按 epoch 毫秒时间戳执行", false), // 定义 at_ms 参数，整数类型，选填
                ToolParam.of("every_ms", "integer", "间隔毫秒数", false), // 定义 every_ms 参数，整数类型，选填
                ToolParam.of("cron_expr", "string", "Cron 表达式", false), // 定义 cron_expr 参数，字符串类型，选填
                ToolParam.of("deliver", "boolean", "是否将结果回传到聊天", false) // 定义 deliver 参数，布尔类型，选填
        );
    }

    /**
     * 设置上下文信息（渠道和聊天 ID）
     *
     * @param channel 消息通道
     * @param chatId  聊天 ID
     */
    public void setContext(String channel, String chatId) { // 设置上下文的方法，接收 channel 和 chatId
        if (channel != null && !channel.isBlank()) { // 检查 channel 是否非空且非空白
            this.channel = channel; // 更新 channel 成员变量
        }
        if (chatId != null && !chatId.isBlank()) { // 检查 chatId 是否非空且非空白
            this.chatId = chatId; // 更新 chatId 成员变量
        }
    }

    @Override // 标记重写父类方法
    public String execute(Map<String, Object> params) throws Exception { // 执行工具的主入口方法，接收参数 Map
        String action = (String) params.get("action"); // 从参数中获取 action
        String name = (String) params.get("name"); // 从参数中获取 name
        String jobId = (String) params.get("job_id"); // 从参数中获取 job_id
        String message = (String) params.get("message"); // 从参数中获取 message
        String scheduleType = (String) params.get("schedule_type"); // 从参数中获取 schedule_type
        Long atMs = params.get("at_ms") instanceof Number n ? n.longValue() : null; // 从参数中获取 at_ms 并转换为 Long，若非数字则为 null
        Long everyMs = params.get("every_ms") instanceof Number n ? n.longValue() : null; // 从参数中获取 every_ms 并转换为 Long，若非数字则为 null
        String cronExpr = (String) params.get("cron_expr"); // 从参数中获取 cron_expr
        Boolean deliver = (Boolean) params.get("deliver"); // 从参数中获取 deliver

        return execute(action, name, jobId, message, scheduleType, atMs, everyMs, cronExpr, deliver); // 调用重载的 execute 方法执行具体逻辑
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
    public String execute( // 重载的 execute 方法，接收具体参数
            String action, // 操作类型
            String name, // 任务名称
            String jobId, // 任务 ID
            String message, // 任务消息
            String scheduleType, // 调度类型
            Long atMs, // 指定时间戳
            Long everyMs, // 间隔时间
            String cronExpr, // Cron 表达式
            Boolean deliver // 是否交付结果
    ) throws Exception { // 声明可能抛出异常
        if (cronService == null) { // 检查 cronService 是否为 null
            return "错误：cron 服务不可用。"; // 若不可用，返回错误信息
        }

        String act = action != null ? action.trim().toLowerCase(Locale.ROOT) : ""; // 将 action 转换为小写并去除首尾空格，若为 null 则为空串

        return switch (act) { // 根据 action 进行模式匹配
            case "add" -> doAdd(name, message, scheduleType, atMs, everyMs, cronExpr, deliver); // 执行添加任务
            case "list" -> doList(); // 执行列出任务
            case "remove" -> doRemove(jobId); // 执行删除任务
            case "enable" -> doEnable(jobId, true); // 执行启用任务
            case "disable" -> doEnable(jobId, false); // 执行禁用任务
            case "run" -> doRun(jobId); // 执行立即运行任务
            case "status" -> doStatus(); // 执行获取状态
            default -> "错误：未知的 cron action：'" + action + "'"; // 未知操作返回错误
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
    private String doAdd( // 私有方法：添加任务
            String name, // 任务名称
            String message, // 任务消息
            String scheduleType, // 调度类型
            Long atMs, // 指定时间戳
            Long everyMs, // 间隔时间
            String cronExpr, // Cron 表达式
            Boolean deliver // 是否交付结果
    ) {
        if (name == null || name.isBlank()) { // 检查 name 是否为空或空白
            return "错误：cron add 需要提供 name"; // 若无效，返回错误信息
        }

        CronSchedule schedule = new CronSchedule(); // 创建新的 CronSchedule 对象
        String type = scheduleType != null ? scheduleType.trim().toLowerCase(Locale.ROOT) : "every"; // 确定调度类型，默认为 "every"

        switch (type) { // 根据调度类型进行匹配
            case "at" -> { // 一次性执行
                schedule.setKind(ScheduleKind.AT); // 设置调度类型为 AT
                schedule.setAtMs(atMs); // 设置执行时间戳
            }
            case "every" -> { // 周期性执行
                schedule.setKind(ScheduleKind.EVERY); // 设置调度类型为 EVERY
                schedule.setEveryMs(everyMs != null ? everyMs : 60_000L); // 设置间隔时间，默认为 60 秒
            }
            case "cron" -> { // Cron 表达式执行
                schedule.setKind(ScheduleKind.CRON); // 设置调度类型为 CRON
                schedule.setExpr(cronExpr); // 设置 Cron 表达式
                schedule.setTz(defaultTimezone); // 设置时区
            }
            default -> { // 不支持的类型
                return "错误：不支持的 schedule_type：'" + scheduleType + "'"; // 返回错误信息
            }
        }

        CronJob job = cronService.addJob( // 调用服务添加任务
                name, // 任务名称
                schedule, // 调度配置
                message != null ? message : "", // 任务消息，默认为空串
                deliver != null && deliver, // 是否交付结果
                channel, // 消息通道
                chatId, // 聊天 ID
                false // 是否受保护（此处固定为 false）
        );

        return "已添加 cron 任务：" + job.getId() + "（" + job.getName() + "）"; // 返回成功信息，包含任务 ID 和名称
    }

    /**
     * 列出所有定时任务
     *
     * @return 任务列表字符串
     */
    private String doList() { // 私有方法：列出任务
        List<CronJob> jobs = cronService.listJobs(true); // 获取所有任务列表
        if (jobs.isEmpty()) { // 检查列表是否为空
            return "当前没有定时任务。"; // 若为空，返回提示信息
        }

        StringBuilder sb = new StringBuilder(); // 创建 StringBuilder 用于构建结果字符串
        sb.append("定时任务列表：\n"); // 添加标题

        for (CronJob job : jobs) { // 遍历每个任务
            sb.append("- ") // 添加列表项前缀
                    .append(job.getId()) // 添加任务 ID
                    .append(" | ") // 添加分隔符
                    .append(job.getName()) // 添加任务名称
                    .append(" | 启用=") // 添加启用状态标签
                    .append(job.isEnabled()) // 添加启用状态值
                    .append(" | 下次执行=") // 添加下次执行时间标签
                    .append(job.getState() != null ? job.getState().getNextRunAtMs() : null) // 添加下次执行时间戳，若状态为空则为 null
                    .append("\n"); // 添加换行符
        }

        return sb.toString().trim(); // 返回构建好的字符串并去除首尾空白
    }

    /**
     * 删除指定的定时任务
     *
     * @param jobId 任务 ID
     * @return 操作结果字符串
     */
    private String doRemove(String jobId) { // 私有方法：删除任务
        if (jobId == null || jobId.isBlank()) { // 检查 jobId 是否为空或空白
            return "错误：remove 需要提供 job_id"; // 若无效，返回错误信息
        }

        String result = cronService.removeJob(jobId); // 调用服务删除任务
        return switch (result) { // 根据删除结果进行匹配
            case "removed" -> "已删除 cron 任务：" + jobId; // 成功删除
            case "protected" -> "错误：该 cron 任务受保护，无法删除"; // 任务受保护
            default -> "错误：未找到 cron 任务"; // 未找到任务或其他错误
        };
    }

    /**
     * 启用或禁用指定的定时任务
     *
     * @param jobId   任务 ID
     * @param enabled 是否启用
     * @return 操作结果字符串
     */
    private String doEnable(String jobId, boolean enabled) { // 私有方法：启用或禁用任务
        if (jobId == null || jobId.isBlank()) { // 检查 jobId 是否为空或空白
            return "错误：必须提供 job_id"; // 若无效，返回错误信息
        }

        CronJob job = cronService.enableJob(jobId, enabled); // 调用服务启用或禁用任务
        if (job == null) { // 检查返回的任务对象是否为 null
            return "错误：未找到 cron 任务"; // 若为 null，返回错误信息
        }

        return (enabled ? "已启用" : "已禁用") + " cron 任务：" + jobId; // 返回操作结果信息
    }

    /**
     * 立即执行指定的定时任务
     *
     * @param jobId 任务 ID
     * @return 操作结果字符串
     */
    private String doRun(String jobId) { // 私有方法：立即执行任务
        if (jobId == null || jobId.isBlank()) { // 检查 jobId 是否为空或空白
            return "错误：run 需要提供 job_id"; // 若无效，返回错误信息
        }

        boolean ok = cronService.runJob(jobId, true); // 调用服务立即执行任务
        return ok ? "已执行 cron 任务：" + jobId : "错误：未找到 cron 任务或无法执行"; // 根据执行结果返回相应信息
    }

    /**
     * 获取 Cron 服务的当前状态
     *
     * @return 状态信息字符串
     */
    private String doStatus() { // 私有方法：获取状态
        Map<String, Object> status = cronService.status(); // 调用服务获取状态 Map
        return "Cron 状态：启用=" + status.get("enabled") // 构建状态字符串：启用状态
                + "，任务数=" + status.get("jobs") // 添加：任务数量
                + "，下次唤醒时间(ms)=" + status.get("next_wake_at_ms"); // 添加：下次唤醒时间
    }
}
