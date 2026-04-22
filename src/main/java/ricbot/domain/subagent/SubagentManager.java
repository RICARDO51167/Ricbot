package ricbot.domain.subagent;

// 导入代理运行结果类
import ricbot.domain.agent.AgentRunResult;
// 导入代理运行规范类
import ricbot.domain.agent.AgentRunSpec;
// 导入代理运行器类
import ricbot.domain.agent.AgentRunner;
import ricbot.domain.agent.ContextBuilder;
// 导入代理钩子基类
import ricbot.domain.hook.AgentHook;
// 导入代理钩子上下文类
import ricbot.domain.hook.AgentHookContext;
import ricbot.domain.skill.SkillsLoader;
// 导入工具注册表类
import ricbot.tool.api.ToolRegistry;
// 导入文件编辑工具类
import ricbot.tool.filesystem.EditFileTool;
// 导入目录列表工具类
import ricbot.tool.filesystem.ListDirTool;
// 导入文件读取工具类
import ricbot.tool.filesystem.ReadFileTool;
// 导入文件写入工具类
import ricbot.tool.filesystem.WriteFileTool;
// 导入命令执行工具类
import ricbot.tool.process.ExecTool;
// 导入全局匹配搜索工具类
import ricbot.tool.search.GlobTool;
// 导入内容搜索工具类
import ricbot.tool.search.GrepTool;
import ricbot.tool.web.WebFetchTool;
import ricbot.tool.web.WebSearchTool;
// 导入入站消息类
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.InboundMessages;
// 导入消息总线类
import ricbot.domain.message.MessageBus;
// 导入配置类
import ricbot.infra.config.Config;
// 导入提示词模板渲染类
import ricbot.infra.template.PromptTemplates;
// 导入大语言模型提供者接口
import ricbot.integration.llm.api.LLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 导入文件路径类
import java.nio.file.Path;
// 导入常用集合类
import java.util.*;
// 导入并发工具类
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子代理管理器。
 *
 * 作用：
 * 1. 启动后台子代理任务
 * 2. 跟踪正在运行的子代理
 * 3. 在任务完成后把结果回传给主 Agent
 * 4. 支持按 session 取消子代理
 *
 * 对应 Python 中的 SubagentManager。
 */
public class SubagentManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SubagentManager.class);
    private static final int SUBAGENT_ID_LENGTH = 8;
    private static final int SUBAGENT_MAX_ITERATIONS = 15;
    private static final int DEFAULT_LABEL_MAX_LENGTH = 30;
    private static final String DEFAULT_ORIGIN_CHANNEL = "cli";
    private static final String DEFAULT_ORIGIN_CHAT_ID = "direct";
    private static final String DEFAULT_MAX_ITERATIONS_MESSAGE = "任务已结束，但未生成最终回复。";
    private static final String DEFAULT_ERROR_MESSAGE = "错误：子代理执行失败。";
    private static final String CANCELLED_MESSAGE = "任务已取消。";

    /**
     * 子代理执行时的简单日志 Hook。
     *
     * 只负责在工具执行前打印日志，方便排查。
     */
    static class SubagentHook extends AgentHook {
        private final Logger log;

        /**
         * 构造函数，初始化任务ID
         * @param taskId 任务唯一标识
         */
        public SubagentHook(String taskId) {
            this.log = LoggerFactory.getLogger("subagent." + taskId);
        }

        /**
         * 在工具执行前调用的钩子方法
         * @param context 代理钩子上下文，包含工具调用信息
         */
        @Override
        public void beforeExecuteTools(AgentHookContext context) {
            // 如果没有工具调用，直接返回
            if (context.getToolCalls() == null) {
                return;
            }

            // 遍历所有待执行的工具调用
            for (var toolCall : context.getToolCalls()) {
                log.debug("tool: name={}, args={}", toolCall.getName(), toolCall.getArguments());
            }
        }
    }

    // 大语言模型提供者
    // 工作空间路径
    private final Path workspace;
    // 消息总线，用于通信
    private final MessageBus bus;
    // 使用的模型名称
    private final String model;
    // 工具结果最大字符数限制
    private final int maxToolResultChars;
    // 执行工具配置
    private final Config.ExecToolConfig execConfig;
    // 是否限制在工作空间内
    private final boolean restrictToWorkspace;
    private final Config.WebToolsConfig webConfig;
    private final SkillsLoader skillsLoader;
    // 代理运行器实例
    private final AgentRunner runner;

    /**
     * 存储正在运行的任务：taskId -> Future
     * 使用 ConcurrentHashMap 保证线程安全
     */
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    /**
     * 存储会话与任务的关联：sessionKey -> taskId set
     * 用于按会话取消任务
     */
    private final Map<String, Set<String>> sessionTasks = new ConcurrentHashMap<>();

    /**
     * 用线程池模拟后台执行。
     * Python 里是 asyncio.create_task，这里用 ExecutorService。
     * 使用缓存线程池，根据需要创建新线程
     */
    private final ThreadPoolExecutor executor;

    /**
     * 构造函数，初始化子代理管理器
     *
     * @param provider 大语言模型提供者
     * @param workspace 工作空间路径
     * @param bus 消息总线
     * @param maxToolResultChars 工具结果最大字符数
     * @param model 模型名称，如果为null则使用默认模型
     * @param webConfig Web工具配置（当前未使用）
     * @param execConfig 执行工具配置
     * @param restrictToWorkspace 是否限制在工作空间内
     * @param disabledSkills 禁用的技能列表
     */
    public SubagentManager(
            LLMProvider provider,
            Path workspace,
            MessageBus bus,
            int maxToolResultChars,
            String model,
            Config.WebToolsConfig webConfig,
            Config.ExecToolConfig execConfig,
            boolean restrictToWorkspace,
            List<String> disabledSkills
    ) {
        // 初始化工作空间路径
        this.workspace = workspace;
        // 初始化消息总线
        this.bus = bus;
        // 初始化模型名称，如果传入为null则使用提供者默认模型
        this.model = model != null ? model : provider.getDefaultModel();
        // 初始化最大工具结果字符数
        this.maxToolResultChars = maxToolResultChars;
        this.webConfig = webConfig != null ? webConfig : new Config.WebToolsConfig();
        // 初始化执行配置，如果传入为null则创建默认配置
        this.execConfig = execConfig != null ? execConfig : new Config.ExecToolConfig();
        // 初始化工作空间限制标志
        this.restrictToWorkspace = restrictToWorkspace;
        // 初始化禁用技能集合，如果传入为null则初始化为空集合
        // 禁用的技能集合
        Set<String> disabledSkills1 = new HashSet<>(disabledSkills != null ? disabledSkills : List.of());
        this.skillsLoader = new SkillsLoader(workspace, null, disabledSkills1);
        // 初始化代理运行器
        this.runner = new AgentRunner(provider);

        this.executor = new ThreadPoolExecutor(
                1,
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                30,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "subagent-" + seq.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * 启动一个后台子代理任务。
     *
     * 返回给主 Agent 的是“已启动”提示；
     * 真正执行发生在 runSubagent(...) 里。
     *
     * @param task 任务描述
     * @param label 任务标签，用于显示
     * @param originChannel 来源渠道
     * @param originChatId 来源聊天ID
     * @param sessionKey 会话密钥，用于分组管理任务
     * @return 启动提示信息
     */
    public String spawn(
            String task,
            String label,
            String originChannel,
            String originChatId,
            String sessionKey
    ) {
        String taskId = UUID.randomUUID().toString().substring(0, SUBAGENT_ID_LENGTH);
        String displayLabel = resolveDisplayLabel(task, label);
        Origin origin = new Origin(
                originChannel != null ? originChannel : DEFAULT_ORIGIN_CHANNEL,
                originChatId != null ? originChatId : DEFAULT_ORIGIN_CHAT_ID
        );

        try {
            Future<?> future = submitSubagentTask(taskId, task, displayLabel, origin, sessionKey);
            registerRunningTask(taskId, sessionKey, future);
        } catch (RejectedExecutionException e) {
            log.warn("子代理任务队列已满，拒绝执行: label={}", displayLabel);
            return "子代理系统繁忙，请稍后再试。";
        }

        log.info("已启动子代理: id={}, label={}", taskId, displayLabel);
        return "子代理 [" + displayLabel + "] 已启动（id：" + taskId + "）。完成后我会通知你。";
    }

    private Future<?> submitSubagentTask(
            String taskId,
            String task,
            String displayLabel,
            Origin origin,
            String sessionKey
    ) {
        return executor.submit(() -> {
            try {
                runSubagent(taskId, task, displayLabel, origin);
            } finally {
                cleanupTask(taskId, sessionKey);
            }
        });
    }

    private void registerRunningTask(String taskId, String sessionKey, Future<?> future) {
        runningTasks.put(taskId, future);
        if (sessionKey != null) {
            sessionTasks.computeIfAbsent(sessionKey, key -> ConcurrentHashMap.newKeySet()).add(taskId);
        }
    }

    private void cleanupTask(String taskId, String sessionKey) {
        runningTasks.remove(taskId);
        if (sessionKey == null) {
            return;
        }
        Set<String> ids = sessionTasks.get(sessionKey);
        if (ids == null) {
            return;
        }
        ids.remove(taskId);
        if (ids.isEmpty()) {
            sessionTasks.remove(sessionKey);
        }
    }

    /**
     * 真正执行子代理任务。
     *
     * @param taskId 任务ID
     * @param task 任务描述
     * @param label 任务标签
     * @param origin 来源信息
     */
    private void runSubagent(
            String taskId,
            String task,
            String label,
            Origin origin
    ) {
        log.info("子代理开始: id={}, label={}", taskId, label);

        try {
            ToolRegistry tools = buildSubagentTools();
            AgentRunSpec spec = buildRunSpec(taskId, task, origin, tools);
            AgentRunResult result = runner.run(spec);
            log.info("子代理完成: id={}, label={}", taskId, label);
            announceResult(taskId, label, task, resolveAnnouncement(result), origin);
        } catch (CancellationException e) {
            announceResult(taskId, label, task, new Announcement(CANCELLED_MESSAGE, "cancelled"), origin);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            announceResult(taskId, label, task, new Announcement(CANCELLED_MESSAGE, "cancelled"), origin);
        } catch (Exception e) {
            String errorMsg = "错误：" + e.getMessage();
            log.warn("子代理执行失败: id={}, label={}", taskId, label, e);
            announceResult(taskId, label, task, new Announcement(errorMsg, "error"), origin);
        }
    }

    private ToolRegistry buildSubagentTools() {
        ToolRegistry tools = new ToolRegistry();
        Path allowedDir = resolveAllowedDir();

        tools.register(new ReadFileTool(workspace, allowedDir, List.of()));
        tools.register(new WriteFileTool(workspace, allowedDir));
        tools.register(new EditFileTool(workspace, allowedDir));
        tools.register(new ListDirTool(workspace, allowedDir));
        tools.register(new GlobTool(workspace, allowedDir));
        tools.register(new GrepTool(workspace, allowedDir));

        if (execConfig.isEnable()) {
            tools.register(new ExecTool(
                    execConfig.getTimeout(),
                    String.valueOf(workspace),
                    null,
                    null,
                    restrictToWorkspace,
                    execConfig.getSandbox(),
                    execConfig.getPathAppend(),
                    execConfig.getAllowedEnvKeys()
            ));
        }

        if (webConfig.isEnable()) {
            tools.register(new WebFetchTool(webConfig.getMaxChars(), webConfig.getProxy()));
            tools.register(new WebSearchTool(webConfig.getSearch(), webConfig.getProxy()));
        }
        return tools;
    }

    private Path resolveAllowedDir() {
        return (restrictToWorkspace || isSandboxEnabled(execConfig)) ? workspace : null;
    }

    private AgentRunSpec buildRunSpec(String taskId, String task, Origin origin, ToolRegistry tools) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSubagentPrompt(origin)));
        messages.add(Map.of("role", "user", "content", task));

        return new AgentRunSpec()
                .setInitialMessages(messages)
                .setTools(tools)
                .setModel(model)
                .setMaxIterations(SUBAGENT_MAX_ITERATIONS)
                .setMaxToolResultChars(maxToolResultChars)
                .setHook(new SubagentHook(taskId))
                .setMaxIterationsMessage(DEFAULT_MAX_ITERATIONS_MESSAGE)
                .setErrorMessage(null)
                .setFailOnToolError(true);
    }

    private Announcement resolveAnnouncement(AgentRunResult result) {
        if (result == null) {
            return new Announcement(DEFAULT_ERROR_MESSAGE, "error");
        }
        if ("tool_error".equals(result.getStopReason())) {
            return new Announcement(formatPartialProgress(result), "error");
        }
        if ("error".equals(result.getStopReason())) {
            return new Announcement(
                    result.getError() != null ? result.getError() : DEFAULT_ERROR_MESSAGE,
                    "error"
            );
        }
        if ("cancelled".equals(result.getStopReason())) {
            return new Announcement(CANCELLED_MESSAGE, "cancelled");
        }
        return new Announcement(
                result.getFinalContent() != null ? result.getFinalContent() : DEFAULT_MAX_ITERATIONS_MESSAGE,
                "ok"
        );
    }

    /**
     * 把子代理结果回传给主 Agent。
     *
     * 注意：
     * 这里不是直接发给用户，而是构造成 system message，
     * 重新进入主 Agent 的 MessageBus。
     *
     * @param taskId 任务ID
     * @param label 任务标签
     * @param task 任务描述
     * @param origin 来源信息
     */
    private void announceResult(
            String taskId,
            String label,
            String task,
            Announcement announcement,
            Origin origin
    ) {
        String status = announcement.status();
        String statusText = switch (status) {
            case "ok" -> "已完成";
            case "cancelled" -> "已取消";
            default -> "失败";
        };

        String announceContent = PromptTemplates.renderTemplate(
                "agent/subagent_announce.md",
                true,
                Map.of(
                        "label", label,
                        "status_text", statusText,
                        "task", task,
                        "result", announcement.result()
                )
        );

        InboundMessage msg = InboundMessages.of(
                "system",
                "subagent",
                origin.channel() + ":" + origin.chatId(),
                announceContent
        );

        try {
            bus.publishInbound(msg);
            log.info("子代理结果已回传: id={}, to={}:{}, status={}",
                    taskId,
                    origin.channel(),
                    origin.chatId(),
                    status
            );
        } catch (Exception e) {
            log.warn("发布子代理结果失败: id={}", taskId, e);
        }
    }

    /**
     * 格式化子代理失败前的部分执行进度。
     *
     * @param result 代理运行结果
     * @return 格式化的进度字符串
     */
    private String formatPartialProgress(AgentRunResult result) {
        // 存储成功的工具事件
        List<Map<String, Object>> completed = new ArrayList<>();
        // 存储失败的事件
        Map<String, Object> failure = null;

        if (result.getToolEvents() != null) {
            // 遍历所有工具事件，收集成功的事件
            for (Map<String, Object> event : result.getToolEvents()) {
                if ("ok".equals(String.valueOf(event.get("status")))) {
                    completed.add(event);
                }
            }

            // 从后向前遍历，找到第一个失败的事件
            for (int i = result.getToolEvents().size() - 1; i >= 0; i--) {
                Map<String, Object> event = result.getToolEvents().get(i);
                if ("error".equals(String.valueOf(event.get("status")))) {
                    failure = event;
                    break;
                }
            }
        }

        // 构建输出行列表
        List<String> lines = new ArrayList<>();

        // 如果有完成的步骤
        if (!completed.isEmpty()) {
            lines.add("已完成步骤：");
            // 只取最后3个完成的步骤，避免输出过多
            int start = Math.max(0, completed.size() - 3);
            for (int i = start; i < completed.size(); i++) {
                Map<String, Object> event = completed.get(i);
                // 添加步骤名称和详情
                lines.add("- " + String.valueOf(event.get("name")) + ": " + String.valueOf(event.get("detail")));
            }
        }

        // 如果有失败的事件
        if (failure != null) {
            // 如果已有内容，添加空行分隔
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("失败：");
            // 添加失败的工具名称和详情
            lines.add("- " + String.valueOf(failure.get("name")) + ": " + String.valueOf(failure.get("detail")));
        }

        // 如果有错误信息且没有具体的失败事件
        if (result.getError() != null && failure == null) {
            // 如果已有内容，添加空行分隔
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("失败：");
            // 添加错误信息
            lines.add("- " + result.getError());
        }

        // 如果没有任何行，返回错误信息或默认消息
        return lines.isEmpty()
                ? "错误：子代理执行失败。"
                : String.join("\n", lines);
    }

    private String buildSubagentPrompt(Origin origin) {
        String channel = origin != null ? origin.channel() : null;
        String chatId = origin != null ? origin.chatId() : null;
        String timeCtx = ContextBuilder.buildRuntimeContext(channel, chatId, null);
        String skillsSummary = skillsLoader.buildSkillsSummary();

        // 渲染子代理系统提示词模板
        return PromptTemplates.renderTemplate(
                "agent/subagent_system.md",
                true,
                Map.of(
                        "time_ctx", timeCtx,
                        "workspace", String.valueOf(workspace),
                        "skills_summary", skillsSummary
                )
        );
    }

    /**
     * 按 session 取消该会话下所有仍在运行的子代理。
     *
     * 返回取消数量。
     *
     * @param sessionKey 会话密钥
     * @return 被取消的任务数量
     */
    public int cancelBySession(String sessionKey) {
        // 获取该会话下的所有任务ID，如果不存在则返回空集合
        Set<String> ids = sessionTasks.getOrDefault(sessionKey, Set.of());

        // 存储待取消的Future列表
        List<Future<?>> futures = new ArrayList<>();
        // 遍历所有任务ID
        for (String tid : ids) {
            // 获取对应的Future
            Future<?> f = runningTasks.get(tid);
            // 如果Future存在且未完成
            if (f != null && !f.isDone()) {
                // 添加到待取消列表
                futures.add(f);
            }
        }

        // 取消所有待取消的任务
        for (Future<?> f : futures) {
            // 尝试中断执行
            f.cancel(true);
        }

        if (!futures.isEmpty()) {
            sessionTasks.remove(sessionKey);
        }

        // 返回被取消的任务数量
        return futures.size();
    }

    public int getRunningCount() {
        return runningTasks.size();
    }

    @Override
    public void close() {
        for (Future<?> f : runningTasks.values()) {
            try {
                f.cancel(true);
            } catch (Exception ignored) {
            }
        }
        runningTasks.clear();
        sessionTasks.clear();
        executor.shutdownNow();
    }

    // =========================================================
    // 小工具方法
    // =========================================================

    /**
     * 截断文本到指定长度。
     *
     * @param text 原始文本
     * @param maxLen 最大长度
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen
                ? text
                : text.substring(0, maxLen) + "...";
    }

    private String resolveDisplayLabel(String task, String label) {
        return (label != null && !label.isBlank())
                ? label
                : truncate(task, DEFAULT_LABEL_MAX_LENGTH);
    }

    /**
     * 兼容你前面 Config.ExecToolConfig 里 sandbox 是 String 的写法。
     * 检查沙箱是否启用。
     *
     * @param cfg 执行工具配置
     * @return 如果沙箱配置不为空且非空白则返回true
     */
    private boolean isSandboxEnabled(Config.ExecToolConfig cfg) {
        return cfg.getSandbox() != null && !cfg.getSandbox().isBlank();
    }

    private record Origin(String channel, String chatId) {
    }

    private record Announcement(String result, String status) {
    }
}
