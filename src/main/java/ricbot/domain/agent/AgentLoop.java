package ricbot.domain.agent;

import ricbot.domain.skill.SkillsLoader;
import ricbot.domain.skill.SkillRouter;
import ricbot.domain.skill.SkillRoutingContext;
import ricbot.infra.cron.CronService;
import ricbot.infra.cron.CronTypes.CronJob;
import ricbot.tool.web.WebFetchTool;
import ricbot.tool.web.WebSearchTool;
import ricbot.domain.memory.Consolidator;
import ricbot.domain.memory.Dream;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.subagent.SubagentManager;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.hook.AgentHookContext;
import ricbot.tool.api.ToolRegistry;
import ricbot.tool.cron.CronTool;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.NotebookEditTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.process.ExecTool;
import ricbot.tool.process.SpawnTool;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;
import ricbot.integration.mcp.MCPLoader;
import ricbot.integration.command.CommandRouter;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.infra.config.Config;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.ToolCallRequest;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.infra.common.HelperUtils;
import ricbot.infra.runtime.RuntimeUtils;
import ricbot.infra.template.ToolHintFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 主循环：ricbot 的核心调度引擎。
 */
public class AgentLoop {

    /** 日志记录器，用于记录 AgentLoop 类的运行日志 */
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** 统一会话的默认键值，当启用统一会话模式时使用 */
    public static final String UNIFIED_SESSION_KEY = "unified:default";
    /** 会话元数据中用于存储运行时检查点的键 */
    private static final String RUNTIME_CHECKPOINT_KEY = "runtime_checkpoint";
    /** 会话元数据中用于标记待处理用户轮次的键 */
    private static final String PENDING_USER_TURN_KEY = "pending_user_turn";

    /** 消息总线，用于接收入站消息和发送出站消息 */
    private final MessageBus bus;
    /** LLM 提供者，用于调用大语言模型 */
    private final LLMProvider provider;
    /** 工作空间路径，用于限制文件操作范围 */
    private final Path workspace;
    /** 使用的模型名称 */
    private final String model;

    /** 最大工具调用迭代次数 */
    private final int maxIterations;
    /** 上下文窗口大小（Token 数） */
    private final int contextWindowTokens;
    /** 上下文块数量限制，可选 */
    private final Integer contextBlockLimit;
    /** 工具返回结果的最大字符数 */
    private final int maxToolResultChars;

    /** Web 工具配置 */
    private final Config.WebToolsConfig webConfig;
    /** 执行工具配置 */
    private final Config.ExecToolConfig execConfig;
    /** Dream 配置 */
    private final Config.DreamConfig dreamConfig;
    /** MCP 服务器配置映射 */
    private final Map<String, Object> mcpServers;
    /** 是否限制操作仅在工作空间内 */
    private final boolean restrictToWorkspace;
    /** 是否启用统一会话模式 */
    private final boolean unifiedSession;
    /** Provider 重试模式（透传到运行规格） */
    private final String providerRetryMode;
    /** 会话自动归档 TTL（分钟），0 表示禁用 */
    private final int sessionTtlMinutes;

    /** 上下文构建器，用于构建发送给 LLM 的消息上下文 */
    private final ContextBuilder contextBuilder;
    /** 会话管理器，负责会话的创建、加载和保存 */
    private final SessionManager sessionManager;
    /** 记忆存储，用于长期记忆管理 */
    private final MemoryStore memoryStore;
    /** 记忆整合器，用于压缩和整理历史消息 */
    private final Consolidator consolidator;
    /** Dream 模块，用于后台记忆整理和反思 */
    private final Dream dream;
    /** 会话自动归档器 */
    private final AutoCompact autoCompact;
    /** 子代理管理器，用于管理子代理任务 */
    private final SubagentManager subagents;
    /** 技能加载器，用于加载可用技能 */
    private final SkillsLoader skillsLoader;
    /** 技能路由器，用于根据上下文选择合适技能 */
    private final SkillRouter skillRouter;
    /** 定时任务服务 */
    private final CronService cronService;
    /** 工具注册表，管理所有可用工具 */
    private final ToolRegistry tools;
    /** Agent 运行器，负责执行具体的 LLM 交互循环 */
    private final AgentRunner runner;
    /** MCP 兼容加载器（统一 MCP 装配路径） */
    private final MCPLoader mcpLoader;
    /** 命令路由器，统一 slash 命令入口 */
    private final CommandRouter commandRouter;

    /** 会话锁映射，用于保证同一会话的串行处理 */
    private final ConcurrentMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    /** 活跃任务映射，记录每个会话当前正在执行的任务 Future */
    private final ConcurrentMap<String, List<Future<?>>> activeTasks = new ConcurrentHashMap<>();
    /** 并发控制信号量，限制同时处理的消息数量 */
    private final Semaphore concurrencyGate;
    /** 线程池执行器，用于异步处理消息 */
    private final ExecutorService executor;
    /** 定时任务调度器，用于执行周期性任务 */
    private final ScheduledExecutorService scheduler;

    /** Agent 循环运行状态标志 */
    private volatile boolean running = false;
    private final AtomicBoolean backgroundStarted = new AtomicBoolean(false);
    private final AtomicBoolean loopThreadStarted = new AtomicBoolean(false);
    /** 额外的 Agent 钩子列表 */
    private List<AgentHook> extraHooks = new ArrayList<>();

    /**
     * 构造 AgentLoop 实例。
     *
     * @param bus                消息总线
     * @param provider           LLM 提供者
     * @param workspace          工作空间路径
     * @param model              模型名称
     * @param maxIterations      最大迭代次数
     * @param contextWindowTokens 上下文窗口大小
     * @param contextBlockLimit  上下文块限制
     * @param maxToolResultChars 工具结果最大字符数
     * @param providerRetryMode  提供者重试模式
     * @param webConfig          Web 工具配置
     * @param execConfig         执行工具配置
     * @param mcpServers         MCP 服务器配置
     * @param restrictToWorkspace 是否限制工作空间
     * @param sessionManager     会话管理器
     * @param timezone           时区
     * @param unifiedSession     是否统一会话
     * @param disabledSkills     禁用的技能列表
     * @param sessionTtlMinutes  会话 TTL（分钟，<=0 表示禁用）
     * @param dreamConfig        Dream 配置
     */
    public AgentLoop(
            MessageBus bus,
            LLMProvider provider,
            Path workspace,
            String model,
            Integer maxIterations,
            Integer contextWindowTokens,
            Integer contextBlockLimit,
            Integer maxToolResultChars,
            String providerRetryMode,
            Config.WebToolsConfig webConfig,
            Config.ExecToolConfig execConfig,
            Map<String, Object> mcpServers,
            boolean restrictToWorkspace,
            SessionManager sessionManager,
            String timezone,
            boolean unifiedSession,
            List<String> disabledSkills,
            int sessionTtlMinutes,
            Config.DreamConfig dreamConfig
    ) {
        // 获取默认配置
        Config.AgentDefaults defaults = new Config.AgentDefaults();

        // 初始化基础组件
        this.bus = bus;
        this.provider = provider;
        this.workspace = workspace.toAbsolutePath().normalize();
        this.model = model != null ? model : provider.getDefaultModel();

        // 初始化配置参数，使用默认值填充 null 值
        this.maxIterations = maxIterations != null ? maxIterations : defaults.getMaxToolIterations();
        this.contextWindowTokens = contextWindowTokens != null ? contextWindowTokens : defaults.getContextWindowTokens();
        this.contextBlockLimit = contextBlockLimit;
        this.maxToolResultChars = maxToolResultChars != null ? maxToolResultChars : defaults.getMaxToolResultChars();

        this.webConfig = webConfig != null ? webConfig : new Config.WebToolsConfig();
        this.execConfig = execConfig != null ? execConfig : new Config.ExecToolConfig();
        this.dreamConfig = dreamConfig != null ? dreamConfig : defaults.getDream();
        this.mcpServers = mcpServers != null ? mcpServers : Collections.emptyMap();
        this.restrictToWorkspace = restrictToWorkspace;
        this.unifiedSession = unifiedSession;
        this.providerRetryMode = providerRetryMode != null && !providerRetryMode.isBlank()
                ? providerRetryMode
                : defaults.getProviderRetryMode();
        this.sessionTtlMinutes = sessionTtlMinutes > 0 ? sessionTtlMinutes : defaults.getSessionTtlMinutes();

        // 初始化核心组件
        this.contextBuilder = new ContextBuilder(this.workspace, timezone, disabledSkills);
        this.sessionManager = sessionManager != null ? sessionManager : new SessionManager(this.workspace);
        this.memoryStore = new MemoryStore(this.workspace);
        
        // 初始化记忆整合器
        this.consolidator = new Consolidator(
                this.memoryStore,
                this.provider,
                this.model,
                this.sessionManager,
                this.contextWindowTokens,
                4096 // maxCompletionTokens placeholder
        );
        
        // 初始化 Dream 模块
        this.dream = new Dream(this.workspace, this.provider, this.model, this.memoryStore);
        this.autoCompact = new AutoCompact(this.sessionManager, this.consolidator, this.sessionTtlMinutes);
        
        // 初始化子代理管理器
        this.subagents = new SubagentManager(
                this.provider,
                this.workspace,
                this.bus,
                this.maxToolResultChars,
                this.model,
                this.webConfig,
                this.execConfig,
                this.restrictToWorkspace,
                disabledSkills
        );
        
        // 初始化技能加载器
        this.skillsLoader = new SkillsLoader(
                this.workspace,
                null, // builtinDir will be resolved automatically
                disabledSkills != null ? new HashSet<>(disabledSkills) : new HashSet<>()
        );
        
        // 初始化技能路由器，从环境变量读取配置
        this.skillRouter = new SkillRouter(
                this.skillsLoader,
                parseInt(System.getenv("RICBOT_SKILLS_MAX_SELECTED"), 3),
                parseInt(System.getenv("RICBOT_SKILLS_MAX_CHARS"), 12000)
        );
        
        // 初始化定时任务服务
        this.cronService = new CronService(workspace.resolve(".ricbot").resolve("cron").resolve("store.json"));
        this.cronService.setOnJob(this::handleCronJob);

        // 初始化工具注册表和运行器
        this.tools = new ToolRegistry();
        this.runner = new AgentRunner(provider);
        this.mcpLoader = new MCPLoader(this.tools, this.mcpServers);
        this.commandRouter = new CommandRouter();

        // 初始化并发控制
        int maxConcurrent = parseInt(System.getenv("RICBOT_MAX_CONCURRENT_REQUESTS"), 3);
        this.concurrencyGate = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;

        // 初始化线程池
        this.executor = Executors.newCachedThreadPool();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        // 注册默认工具
        registerDefaultTools();
        registerCommandRoutes();
    }

    // ---------------------------------------------------------------------
    // Tool registration
    // ---------------------------------------------------------------------

    /**
     * 注册默认工具。
     *
     * 最小可展示工具集：read_file / list_dir / exec
     */
    private void registerDefaultTools() {
        // 计算允许的操作目录，如果限制工作空间或启用沙箱，则限制为工作空间
        Path allowedDir = (restrictToWorkspace || execConfig.isSandbox()) ? workspace : null;

        // 注册文件系统工具
        tools.register(new ReadFileTool(workspace, allowedDir, List.of()));
        tools.register(new ListDirTool(workspace, allowedDir));
        tools.register(new WriteFileTool(workspace, allowedDir));
        tools.register(new EditFileTool(workspace, allowedDir));
        tools.register(new NotebookEditTool(workspace, allowedDir, List.of()));

        // 注册搜索工具
        tools.register(new GlobTool(workspace, allowedDir));
        tools.register(new GrepTool(workspace, allowedDir));

        // 注册 Shell 执行工具
        if (execConfig.isEnable()) {
            tools.register(new ExecTool(
                    execConfig.getTimeout(),
                    workspace.toString(),
                    null,
                    null,
                    restrictToWorkspace,
                    execConfig.isSandbox() ? "sandbox" : "",
                    execConfig.getPathAppend(),
                    execConfig.getAllowedEnvKeys()
            ));
            tools.register(new SpawnTool(subagents));
        }

        // 注册定时任务工具
        if (cronService != null) {
            tools.register(new CronTool(cronService, contextBuilder.getTimezone()));
        }

        // 注册 Web 工具
        if (webConfig.isEnable()) {
            tools.register(new WebFetchTool(
                    webConfig.getMaxChars(),
                    webConfig.getProxy()
            ));
            tools.register(new WebSearchTool(
                    webConfig.getSearch(),
                    webConfig.getProxy()
            ));
        }

    }

    private void registerCommandRoutes() {
        commandRouter.priority("/stop", this::cmdStop);
        commandRouter.priority("/restart", this::cmdDisabled);
        commandRouter.exact("/new", this::cmdNew);
        commandRouter.exact("/help", this::cmdHelp);
        commandRouter.exact("/status", this::cmdStatus);

        commandRouter.exact("/dream", this::cmdDream);
        commandRouter.exact("/dream-log", this::cmdDreamLog);
        commandRouter.prefix("/dream-log ", this::cmdDreamLog);
        commandRouter.exact("/dream-restore", this::cmdDreamRestore);
        commandRouter.prefix("/dream-restore ", this::cmdDreamRestore);
    }

    // ---------------------------------------------------------------------
    // Main loop
    // ---------------------------------------------------------------------

    /**
     * 处理定时任务回调。
     *
     * @param job 定时任务对象
     * @return 处理结果字符串
     */
    private String handleCronJob(CronJob job) {
        log.info("执行定时任务: {}", job.getName());
        
        // 构建入站消息
        InboundMessage msg = new InboundMessage();
        msg.setChannel(job.getPayload().getChannel() != null ? job.getPayload().getChannel() : "system");
        msg.setChatId(job.getPayload().getTo() != null ? job.getPayload().getTo() : "cron");
        msg.setContent(job.getPayload().getMessage());
        msg.setSenderId("cron");
        
        // 标记这是一个 cron 任务
        msg.getMetadata().put("_cron_job_id", job.getId());
        msg.getMetadata().put("_cron_job_name", job.getName());
        msg.getMetadata().put("_deliver", job.getPayload().isDeliver());

        // 提交任务到线程池异步分发
        executor.submit(() -> dispatch(msg));
        return "任务已分发";
    }

    /**
     * 启动 Agent 主循环。
     */
    public void start() {
        startBackgroundIfNeeded();
        if (loopThreadStarted.compareAndSet(false, true)) {
            new Thread(this::run, "agent-loop").start();
        }
    }

    /**
     * Agent 主循环运行逻辑。
     */
    public void run() {
        // 标记 Agent 循环为运行状态
        this.running = true;
        // 记录启动日志
        log.info("Agent 循环已启动");

        // 主循环：持续监听 inbound 消息
        while (running) {
            try {
                // 阻塞最多 500ms 等待入站消息，若超时返回 null
                InboundMessage msg = bus.consumeInbound(500, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    // 无消息则继续下一轮循环
                    continue;
                }

                // 获取消息内容并去除首尾空白
                String raw = trim(msg.getContent());

                // /stop 优先处理：立即停止当前会话任务
                if (raw != null && raw.startsWith("/") && commandRouter.isPriority(raw)) {
                    OutboundMessage priorityOut = dispatchCommand(msg, null, effectiveSessionKey(msg), raw, true);
                    if (priorityOut != null) {
                        bus.publishOutbound(priorityOut);
                    }
                    continue;
                }

                // 正常异步分发：计算会话 Key
                String sessionKey = effectiveSessionKey(msg);

                // 提交任务到线程池异步执行
                Future<?> future = executor.submit(() -> {
                    try {
                        // 如果配置了并发限制信号量，则先获取许可
                        if (concurrencyGate != null) {
                            concurrencyGate.acquire();
                        }
                        // 分发消息进行处理
                        dispatch(msg);
                    } catch (InterruptedException e) {
                        // 恢复中断状态
                        Thread.currentThread().interrupt();
                    } finally {
                        // 释放并发限制信号量许可
                        if (concurrencyGate != null) {
                            concurrencyGate.release();
                        }
                    }
                });

                // 将未来任务对象添加到对应会话的活动任务列表中
                activeTasks.computeIfAbsent(sessionKey, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(future);

            } catch (InterruptedException e) {
                // 线程被中断，恢复中断状态并退出循环
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 捕获其他未预期异常，记录日志并继续运行
                log.error("Agent 循环出错", e);
            }
        }
    }

    private void startBackgroundIfNeeded() {
        if (!backgroundStarted.compareAndSet(false, true)) {
            return;
        }
        this.running = true;
        this.cronService.start();
        if (mcpServers != null && !mcpServers.isEmpty()) {
            executor.submit(() -> {
                try {
                    mcpLoader.load();
                } catch (Exception e) {
                    log.error("MCP 加载失败", e);
                }
            });
        }
        if (dreamConfig != null && dreamConfig.isEnabled()) {
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    dream.run();
                } catch (Exception e) {
                    log.error("后台 Dream 任务出错", e);
                }
            }, 15, 15, TimeUnit.MINUTES);
        }

        if (sessionTtlMinutes > 0) {
            scheduler.scheduleWithFixedDelay(this::runAutoCompactSweep, 1, 1, TimeUnit.MINUTES);
        }
    }

    public void stop() {
        this.running = false;
        for (String sessionKey : activeTasks.keySet()) {
            markSessionInterrupted(sessionKey, "shutdown");
        }
        this.cronService.stop();
        try {
            subagents.close();
        } catch (Exception e) {
            log.warn("关闭子代理管理器失败", e);
        }
        mcpLoader.close();
        executor.shutdownNow();
        scheduler.shutdownNow();
        log.info("Agent 循环正在停止");
    }

    public Dream getDream() { return dream; }
    public SubagentManager getSubagents() { return subagents; }
    public SessionManager getSessions() { return sessionManager; }
    public Consolidator getConsolidator() { return consolidator; }

    // ---------------------------------------------------------------------
    // Dispatch / processing
    // ---------------------------------------------------------------------

    /**
     * 分发消息到对应的会话进行处理。
     *
     * @param msg 入站消息
     */
    private void dispatch(InboundMessage msg) {
        // 计算有效的会话键，用于确定消息所属的会话
        String sessionKey = effectiveSessionKey(msg);
        // 获取或创建会话锁，确保同一会话的消息串行处理
        Object lock = sessionLocks.computeIfAbsent(sessionKey, k -> new Object());

        // 同步块，保证线程安全
        synchronized (lock) {
            try {
                // 处理消息并获取响应
                OutboundMessage response = processMessage(msg, sessionKey);
                if (response != null) {
                    // 如果响应不为空，发布出站消息
                    bus.publishOutbound(response);
                } else if ("cli".equals(msg.getChannel())) {
                    // CLI 通道特殊处理：如果响应为空，发送一个空包以结束本轮输出
                    OutboundMessage empty = new OutboundMessage();
                    empty.setChannel(msg.getChannel());
                    empty.setChatId(msg.getChatId());
                    empty.setContent("");
                    empty.setMetadata(msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>());
                    bus.publishOutbound(empty);
                }
            } catch (Exception e) {
                // 捕获处理过程中的异常，记录日志并发送错误消息
                log.error("处理会话 {} 的消息时出错", sessionKey, e);
                OutboundMessage error = new OutboundMessage();
                error.setChannel(msg.getChannel());
                error.setChatId(msg.getChatId());
                error.setContent("抱歉，我遇到了一点错误。");
                error.setMetadata(new HashMap<>());
                try {
                    // 尝试发布错误消息
                    bus.publishOutbound(error);
                } catch (Exception publishErr) {
                    log.warn("发布错误消息失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), publishErr);
                }
            } finally {
                // 清理已完成的任务引用，防止内存泄漏
                List<Future<?>> tasks = activeTasks.get(sessionKey);
                if (tasks != null) {
                    tasks.removeIf(Future::isDone);
                }
            }
        }
    }

    /**
     * 处理单条用户消息。
     *
     * @param msg       入站消息
     * @param sessionKey 会话键
     * @return 出站消息响应
     * @throws Exception 处理过程中可能抛出的异常
     */
    private OutboundMessage processMessage(InboundMessage msg, String sessionKey) throws Exception {
        // 如果是系统消息，委托给专门的处理方法
        if ("system".equals(msg.getChannel())) {
            return processSystemMessage(msg);
        }

        // 生成消息内容预览，用于日志记录
        String preview = msg.getContent() != null && msg.getContent().length() > 80
                ? msg.getContent().substring(0, 80) + "..."
                : String.valueOf(msg.getContent());
        log.info("处理来自 {}:{} 的消息: {}", msg.getChannel(), msg.getSenderId(), preview);

        // 获取或创建会话对象
        Session baseSession = sessionManager.getOrCreate(sessionKey);
        AutoCompact.PreparedSession prepared = autoCompact.prepareSession(baseSession, sessionKey);
        String summaryContext = prepared != null ? prepared.summary() : null;
        Session session = prepared != null ? prepared.session() : baseSession;

        // 根据需要归档旧消息，以优化上下文窗口
        consolidator.maybeConsolidateByTokens(session);

        // 恢复之前的运行时检查点（如果有）
        restoreRuntimeCheckpoint(session);
        // 恢复待处理的用户轮次标记（如果有）
        restorePendingUserTurn(session);

        // 处理斜杠命令（统一路由）
        String raw = trim(msg.getContent());
        if (raw != null && raw.startsWith("/")) {
            OutboundMessage commandOut = dispatchCommand(msg, session, sessionKey, raw, false);
            if (commandOut != null) {
                return commandOut;
            }
        }

        // 设置工具执行上下文
        setToolContext(msg.getChannel(), msg.getChatId(), messageIdOf(msg));

        // 获取记忆上下文
        String memoryContext = memoryStore.getMemoryContext();
        // 获取技能上下文
        String skillsContext = skillsLoader.getSkillsContext();
        // 根据当前上下文选择并渲染合适的技能
        SkillRouter.SelectionResult selected = skillRouter.selectAndRender(new SkillRoutingContext(
                workspace,
                msg.getChannel(),
                msg.getChatId(),
                msg.getContent(),
                tools.toolNames(),
                msg.getMetadata(),
                Map.of()
        ));

        // 组合所有上下文信息
        String combinedContext = (memoryContext != null ? memoryContext : "")
                + "\n" + (skillsContext != null ? skillsContext : "")
                + (summaryContext != null && !summaryContext.isBlank()
                ? "\n" + summaryContext
                : "")
                + (selected.renderedContext() != null && !selected.renderedContext().isBlank()
                ? "\n" + selected.renderedContext()
                : "");

        // 获取会话历史消息窗口
        List<Map<String, Object>> history = session.getHistory(historyWindowAsMessages());
        // 构建发送给 LLM 的初始消息列表
        List<Map<String, Object>> initialMessages = contextBuilder.buildMessages(
                history,
                msg.getContent(),
                msg.getMedia(),
                msg.getChannel(),
                msg.getChatId(),
                combinedContext,
                "user"
        );

        // 提前持久化用户消息，防止后续处理崩溃导致消息丢失
        boolean userPersistedEarly = false;
        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            session.addMessage("user", msg.getContent());
            markPendingUserTurn(session);
            sessionManager.save(session);
            userPersistedEarly = true;
        }

        // 构建 Agent 运行钩子，用于处理流式输出等事件
        AgentHook hook = buildLoopHook(msg);

        // 构建 Agent 运行规格
        AgentRunSpec spec = new AgentRunSpec()
                .setInitialMessages(initialMessages)
                .setTools(tools)
                .setModel(model)
                .setMaxIterations(maxIterations)
                .setMaxToolResultChars(maxToolResultChars)
                .setHook(hook)
                .setProviderRetryMode(providerRetryMode)
                .setErrorMessage("抱歉，调用模型时遇到错误。")
                .setMaxIterationsMessage("我已达到最大迭代次数（agents.defaults.max_tool_iterations=" + maxIterations + "），但仍未完成任务。可尝试提高该值（例如 12 或 16）后重试。")
                .setConcurrentTools(true)
                .setWorkspace(workspace)
                .setSessionKey(session.getKey())
                .setContextWindowTokens(contextWindowTokens)
                .setContextBlockLimit(contextBlockLimit)
                .setCheckpointCallback(payload -> setRuntimeCheckpoint(session, payload));

        // 执行 Agent 运行循环
        AgentRunResult runResult = runner.run(spec);
        if (hook == null || !hook.wantsStreaming()) {
            if ("tool_loop".equals(runResult.getStopReason()) || "tool_error_loop".equals(runResult.getStopReason())) {
                int bumped = Math.min(30, Math.max(maxIterations + 6, maxIterations * 2));
                if (bumped > maxIterations) {
                    AgentRunSpec retrySpec = new AgentRunSpec()
                            .setInitialMessages(runResult.getMessages())
                            .setTools(tools)
                            .setModel(model)
                            .setMaxIterations(bumped)
                            .setMaxToolResultChars(maxToolResultChars)
                            .setHook(hook)
                            .setProviderRetryMode(providerRetryMode)
                            .setErrorMessage("抱歉，调用模型时遇到错误。")
                            .setMaxIterationsMessage("我已达到最大迭代次数（agents.defaults.max_tool_iterations=" + bumped + "），但仍未完成任务。可尝试继续提高该值后重试。")
                            .setConcurrentTools(true)
                            .setWorkspace(workspace)
                            .setSessionKey(session.getKey())
                            .setContextWindowTokens(contextWindowTokens)
                            .setContextBlockLimit(contextBlockLimit)
                            .setCheckpointCallback(payload -> setRuntimeCheckpoint(session, payload));
                    runResult = runner.run(retrySpec);
                }
            }
        }

        // 获取最终回复内容
        String finalContent = runResult.getFinalContent();
        // 如果内容为空，使用默认空响应消息
        if (RuntimeUtils.isBlankText(finalContent)) {
            finalContent = RuntimeUtils.EMPTY_FINAL_RESPONSE_MESSAGE;
        }

        // 计算保存消息时需要跳过的数量（包括历史消息和提前持久化的用户消息）
        int saveSkip = 1 + history.size() + (userPersistedEarly ? 1 : 0);
        // 保存本轮对话产生的新消息到会话
        saveTurn(session, runResult.getMessages(), saveSkip);

        // 清除待处理用户轮次标记
        clearPendingUserTurn(session);
        // 清除运行时检查点
        clearRuntimeCheckpoint(session);
        // 成功完成一轮后清除中断原因
        session.getMetadata().remove("_last_interrupt_reason");
        // 保存会话状态
        sessionManager.save(session);

        // 记录回复日志
        log.info("回复给 {}:{}: {}", msg.getChannel(), msg.getSenderId(), abbreviate(finalContent, 120));

        // 构建出站消息
        OutboundMessage out = new OutboundMessage();
        out.setChannel(msg.getChannel());
        out.setChatId(msg.getChatId());
        out.setContent(finalContent);
        out.setMetadata(msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>());
        return out;
    }

    /**
     * 处理系统通道（system channel）的后台消息。
     *
     * @param msg 入站消息
     * @return 出站消息响应
     * @throws Exception 处理过程中可能抛出的异常
     */
    private OutboundMessage processSystemMessage(InboundMessage msg) throws Exception {
        // 解析 chatId 以提取通道和实际聊天ID
        String[] parts = msg.getChatId() != null && msg.getChatId().contains(":")
                ? msg.getChatId().split(":", 2)
                : new String[]{"cli", msg.getChatId()};

        String channel = parts[0];
        String chatId = parts[1];
        String key = channel + ":" + chatId;

        // 获取或创建会话
        Session session = sessionManager.getOrCreate(key);

        // 恢复运行时检查点
        restoreRuntimeCheckpoint(session);
        // 恢复待处理用户轮次
        restorePendingUserTurn(session);

        // 设置工具上下文
        setToolContext(channel, chatId, messageIdOf(msg));

        // 确定当前消息的角色：如果是子代理发送则视为助手，否则视为用户
        String currentRole = "subagent".equals(msg.getSenderId()) ? "assistant" : "user";

        // 获取会话历史
        List<Map<String, Object>> history = session.getHistory(historyWindowAsMessages());
        // 构建消息列表
        List<Map<String, Object>> messages = contextBuilder.buildMessages(
                history,
                msg.getContent(),
                null,
                channel,
                chatId,
                null,
                currentRole
        );

        // 构建 Agent 运行规格
        AgentRunSpec spec = new AgentRunSpec()
                .setInitialMessages(messages)
                .setTools(tools)
                .setModel(model)
                .setMaxIterations(maxIterations)
                .setMaxToolResultChars(maxToolResultChars)
                .setProviderRetryMode(providerRetryMode)
                .setMaxIterationsMessage("我已达到最大迭代次数（agents.defaults.max_tool_iterations=" + maxIterations + "），但仍未完成任务。可尝试提高该值（例如 12 或 16）后重试。")
                .setWorkspace(workspace)
                .setSessionKey(session.getKey())
                .setContextWindowTokens(contextWindowTokens)
                .setContextBlockLimit(contextBlockLimit);

        // 执行 Agent 运行
        AgentRunResult runResult = runner.run(spec);

        // 保存新生成的消息
        saveTurn(session, runResult.getMessages(), 1 + history.size());
        // 清除运行时检查点
        clearRuntimeCheckpoint(session);
        // 成功完成一轮后清除中断原因
        session.getMetadata().remove("_last_interrupt_reason");
        // 保存会话
        sessionManager.save(session);

        // 构建出站消息
        OutboundMessage out = new OutboundMessage();
        out.setChannel(channel);
        out.setChatId(chatId);
        // 如果内容为空，使用默认完成消息
        out.setContent(
                RuntimeUtils.isBlankText(runResult.getFinalContent())
                        ? "后台任务已完成。"
                        : runResult.getFinalContent()
        );
        out.setMetadata(new HashMap<>());
        return out;
    }

    /**
     * 直接调用 Agent 处理逻辑，不通过消息总线（Bus）。
     * 适用于需要同步获取结果的场景，如单元测试或内部 API 调用。
     *
     * @param content   用户输入的内容
     * @param sessionKey 会话键，用于标识和隔离不同的对话上下文
     * @param channel   通信渠道，例如 "cli", "web" 等
     * @param chatId    聊天 ID，用于区分同一渠道下的不同对话
     * @return 出站消息，包含 Agent 的回复内容
     * @throws Exception 处理过程中可能抛出的异常
     */
    public OutboundMessage processDirect(
            String content,
            String sessionKey,
            String channel,
            String chatId
    ) throws Exception {
        // 创建一个新的入站消息对象
        InboundMessage msg = new InboundMessage();
        // 设置通信渠道
        msg.setChannel(channel);
        // 设置发送者为 "user"，表示这是用户发起的请求
        msg.setSenderId("user");
        // 设置聊天 ID
        msg.setChatId(chatId);
        // 设置消息内容
        msg.setContent(content);
        // 初始化媒体列表为空列表
        msg.setMedia(new ArrayList<>());
        // 初始化元数据为空 Map
        msg.setMetadata(new HashMap<>());
        // 设置会话键覆盖值，确保使用指定的 sessionKey
        msg.setSessionKeyOverride(sessionKey);

        // 调用核心消息处理方法，并返回结果
        return processMessage(msg, effectiveSessionKey(msg));
    }

    /**
     * processDirect 的重载方法，提供默认的参数值。
     * 默认渠道为 "cli"，默认聊天 ID 为 "direct"。
     * 旨在简化调用，对齐项目中已有的调用点。
     *
     * @param content   用户输入的内容
     * @param sessionKey 会话键
     * @return 出站消息
     * @throws Exception 处理过程中可能抛出的异常
     */
    public OutboundMessage processDirect(String content, String sessionKey) throws Exception {
        // 调用全参数版本的 processDirect，传入默认值
        return processDirect(content, sessionKey, "cli", "direct");
    }

    // ---------------------------------------------------------------------
    // /stop 命令处理
    // ---------------------------------------------------------------------

    private OutboundMessage buildStopResponse(InboundMessage msg) {
        // 计算有效的会话键
        String sessionKey = effectiveSessionKey(msg);
        // 从活跃任务映射中移除该会话的任务列表，并获取它们
        List<Future<?>> tasks = activeTasks.remove(sessionKey);

        // 记录成功取消的任务数量
        int cancelled = 0;
        // 如果存在任务列表
        if (tasks != null) {
            // 遍历所有任务
            for (Future<?> task : tasks) {
                // 检查任务是否不为 null 且尚未完成
                if (task != null && !task.isDone()) {
                    // 尝试取消任务，true 表示允许中断正在运行的线程
                    if (task.cancel(true)) {
                        // 如果取消成功，计数器加一
                        cancelled++;
                    }
                }
            }
        }

        // 总取消数即为 cancelled
        int total = cancelled;
        if (total > 0) {
            markSessionInterrupted(sessionKey, "manual_stop");
        }

        // 构建出站响应消息
        OutboundMessage out = new OutboundMessage();
        // 设置响应渠道
        out.setChannel(msg.getChannel());
        // 设置响应聊天 ID
        out.setChatId(msg.getChatId());
        // 根据取消数量设置响应内容
        out.setContent(total > 0 ? "⏹ 已停止 " + total + " 个任务。" : "没有可停止的任务。");
        // 初始化元数据
        out.setMetadata(new HashMap<>());
        return out;
    }

    private OutboundMessage dispatchCommand(
            InboundMessage msg,
            Session session,
            String sessionKey,
            String raw,
            boolean priorityOnly
    ) {
        CommandRouter.CommandContext ctx = new CommandRouter.CommandContext(msg, session, sessionKey, raw, this);
        try {
            CompletableFuture<OutboundMessage> future = priorityOnly
                    ? commandRouter.dispatchPriority(ctx)
                    : commandRouter.dispatch(ctx);
            return future != null ? future.join() : null;
        } catch (Exception e) {
            log.warn("命令执行失败: {}", raw, e);
            return null;
        }
    }

    private CompletableFuture<OutboundMessage> cmdStop(CommandRouter.CommandContext ctx) {
        return CompletableFuture.completedFuture(buildStopResponse(ctx.getMsg()));
    }

    private CompletableFuture<OutboundMessage> cmdDisabled(CommandRouter.CommandContext ctx) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setContent("当前运行时未启用该命令。");
        out.setMetadata(new HashMap<>());
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdNew(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        session.clear();
        sessionManager.save(session);

        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setContent("已开始新的会话。");
        out.setMetadata(new HashMap<>());
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdHelp(CommandRouter.CommandContext ctx) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setContent("ricbot 命令：\n/new — 开始新对话\n/stop — 停止当前任务\n/help — 查看可用命令");
        out.setMetadata(new HashMap<>());
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdStatus(CommandRouter.CommandContext ctx) {
        Session session = ctx.getSession() != null ? ctx.getSession() : sessionManager.getOrCreate(ctx.getKey());
        int sessionMsgCount = session != null ? session.getMessages().size() : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("ricbot status\n");
        sb.append("model: ").append(model).append("\n");
        sb.append("workspace: ").append(workspace).append("\n");
        sb.append("session messages: ").append(sessionMsgCount).append("\n");

        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setContent(sb.toString());
        out.setMetadata(new HashMap<>());
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdDream(CommandRouter.CommandContext ctx) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setMetadata(new HashMap<>());

        if (dreamConfig == null || !dreamConfig.isEnabled()) {
            out.setContent("Dream 未启用。");
            return CompletableFuture.completedFuture(out);
        }

        boolean changed = dream.run();
        if (changed) {
            out.setContent("Dream 已完成一次整合，记忆文件已更新。");
        } else {
            out.setContent("Dream 本次没有检测到可更新内容。");
        }
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdDreamLog(CommandRouter.CommandContext ctx) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setMetadata(new HashMap<>());

        if (dreamConfig == null || !dreamConfig.isEnabled()) {
            out.setContent("Dream 未启用。");
            return CompletableFuture.completedFuture(out);
        }

        int maxEntries = 10;
        String args = trim(ctx.getArgs());
        if (!args.isBlank()) {
            maxEntries = Math.max(1, Math.min(50, parseInt(args, 10)));
        }

        var git = memoryStore.getGit();
        if (!git.isInitialized()) {
            out.setContent("Dream 日志仓库尚未初始化。先执行一次 /dream 后再查看日志。");
            return CompletableFuture.completedFuture(out);
        }

        var logs = git.log(maxEntries);
        if (logs.isEmpty()) {
            out.setContent("暂无 Dream 历史记录。");
            return CompletableFuture.completedFuture(out);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("dream log (latest ").append(logs.size()).append(")\n");
        sb.append("cursor: ").append(memoryStore.getLastDreamCursor())
                .append("/").append(memoryStore.getLastCursor()).append("\n\n");
        for (var c : logs) {
            sb.append(c.sha()).append("  ").append(c.timestamp()).append("  ").append(c.message()).append("\n");
        }
        out.setContent(sb.toString().trim());
        return CompletableFuture.completedFuture(out);
    }

    private CompletableFuture<OutboundMessage> cmdDreamRestore(CommandRouter.CommandContext ctx) {
        OutboundMessage out = new OutboundMessage();
        out.setChannel(ctx.getMsg().getChannel());
        out.setChatId(ctx.getMsg().getChatId());
        out.setMetadata(new HashMap<>());

        if (dreamConfig == null || !dreamConfig.isEnabled()) {
            out.setContent("Dream 未启用。");
            return CompletableFuture.completedFuture(out);
        }

        String args = trim(ctx.getArgs());
        if (args.isBlank()) {
            out.setContent("用法：/dream-restore <commit_sha>");
            return CompletableFuture.completedFuture(out);
        }

        String sha = args.split("\\s+")[0];
        var git = memoryStore.getGit();
        if (!git.isInitialized()) {
            out.setContent("Dream 日志仓库尚未初始化，无法 restore。请先执行 /dream。");
            return CompletableFuture.completedFuture(out);
        }

        var found = git.findCommit(sha, 200);
        if (found == null) {
            out.setContent("未找到对应提交：" + sha);
            return CompletableFuture.completedFuture(out);
        }

        String reverted = git.revert(found.sha());
        if (reverted == null) {
            out.setContent("restore 失败，请检查工作区状态后重试。");
            return CompletableFuture.completedFuture(out);
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        out.setContent("Dream 已恢复到 " + found.sha() + "，新提交: " + reverted + " (" + now + ")");
        return CompletableFuture.completedFuture(out);
    }

    // ---------------------------------------------------------------------
    // Hook / helpers (钩子与辅助方法)
    // ---------------------------------------------------------------------

    /**
     * 构建 Agent 运行循环所需的钩子（Hook）。
     * 该钩子负责处理流式输出、工具执行前后的状态更新以及最终内容的清理。
     *
     * @param msg 当前处理的入站消息
     * @return 配置好的 AgentHook 实例
     */
    private AgentHook buildLoopHook(InboundMessage msg) {
        AgentHook baseHook = new AgentHook(true) {
            // 用于累积流式输出的缓冲区
            private final StringBuilder streamBuf = new StringBuilder();

            /**
             * 判断当前请求是否需要流式输出。
             * 检查消息元数据中的 "_wants_stream" 标志。
             *
             * @return 如果需要流式输出则返回 true
             */
            @Override
            public boolean wantsStreaming() {
                // 从元数据中获取流式标志
                Object wants = msg.getMetadata() != null ? msg.getMetadata().get("_wants_stream") : null;
                // 只有当标志存在且为 Boolean true 时才返回 true
                return wants instanceof Boolean b && b;
            }

            /**
             * 处理流式输出的增量片段。
             * 去除思考过程标记后，将新增的有效内容发布到总线。
             *
             * @param context 钩子上下文
             * @param delta   本次接收到的文本增量
             */
            @Override
            public void onStream(AgentHookContext context, String delta) {
                // 获取缓冲区当前内容并去除思考标记
                String prevClean = HelperUtils.stripThink(streamBuf.toString());
                // 将新增量追加到缓冲区
                streamBuf.append(delta);
                // 获取更新后的缓冲区内容并去除思考标记
                String newClean = HelperUtils.stripThink(streamBuf.toString());

                // 计算本次新增的有效内容
                // 如果新长度大于等于旧长度，截取差异部分；否则直接使用新内容（防止异常情况）
                String incremental = newClean.length() >= prevClean.length()
                        ? newClean.substring(prevClean.length())
                        : newClean;

                // 如果存在非空的有效增量
                if (!incremental.isBlank()) {
                    // 构建出站消息
                    OutboundMessage out = new OutboundMessage();
                    out.setChannel(msg.getChannel());
                    out.setChatId(msg.getChatId());
                    out.setContent(incremental);

                    // 复制元数据并添加流式标记
                    Map<String, Object> meta = msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>();
                    meta.put("_stream_delta", true);
                    out.setMetadata(meta);

                    try {
                        // 发布增量消息
                        bus.publishOutbound(out);
                    } catch (Exception e) {
                        log.debug("发布流式增量失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), e);
                    }
                }
            }

            /**
             * 处理流式输出结束事件。
             * 发送一个空内容消息作为结束标记，并清空缓冲区。
             *
             * @param context 钩子上下文
             * @param resuming 是否处于恢复状态
             */
            @Override
            public void onStreamEnd(AgentHookContext context, boolean resuming) {
                // 构建结束标记消息
                OutboundMessage out = new OutboundMessage();
                out.setChannel(msg.getChannel());
                out.setChatId(msg.getChatId());
                out.setContent(""); // 空内容表示流结束

                // 设置元数据标记
                Map<String, Object> meta = msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>();
                meta.put("_stream_end", true);
                meta.put("_resuming", resuming);
                out.setMetadata(meta);

                try {
                    // 发布结束消息
                    bus.publishOutbound(out);
                } catch (Exception e) {
                    log.debug("发布流式结束标记失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), e);
                }

                // 清空流式缓冲区，为下一次请求做准备
                streamBuf.setLength(0);
            }

            /**
             * 在工具执行之前触发的回调。
             * 用于发布思考过程或工具调用提示作为进度更新。
             *
             * @param context 钩子上下文，包含当前的响应和工具调用信息
             */
            @Override
            public void beforeExecuteTools(AgentHookContext context) {
                // 如果不启用流式输出，且存在助手响应，则发布思考内容作为进度
                if (!wantsStreaming() && context.getResponse() != null) {
                    // 去除思考标记
                    String thought = stripThink(context.getResponse().getContent());
                    // 如果思考内容非空，发布进度消息
                    if (!thought.isBlank()) {
                        publishProgress(msg, thought, false);
                    }
                }

                // 生成工具调用提示字符串
                String toolHint = stripThink(toolHint(context.getToolCalls()));
                // 如果提示非空，发布进度消息，标记为工具提示
                if (!toolHint.isBlank()) {
                    publishProgress(msg, toolHint, true);
                }

                // 设置工具执行的上下文信息
                setToolContext(msg.getChannel(), msg.getChatId(), messageIdOf(msg));
            }

            /**
             * 每次 LLM 迭代结束后触发的回调。
             * 用于记录 Token 使用情况等调试信息。
             *
             * @param context 钩子上下文，包含用量统计
             */
            @Override
            public void afterIteration(AgentHookContext context) {
                // 获取 Token 用量统计
                Map<String, Integer> usage = context.getUsage();
                // 如果用量信息存在且非空
                if (usage != null && !usage.isEmpty()) {
                    // 记录调试日志，显示提示词、完成和总 Token 数
                    log.debug(
                            "LLM 用量: 提示词={} 完成={} 总计={}",
                            usage.getOrDefault("prompt_tokens", 0),
                            usage.getOrDefault("completion_tokens", 0),
                            usage.getOrDefault("total_tokens", 0)
                    );
                }
            }

            /**
             * 在返回最终内容之前进行清理。
             * 主要作用是去除内容中的思考过程标记。
             *
             * @param context 钩子上下文
             * @param content 原始内容
             * @return 清理后的内容
             */
            @Override
            public String finalizeContent(AgentHookContext context, String content) {
                // 去除思考标记并返回
                return stripThink(content);
            }
        };

        if (extraHooks == null || extraHooks.isEmpty()) {
            return baseHook;
        }

        List<AgentHook> hooks = new ArrayList<>();
        hooks.add(baseHook);
        for (AgentHook hook : extraHooks) {
            if (hook != null) {
                hooks.add(hook);
            }
        }
        return hooks.size() == 1 ? baseHook : new AgentHook.CompositeHook(hooks);
    }

    /**
     * 发布进度消息到总线。
     *
     * @param msg      原始入站消息，用于获取通道和会话ID
     * @param content  进度内容
     * @param toolHint 是否为工具调用提示
     */
    private void publishProgress(InboundMessage msg, String content, boolean toolHint) {
        // 创建出站消息对象
        OutboundMessage out = new OutboundMessage();
        // 设置通道
        out.setChannel(msg.getChannel());
        // 设置聊天ID
        out.setChatId(msg.getChatId());
        // 设置内容
        out.setContent(content);

        // 复制元数据，如果为空则创建新的 HashMap
        Map<String, Object> meta = msg.getMetadata() != null ? new HashMap<>(msg.getMetadata()) : new HashMap<>();
        // 标记为进度消息
        meta.put("_progress", true);
        // 标记是否为工具提示
        meta.put("_tool_hint", toolHint);
        // 设置元数据
        out.setMetadata(meta);

        try {
            // 发布出站消息
            bus.publishOutbound(out);
        } catch (Exception e) {
            log.debug("发布进度消息失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), e);
        }
    }

    /**
     * 计算有效的会话键。
     *
     * @param msg 入站消息
     * @return 会话键
     */
    private String effectiveSessionKey(InboundMessage msg) {
        // 如果启用统一会话且消息中没有覆盖会话键或会话键为空
        if (unifiedSession && (msg.getSessionKeyOverride() == null || msg.getSessionKeyOverride().isBlank())) {
            // 返回统一会话键
            return UNIFIED_SESSION_KEY;
        }
        // 否则返回消息中的会话键
        return msg.getSessionKey();
    }

    /**
     * 设置工具上下文（当前为无操作）。
     *
     * @param channel   通道
     * @param chatId    聊天ID
     * @param messageId 消息ID
     */
    private void setToolContext(String channel, String chatId, String messageId) {
        for (String toolName : tools.toolNames()) {
            var tool = tools.get(toolName);
            if (tool == null) {
                continue;
            }

            try {
                if (tool instanceof CronTool cronTool) {
                    cronTool.setContext(channel, chatId);
                } else if (tool instanceof SpawnTool spawnTool) {
                    spawnTool.setContext(channel, chatId);
                }

                try {
                    var m3 = tool.getClass().getMethod("setContext", String.class, String.class, String.class);
                    m3.invoke(tool, channel, chatId, messageId);
                    continue;
                } catch (NoSuchMethodException ignored) {
                    // fall through
                }

                try {
                    var m2 = tool.getClass().getMethod("setContext", String.class, String.class);
                    m2.invoke(tool, channel, chatId);
                } catch (NoSuchMethodException ignored) {
                    // 当前工具不支持上下文注入
                }
            } catch (Exception e) {
                log.debug("注入工具上下文失败: tool={}", toolName, e);
            }
        }
    }

    /**
     * 去除思考过程标记。
     *
     * @param text 原始文本
     * @return 处理后的文本
     */
    private String stripThink(String text) {
        // 使用 HelperUtils 去除思考标记，如果文本为空则传入空字符串
        return HelperUtils.stripThink(text != null ? text : "");
    }

    /**
     * 生成工具调用提示。
     *
     * @param toolCalls 工具调用请求列表
     * @return 格式化后的工具提示字符串
     */
    private String toolHint(List<ToolCallRequest> toolCalls) {
        // 使用 ToolHintFormatter 格式化提示
        return ToolHintFormatter.formatToolHints(toolCalls);
    }

    // ---------------------------------------------------------------------
    // Session persistence helpers
    // ---------------------------------------------------------------------

    /**
     * 保存一轮对话消息到会话中。
     *
     * @param session  会话对象
     * @param messages 消息列表
     * @param skip     跳过前 N 条消息
     */
    private void saveTurn(Session session, List<Map<String, Object>> messages, int skip) {
        // 如果消息列表为空或为 null，直接返回
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 计算起始索引，确保不越界
        int start = Math.max(0, Math.min(skip, messages.size()));

        // 遍历需要保存的消息
        for (int i = start; i < messages.size(); i++) {
            Map<String, Object> entry = normalizeTurnEntry(messages.get(i));
            if (entry == null) {
                continue;
            }
            // 将消息添加到会话中
            session.getMessages().add(entry);
        }

        // 更新会话的最后更新时间
        session.setUpdatedAt(Instant.now());
    }

    private Map<String, Object> normalizeTurnEntry(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }

        Map<String, Object> entry = new LinkedHashMap<>(raw);
        Object role = entry.get("role");
        Object content = entry.get("content");

        if ("assistant".equals(role) && (content == null || String.valueOf(content).isBlank()) && !entry.containsKey("tool_calls")) {
            return null;
        }

        if ("tool".equals(role) && content instanceof String s && s.length() > maxToolResultChars) {
            entry.put("content", HelperUtils.truncateText(s, maxToolResultChars));
            content = entry.get("content");
        }

        if ("user".equals(role) && content instanceof String s && s.startsWith(ContextBuilder.RUNTIME_CONTEXT_TAG)) {
            String endMarker = ContextBuilder.RUNTIME_CONTEXT_END;
            int endPos = s.indexOf(endMarker);
            if (endPos >= 0) {
                String after = s.substring(endPos + endMarker.length()).stripLeading();
                if (after.isBlank()) {
                    return null;
                }
                entry.put("content", after);
            }
        }

        entry.putIfAbsent("timestamp", Instant.now().toString());
        return entry;
    }

    /**
     * 设置运行时检查点。
     *
     * @param session 会话对象
     * @param payload 检查点数据
     */
    private void setRuntimeCheckpoint(Session session, Map<String, Object> payload) {
        // 将检查点数据存入会话元数据
        session.getMetadata().put(RUNTIME_CHECKPOINT_KEY, payload);
        // 保存会话
        sessionManager.save(session);
    }

    /**
     * 清除运行时检查点。
     *
     * @param session 会话对象
     */
    private void clearRuntimeCheckpoint(Session session) {
        // 从会话元数据中移除检查点
        session.getMetadata().remove(RUNTIME_CHECKPOINT_KEY);
    }

    /**
     * 标记待处理的用户轮次。
     *
     * @param session 会话对象
     */
    private void markPendingUserTurn(Session session) {
        // 在会话元数据中标记
        session.getMetadata().put(PENDING_USER_TURN_KEY, true);
    }

    /**
     * 清除待处理的用户轮次标记。
     *
     * @param session 会话对象
     */
    private void clearPendingUserTurn(Session session) {
        // 从会话元数据中移除标记
        session.getMetadata().remove(PENDING_USER_TURN_KEY);
    }

    /**
     * 恢复运行时检查点。
     *
     * @param session 会话对象
     */
    @SuppressWarnings("unchecked")
    private void restoreRuntimeCheckpoint(Session session) {
        // 获取原始检查点数据
        Object raw = session.getMetadata().get(RUNTIME_CHECKPOINT_KEY);
        // 如果数据不是 Map 类型，直接返回
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return;
        }

        // 转换为 Map
        Map<String, Object> checkpoint = (Map<String, Object>) rawMap;
        // 获取助手消息
        Object assistantMessage = checkpoint.get("assistant_message");
        // 获取已完成的工具结果
        Object completedToolResults = checkpoint.get("completed_tool_results");
        // 获取待处理的工具调用
        Object pendingToolCalls = checkpoint.get("pending_tool_calls");

        // 如果存在助手消息，添加到会话历史
        if (assistantMessage instanceof Map<?, ?> a) {
            session.getMessages().add(new LinkedHashMap<>((Map<String, Object>) a));
        }

        // 如果存在已完成的工具结果，逐个添加到会话历史
        if (completedToolResults instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    session.getMessages().add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }

        // 如果存在待处理的工具调用，生成错误消息并添加到会话历史
        if (pendingToolCalls instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> toolCall) {
                    // 获取函数信息
                    Map<String, Object> fn = toolCall.get("function") instanceof Map<?, ?> f
                            ? (Map<String, Object>) f
                            : new LinkedHashMap<>();

                    // 创建工具响应消息
                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolCall.get("id"));
                    toolMsg.put("name", fn.getOrDefault("name", "tool"));
                    toolMsg.put("content", interruptedToolMessage(checkpoint, session));
                    toolMsg.put("timestamp", Instant.now().toString());
                    session.getMessages().add(toolMsg);
                }
            }
        }

        // 清除待处理用户轮次标记
        clearPendingUserTurn(session);
        // 清除运行时检查点
        clearRuntimeCheckpoint(session);
        // 保存会话
        sessionManager.save(session);
    }

    private String interruptedToolMessage(Map<String, Object> checkpoint, Session session) {
        String reason = null;
        Object cpReason = checkpoint.get("interruption_reason");
        if (cpReason instanceof String s && !s.isBlank()) {
            reason = s;
        }
        if (reason == null) {
            Object sessionReason = session.getMetadata().get("_last_interrupt_reason");
            if (sessionReason instanceof String s && !s.isBlank()) {
                reason = s;
            }
        }
        if (reason == null) {
            reason = "interrupted";
        }

        return switch (reason) {
            case "manual_stop" -> "错误：任务在该工具执行完成前被手动停止。";
            case "shutdown" -> "错误：任务在该工具执行完成前因服务关闭而中断。";
            case "timeout" -> "错误：任务在该工具执行完成前因超时而中断。";
            default -> "错误：任务在该工具执行完成前被中断。";
        };
    }

    /**
     * 恢复待处理的用户轮次。
     *
     * @param session 会话对象
     */
    private void restorePendingUserTurn(Session session) {
        // 获取标记
        Object flag = session.getMetadata().get(PENDING_USER_TURN_KEY);
        // 如果标记不存在或不为 true，直接返回
        if (!(flag instanceof Boolean b) || !b) {
            return;
        }

        // 获取会话消息列表
        List<Map<String, Object>> messages = session.getMessages();
        // 如果消息列表不为空
        if (!messages.isEmpty()) {
            // 获取最后一条消息
            Map<String, Object> last = messages.get(messages.size() - 1);
            // 如果最后一条消息是用户消息
            if ("user".equals(last.get("role"))) {
                // 创建一条助手错误消息
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", "错误：任务在生成回复前被中断。");
                assistant.put("timestamp", Instant.now().toString());
                // 添加到消息列表
                messages.add(assistant);
                // 更新会话时间
                session.setUpdatedAt(Instant.now());
            }
        }

        // 清除待处理用户轮次标记
        clearPendingUserTurn(session);
        // 保存会话
        sessionManager.save(session);
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    /**
     * 计算历史消息窗口大小。
     *
     * @return 最大历史消息数
     */
    private int historyWindowAsMessages() {
        // 根据 contextWindowTokens 近似计算最大历史消息数
        // 设置保守上限，避免历史无限增长
        if (contextWindowTokens <= 0) {
            return 100;
        }
        // 限制在 20 到 200 之间
        return Math.min(200, Math.max(20, contextWindowTokens / 500));
    }

    /**
     * 从消息元数据中获取消息 ID。
     *
     * @param msg 入站消息
     * @return 消息 ID，如果不存在则返回 null
     */
    private String messageIdOf(InboundMessage msg) {
        // 如果元数据为空，返回 null
        if (msg.getMetadata() == null) {
            return null;
        }
        // 获取 message_id
        Object v = msg.getMetadata().get("message_id");
        // 如果存在则转为字符串，否则返回 null
        return v != null ? String.valueOf(v) : null;
    }

    /**
     * 去除字符串首尾空白。
     *
     * @param s 原始字符串
     * @return 处理后的字符串
     */
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 安全地解析整数。
     *
     * @param s   字符串
     * @param def 默认值
     * @return 解析后的整数或默认值
     */
    private static int parseInt(String s, int def) {
        try {
            return s != null ? Integer.parseInt(s) : def;
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 缩写字符串。
     *
     * @param s   原始字符串
     * @param max 最大长度
     * @return 缩写后的字符串
     */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void markSessionInterrupted(String sessionKey, String reason) {
        try {
            Session session = sessionManager.getOrCreate(sessionKey);
            session.getMetadata().put("_last_interrupt_reason", reason);
            sessionManager.save(session);
        } catch (Exception e) {
            log.debug("记录会话中断原因失败: sessionKey={}, reason={}", sessionKey, reason, e);
        }
    }

    private void runAutoCompactSweep() {
        try {
            autoCompact.checkExpired(executor, activeTasks.keySet());
        } catch (Exception e) {
            log.warn("自动归档扫描失败", e);
        }
    }

    // ---------------------------------------------------------------------
    // extra hooks
    // ---------------------------------------------------------------------

    /**
     * 获取额外的钩子列表。
     *
     * @return 钩子列表
     */
    public List<AgentHook> getExtraHooks() {
        return extraHooks;
    }

    /**
     * 设置额外的钩子列表。
     *
     * @param extraHooks 钩子列表
     */
    public void setExtraHooks(List<AgentHook> extraHooks) {
        this.extraHooks = extraHooks != null ? extraHooks : new ArrayList<>();
    }
}
