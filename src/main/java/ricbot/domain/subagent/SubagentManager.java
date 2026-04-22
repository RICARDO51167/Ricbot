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
    // 禁用的技能集合
    private final Set<String> disabledSkills;
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
        this.disabledSkills = new HashSet<>(disabledSkills != null ? disabledSkills : List.of());
        this.skillsLoader = new SkillsLoader(workspace, null, this.disabledSkills);
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
        // 生成8位随机任务ID
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        // 确定显示标签，如果label为空则截取任务描述前30个字符
        String displayLabel = (label != null && !label.isBlank())
                ? label
                : truncate(task, 30);

        // 构建来源信息映射
        Map<String, String> origin = new HashMap<>();
        // 设置渠道，默认为"cli"
        origin.put("channel", originChannel != null ? originChannel : "cli");
        // 设置聊天ID，默认为"direct"
        origin.put("chat_id", originChatId != null ? originChatId : "direct");

        Future<?> future;
        try {
            future = executor.submit(() -> {
                try {
                    runSubagent(taskId, task, displayLabel, origin);
                } finally {
                    runningTasks.remove(taskId);
                    if (sessionKey != null) {
                        Set<String> ids = sessionTasks.get(sessionKey);
                        if (ids != null) {
                            ids.remove(taskId);
                            if (ids.isEmpty()) {
                                sessionTasks.remove(sessionKey);
                            }
                        }
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("子代理任务队列已满，拒绝执行: label={}", displayLabel);
            return "子代理系统繁忙，请稍后再试。";
        }

        // 将任务Future存入运行任务映射
        runningTasks.put(taskId, future);

        // 如果提供了会话密钥，将会话与任务关联
        if (sessionKey != null) {
            // 获取或创建该会话的任务ID集合，并添加当前任务ID
            sessionTasks.computeIfAbsent(sessionKey, k -> ConcurrentHashMap.newKeySet()).add(taskId);
        }

        log.info("已启动子代理: id={}, label={}", taskId, displayLabel);

        // 返回启动成功提示信息
        return "子代理 [" + displayLabel + "] 已启动（id：" + taskId + "）。完成后我会通知你。";
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
            Map<String, String> origin
    ) {
        log.info("子代理开始: id={}, label={}", taskId, label);

        try {
            // -----------------------------
            // 1. 构造子代理自己的工具集
            // -----------------------------
            // 创建新的工具注册表
            ToolRegistry tools = new ToolRegistry();

            // 确定允许访问的目录：如果限制工作空间或启用了沙箱，则限制为工作空间目录，否则为null（无限制）
            Path allowedDir = (restrictToWorkspace || isSandboxEnabled(execConfig))
                    ? workspace
                    : null;

            // 注册文件读取工具
            tools.register(new ReadFileTool(workspace, allowedDir, List.of()));
            // 注册文件写入工具
            tools.register(new WriteFileTool(workspace, allowedDir));
            // 注册文件编辑工具
            tools.register(new EditFileTool(workspace, allowedDir));
            // 注册目录列表工具
            tools.register(new ListDirTool(workspace, allowedDir));
            // 注册全局匹配搜索工具
            tools.register(new GlobTool(workspace, allowedDir));
            // 注册内容搜索工具
            tools.register(new GrepTool(workspace, allowedDir));

            // 如果执行工具启用，则注册执行工具
            if (execConfig.isEnable()) {
                tools.register(new ExecTool(
                        execConfig.getTimeout(), // 超时时间
                        String.valueOf(workspace), // 工作目录
                        null, // 额外路径
                        null, // 额外环境变量
                        restrictToWorkspace, // 是否限制工作空间
                        execConfig.getSandbox(), // 沙箱配置
                        execConfig.getPathAppend(), // 路径追加
                        execConfig.getAllowedEnvKeys() // 允许的环境变量键
                ));
            }

            if (webConfig.isEnable()) {
                tools.register(new WebFetchTool(webConfig.getMaxChars(), webConfig.getProxy()));
                tools.register(new WebSearchTool(webConfig.getSearch(), webConfig.getProxy()));
            }

            // -----------------------------
            // 2. 构造子代理 prompt
            // -----------------------------
            // 构建子代理系统提示词
            String systemPrompt = buildSubagentPrompt(origin);

            // 创建消息列表
            List<Map<String, Object>> messages = new ArrayList<>();
            // 添加系统消息
            messages.add(Map.of(
                    "role", "system",
                    "content", systemPrompt
            ));
            // 添加用户任务消息
            messages.add(Map.of(
                    "role", "user",
                    "content", task
            ));

            // -----------------------------
            // 3. 跑子代理
            // -----------------------------
            // 创建代理运行规范
            AgentRunSpec spec = new AgentRunSpec();
            // 设置初始消息
            spec.setInitialMessages(messages);
            // 设置工具集
            spec.setTools(tools);
            // 设置模型
            spec.setModel(model);
            // 设置最大迭代次数
            spec.setMaxIterations(15);
            // 设置最大工具结果字符数
            spec.setMaxToolResultChars(maxToolResultChars);
            // 设置钩子，用于日志记录
            spec.setHook(new SubagentHook(taskId));
            // 设置达到最大迭代次数时的消息
            spec.setMaxIterationsMessage("任务已结束，但未生成最终回复。");
            // 设置错误消息为null
            spec.setErrorMessage(null);
            // 设置在工具错误时失败
            spec.setFailOnToolError(true);

            // 运行代理并获取结果
            AgentRunResult result = runner.run(spec);

            // -----------------------------
            // 4. 处理结果
            // -----------------------------
            // 如果停止原因是工具错误
            if ("tool_error".equals(result.getStopReason())) {
                // 宣布结果为错误，包含部分进度信息
                announceResult(
                        taskId,
                        label,
                        task,
                        formatPartialProgress(result),
                        origin,
                        "error"
                );
                return;
            }

            // 如果停止原因是其他错误
            if ("error".equals(result.getStopReason())) {
                // 宣布结果为错误，包含错误信息
                announceResult(
                        taskId,
                        label,
                        task,
                        result.getError() != null ? result.getError() : "错误：子代理执行失败。",
                        origin,
                        "error"
                );
                return;
            }

            if ("cancelled".equals(result.getStopReason())) {
                announceResult(taskId, label, task, "任务已取消。", origin, "cancelled");
                return;
            }

            // 获取最终结果内容，如果为空则使用默认消息
            String finalResult = result.getFinalContent() != null
                    ? result.getFinalContent()
                    : "任务已结束，但未生成最终回复。";

            log.info("子代理完成: id={}, label={}", taskId, label);
            // 宣布结果为成功
            announceResult(taskId, label, task, finalResult, origin, "ok");

        } catch (CancellationException e) {
            announceResult(taskId, label, task, "任务已取消。", origin, "cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            announceResult(taskId, label, task, "任务已取消。", origin, "cancelled");
        } catch (Exception e) {
            // 捕获异常，构建错误消息
            String errorMsg = "错误：" + e.getMessage();
            log.warn("子代理执行失败: id={}, label={}", taskId, label, e);
            // 宣布结果为错误
            announceResult(taskId, label, task, errorMsg, origin, "error");
        }
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
     * @param result 执行结果
     * @param origin 来源信息
     * @param status 状态（ok 或 error）
     */
    private void announceResult(
            String taskId,
            String label,
            String task,
            String result,
            Map<String, String> origin,
            String status
    ) {
        // 根据状态确定状态文本
        String statusText = switch (status) {
            case "ok" -> "已完成";
            case "cancelled" -> "已取消";
            default -> "失败";
        };

        // 渲染公告内容模板
        String announceContent = PromptTemplates.renderTemplate(
                "agent/subagent_announce.md",
                true,
                Map.of(
                        "label", label,
                        "status_text", statusText,
                        "task", task,
                        "result", result
                )
        );

        InboundMessage msg = InboundMessages.of(
                "system",
                "subagent",
                origin.get("channel") + ":" + origin.get("chat_id"),
                announceContent
        );

        try {
            // 发布消息到消息总线
            bus.publishInbound(msg);
            log.info("子代理结果已回传: id={}, to={}:{}, status={}",
                    taskId,
                    origin.get("channel"),
                    origin.get("chat_id"),
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
                ? (result.getError() != null ? result.getError() : "错误：子代理执行失败。")
                : String.join("\n", lines);
    }

    /**
     * 构造子代理专用 system prompt。
     *
     * @return 渲染后的系统提示词
     */
    private String buildSubagentPrompt(Map<String, String> origin) {
        String channel = origin != null ? origin.get("channel") : null;
        String chatId = origin != null ? origin.get("chat_id") : null;
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

    /**
     * 获取当前运行中的子代理数量。
     *
     * @return 运行中的任务数量
     */
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
        // 如果文本为null，返回空字符串
        if (text == null) {
            return "";
        }
        // 如果文本长度不超过最大长度，直接返回
        return text.length() <= maxLen
                ? text
                // 否则截取前maxLen个字符并添加省略号
                : text.substring(0, maxLen) + "...";
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
}
