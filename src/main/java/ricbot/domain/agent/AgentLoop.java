package ricbot.domain.agent;

import ricbot.domain.agent.dto.*;
import ricbot.domain.agent.interfacep.AgentInvocationRuntime;
import ricbot.tool.pack.RuntimeToolPacks;
import ricbot.domain.memory.MemoryStore;
import ricbot.domain.config.ProviderCapability;
import ricbot.domain.config.ProviderCapabilityResolver;
import ricbot.domain.config.ModelCard;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.trace.TraceStore;
import ricbot.tool.api.ToolRegistry;
import ricbot.integration.command.CommandRouter;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.InboundMessages;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.domain.message.OutboundMessages;
import ricbot.infra.config.Config;
import ricbot.infra.telemetry.OpenTelemetryRuntime;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.domain.runtime.DurableAgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 主循环：ricbot 的核心调度引擎。
 */
public class AgentLoop implements AutoCloseable {

    /** 日志记录器，用于记录 AgentLoop 类的运行日志 */
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    /** 入站消息轮询超时 */
    private static final int INBOUND_POLL_TIMEOUT_MS = 500;

    /** 统一会话的默认键值，当启用统一会话模式时使用 */
    public static final String UNIFIED_SESSION_KEY = "unified:default";

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

    /** 执行工具配置 */
    private final Config.ExecToolConfig execConfig;
    /** 是否限制操作仅在工作空间内 */
    private final boolean restrictToWorkspace;
    /** 是否启用统一会话模式 */
    private final boolean unifiedSession;
    /** Provider 重试模式（透传到运行规格） */
    private final String providerRetryMode;
    /** 静态/启发式 Provider capability，用于运行时保守降级。 */
    private ProviderCapability providerCapability;
    /** 会话自动归档 TTL（分钟），0 表示禁用 */
    private final int sessionTtlMinutes;
    private final Config.BudgetConfig budgetConfig;
    private final Config.ContextOffloadConfig offloadConfig;

    /** 上下文构建器，用于构建发送给 LLM 的消息上下文 */
    private final ContextBuilder contextBuilder;
    /** 会话管理器，负责会话的创建、加载和保存 */
    private final SessionManager sessionManager;
    private final OpenTelemetryRuntime telemetryRuntime;
    /** 记忆存储，用于长期记忆管理 */
    private final MemoryStore memoryStore;
    private final ApprovalService approvalService;
    private final TraceStore traceStore;
    /** 工具注册表，管理所有可用工具 */
    private final ToolRegistry tools;
    /** Agent 运行器，负责执行具体的 LLM 交互循环 */
    private final AgentInvocationRuntime runner;
    private final DurableAgentRuntime agentRuntime;
    /** Hook 工厂，负责组合请求级 Hook */
    private final AgentHookFactory hookFactory;
    /** 会话准备服务 */
    private final SessionPreparationService sessionPreparationService;
    /** 上下文组装服务 */
    private final AgentContextService agentContextService;
    /** 执行服务 */
    private final AgentExecutionService agentExecutionService;
    /** 持久化服务 */
    private final SessionPersistenceService sessionPersistenceService;
    /** 命令路由器，统一 slash 命令入口 */
    private final CommandRouter commandRouter;
    /** 命令处理器 */
    private final AgentCommands agentCommands;

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
     * @param execConfig         执行工具配置
     * @param restrictToWorkspace 是否限制工作空间
     * @param sessionManager     会话管理器
     * @param timezone           时区
     * @param unifiedSession     是否统一会话
     * @param sessionTtlMinutes  会话 TTL（分钟，<=0 表示禁用）
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
            Config.ExecToolConfig execConfig,
            boolean restrictToWorkspace,
            SessionManager sessionManager,
            String timezone,
            boolean unifiedSession,
            int sessionTtlMinutes
    ) {
        this(bus, provider, workspace, model, maxIterations, contextWindowTokens, contextBlockLimit,
                maxToolResultChars, providerRetryMode, execConfig,
                restrictToWorkspace, sessionManager, timezone, unifiedSession,
                sessionTtlMinutes, null);
    }

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
            Config.ExecToolConfig execConfig,
            boolean restrictToWorkspace,
            SessionManager sessionManager,
            String timezone,
            boolean unifiedSession,
            int sessionTtlMinutes,
            AgentRuntimeCore suppliedCore
    ) {
        this(bus, provider, workspace, model, maxIterations, contextWindowTokens, contextBlockLimit,
                maxToolResultChars, providerRetryMode, execConfig, restrictToWorkspace, sessionManager,
                timezone, unifiedSession, sessionTtlMinutes, suppliedCore,
                new Config.BudgetConfig(), new Config.ContextOffloadConfig());
    }

    public AgentLoop(
            MessageBus bus, LLMProvider provider, Path workspace, String model,
            Integer maxIterations, Integer contextWindowTokens, Integer contextBlockLimit,
            Integer maxToolResultChars, String providerRetryMode, Config.ExecToolConfig execConfig,
            boolean restrictToWorkspace, SessionManager sessionManager, String timezone,
            boolean unifiedSession, int sessionTtlMinutes, AgentRuntimeCore suppliedCore,
            Config.BudgetConfig budgetConfig, Config.ContextOffloadConfig offloadConfig
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

        this.execConfig = execConfig != null ? execConfig : new Config.ExecToolConfig();
        this.restrictToWorkspace = restrictToWorkspace;
        this.unifiedSession = unifiedSession;
        this.providerRetryMode = providerRetryMode != null && !providerRetryMode.isBlank()
                ? providerRetryMode
                : defaults.getProviderRetryMode();
        this.sessionTtlMinutes = sessionTtlMinutes > 0 ? sessionTtlMinutes : defaults.getSessionTtlMinutes();
        this.budgetConfig = budgetConfig != null ? budgetConfig : new Config.BudgetConfig();
        this.offloadConfig = offloadConfig != null ? offloadConfig : new Config.ContextOffloadConfig();
        this.providerCapability = new ProviderCapabilityResolver().resolve(
                null,
                this.model,
                this.provider != null ? this.provider.getApiBase() : null,
                this.contextWindowTokens,
                defaults.getMaxTokens()
        );

        AgentRuntimeCore core = suppliedCore != null ? suppliedCore : AgentRuntimeCoreFactory.create(
                this.provider, this.workspace, this.model, this.contextWindowTokens,
                this.maxToolResultChars, this.execConfig, this.restrictToWorkspace,
                sessionManager, timezone, this.sessionTtlMinutes);
        this.contextBuilder = core.contextBuilder();
        this.sessionManager = core.persistence().sessionManager();
        this.telemetryRuntime = core.telemetryRuntime();
        this.traceStore = core.traceStore();
        this.memoryStore = core.memoryStore();
        this.approvalService = core.approvalService();
        this.tools = core.tools();
        this.runner = core.runner();
        this.agentRuntime = core.agentRuntime();
        this.hookFactory = new AgentHookFactory(this.bus);
        this.sessionPreparationService = new SessionPreparationService(this.sessionManager);
        ContextSelectionService contextSelectionService = new ContextSelectionService(
                this.memoryStore,
                new ToolTraceSummarizer(),
                this.contextWindowTokens,
                this.traceStore
        );
        this.agentContextService = new AgentContextService(
                this.workspace,
                this.contextBuilder,
                this.hookFactory,
                contextSelectionService
        );
        this.agentExecutionService = new AgentExecutionService(
                this.runner,
                this.tools,
                this.workspace,
                this.model,
                this.maxIterations,
                this.maxToolResultChars,
                this.providerRetryMode,
                this.contextWindowTokens,
                this.contextBlockLimit,
                this.providerCapability,
                this.approvalService,
                budgetPolicy(this.budgetConfig, ""), this.offloadConfig, timezone
        );
        this.sessionPersistenceService = new SessionPersistenceService(this.sessionManager, this.maxToolResultChars);
        this.commandRouter = new CommandRouter();
        this.agentCommands = new AgentCommands(
                this.sessionManager,
                this.model,
                this.workspace,
                this::effectiveSessionKey,
                activeTasks::remove,
                this::markSessionInterrupted,
                this.approvalService,
                this.tools, this.provider,
                core.agentRuntime()
        );

        // 初始化并发控制
        int maxConcurrent = parseInt(System.getenv("RICBOT_MAX_CONCURRENT_REQUESTS"), 3);
        this.concurrencyGate = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;

        // 初始化线程池
        this.executor = createWorkerExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        // 注册默认工具
        if (suppliedCore == null) {
            RuntimeToolPacks.registerAll(this.tools, this.workspace, this.restrictToWorkspace,
                    this.execConfig, this.approvalService);
        }
        registerCommandRoutes();
    }

    private static ricbot.domain.agent.budget.BudgetPolicy budgetPolicy(Config.BudgetConfig config, String parentRunId) {
        Config.BudgetConfig value = config != null ? config : new Config.BudgetConfig();
        return new ricbot.domain.agent.budget.BudgetPolicy(value.getMaxTotalTokens(), value.getMaxCostMicrousd(),
                value.getMaxActiveSeconds(), value.getMaxToolCalls(), value.getFinalizationTokens(), parentRunId);
    }

    public void setProviderCapability(ProviderCapability providerCapability) {
        if (providerCapability != null) {
            this.providerCapability = providerCapability;
        }
    }

    public void setRuntimeConfigs(Config.ContextManagementConfig contextManagement,
                                  Config.ToolRuntimeConfig toolRuntime) {
        this.agentExecutionService.setRuntimeConfigs(contextManagement, toolRuntime);
    }

    public void setModelPricing(ModelCard.Pricing pricing) {
        this.agentExecutionService.setModelPricing(pricing);
    }

    private static ExecutorService createWorkerExecutor() {
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        return new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(512),
                r -> {
                    Thread t = new Thread(r, "agent-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private void registerCommandRoutes() {
        agentCommands.register(commandRouter);
    }

    // ---------------------------------------------------------------------
    // Main loop
    // ---------------------------------------------------------------------

    /**
     * 启动 Agent 主循环。
     * <p>
     * 该方法首先确保后台服务已启动，
     * 然后使用 CAS 操作保证 Agent 主循环线程只被创建和启动一次。
     */
    public void start() {
        // 启动必要的后台服务，内部有幂等性保护
        startBackgroundIfNeeded();
        
        // 尝试将 loopThreadStarted 标志从 false 设置为 true
        // 如果设置成功，说明这是第一次调用 start()，需要创建并启动主循环线程
        if (loopThreadStarted.compareAndSet(false, true)) {
            // 创建名为 "agent-loop" 的新线程，执行 run() 方法，并立即启动
            new Thread(this::run, "agent-loop").start();
        }
    }

    /**
     * Agent 主循环运行逻辑。
     */
    public void run() {
        // 标记 Agent 循环为运行状态 - 设置 running 标志为 true，表示 Agent 开始运行
        this.running = true;
        // 记录启动日志 - 输出一条 INFO 级别的日志，表明 Agent 循环已启动
        log.info("Agent 循环已启动");

        // 主循环：持续监听 inbound 消息 - 这是一个无限循环，只要 running 为 true 就会一直运行
        while (running) {
            try {
                // 阻塞最多 500ms 等待入站消息，若超时返回 null - 从消息总线消费消息，最多等待 500 毫秒
                InboundMessage msg = bus.consumeInbound(INBOUND_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    // 无消息则继续下一轮循环 - 如果没有收到消息（超时），则跳过本次循环，继续等待下一条消息
                    continue;
                }

                // 获取消息内容并去除首尾空白 - 调用 trim 方法移除消息内容开头和结尾的空白字符
                String raw = trim(msg.getContent());

                // /stop 优先处理：立即停止当前会话任务 - 检查消息是否为以 "/" 开头的优先级命令
                if (raw.startsWith("/") && commandRouter.isPriority(raw)) {
                    // 分发优先级命令并获取响应 - 调用 dispatchCommand 方法处理优先级命令
                    OutboundMessage priorityOut = dispatchCommand(msg, null, effectiveSessionKey(msg), raw, true);
                    if (priorityOut != null) {
                        // 如果有响应消息，则发布到出站消息总线 - 将命令执行结果发送出去
                        bus.publishOutbound(priorityOut);
                    }
                    // 跳过正常消息处理流程 - 优先级命令处理完后直接进入下一次循环
                    continue;
                }

                submitDispatchTask(msg);

            } catch (InterruptedException e) {
                // 线程被中断，恢复中断状态并退出循环 - 如果主循环线程被中断，恢复中断状态并跳出循环
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 捕获其他未预期异常，记录日志并继续运行 - 出现非中断异常时，记录错误但不停止整个循环
                log.error("Agent 循环出错", e);
            }
        }
    }

    /**
     * 按需启动后台服务。
     * 使用 CAS 确保该方法只被执行一次，避免重复启动后台任务。
     */
    private void startBackgroundIfNeeded() {
        // 尝试将 backgroundStarted 从 false 设置为 true，如果设置失败（说明已经启动过），则直接返回
        if (!backgroundStarted.compareAndSet(false, true)) {
            return;
        }
        // 标记 Agent 循环为运行状态
        this.running = true;
    }

    public void stop() {
        if (!backgroundStarted.compareAndSet(true, false) && executor.isShutdown()) return;
        this.running = false;
        for (String sessionKey : activeTasks.keySet()) {
            markSessionInterrupted(sessionKey, "shutdown");
        }
        executor.shutdownNow();
        scheduler.shutdownNow();
        agentRuntime.close();
        telemetryRuntime.close();
        ricbot.app.bootstrap.RuntimeStoreRegistry.release(workspace);
        log.info("Agent 循环正在停止");
    }

    @Override public void close() { stop(); }

    public SessionManager getSessions() { return sessionManager; }
    public ToolRegistry getTools() { return tools; }

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
        try {
            withSessionLock(sessionKey, () -> {
                OutboundMessage response = processMessage(msg, sessionKey);
                if (response != null) {
                    bus.publishOutbound(response);
                } else if ("cli".equals(msg.getChannel())) {
                    bus.publishOutbound(reply(msg, ""));
                }
                return null;
            });
        } catch (Exception e) {
            log.error("处理会话 {} 的消息时出错", sessionKey, e);
            publishProcessingError(msg);
        } finally {
            cleanupCompletedTasks(sessionKey);
        }
    }

    /**
     * 处理单条用户消息。
     *
     * @param msg          入站消息，包含用户发送的原始内容、渠道、发送者等信息
     * @param sessionKey   会话键，用于标识和隔离不同的对话上下文
     * @return 出站消息响应，包含 Agent 生成的回复内容
     * @throws Exception 处理过程中可能抛出的异常，如 LLM 调用失败、持久化错误等
     */
    private OutboundMessage processMessage(InboundMessage msg, String sessionKey) throws Exception {
        // 检查消息渠道是否为系统通道（system），如果是则委托给系统消息处理器
        if ("system".equals(msg.getChannel())) {
            return processSystemMessage(msg);
        }

        // 生成消息内容的预览字符串，用于日志记录，限制长度为 80 字符以避免日志过长
        String preview = msg.getContent() != null && msg.getContent().length() > 80
                ? msg.getContent().substring(0, 80) + "..."
                : String.valueOf(msg.getContent());
        // 记录开始处理消息的日志，包含渠道、发送者和内容预览
        log.info("处理来自 {}:{} 的消息: {}", msg.getChannel(), msg.getSenderId(), preview);

        // 准备交互式会话上下文：加载历史、应用钩子、检查命令等
        // 传入一个回调函数，用于在准备阶段遇到命令时进行分发处理
        PreparedSessionContext prepared = sessionPreparationService.prepareInteractiveTurn(
                msg,
                sessionKey,
                (commandMessage, session, key, raw) -> dispatchCommand(commandMessage, session, key, raw, false)
        );
        // 如果准备阶段产生了立即响应（例如命中了快捷命令或拦截逻辑），直接返回该响应，不再继续后续流程
        if (prepared.immediateResponse() != null) {
            return prepared.immediateResponse();
        }

        // 如果需要，将用户的当前轮次消息持久化到会话存储中，并返回更新后的上下文
        PreparedSessionContext persisted = sessionPreparationService.persistUserTurnIfNeeded(prepared, msg);
        
        // 构建 Agent 请求上下文：组装发送给 LLM 的完整提示词、工具列表、历史消息窗口等
        AgentRequestContext request = agentContextService.buildInteractiveRequest(
                msg,
                persisted,
                historyWindowAsMessages() // 计算历史消息窗口大小
        );
        
        // 执行交互式 Agent 循环：调用 LLM，处理工具调用，直到得出最终结论或达到最大迭代次数
        // 传入一个回调函数，用于在执行过程中保存运行时检查点（如任务状态）
        ExecutionOutcome outcome = agentExecutionService.executeInteractive(request);
        
        // 持久化交互式轮次的结果：将 Agent 的回复、工具调用记录等保存到会话存储中
        PersistenceResult persistence = sessionPersistenceService.persistInteractiveTurn(request, outcome);

        // 记录回复日志，包含渠道、发送者和回复内容的缩写（限制 120 字符）
        log.info("回复给 {}:{}: {}", msg.getChannel(), msg.getSenderId(), abbreviate(outcome.finalContent(), 120));
        
        // 从持久化结果中提取并返回最终的出站消息对象
        return persistence.outboundMessage();
    }

    /**
     * 处理系统通道（system channel）的后台消息。
     * 此类消息由内部运行控制触发，不直接来自用户交互。
     *
     * @param msg 入站消息，包含触发系统处理的相关信息
     * @return 出站消息响应，包含处理结果或状态更新
     * @throws Exception 处理过程中可能抛出的异常，如会话准备失败、LLM 调用错误等
     */
    private OutboundMessage processSystemMessage(InboundMessage msg) throws Exception {
        SystemTarget target = parseSystemTarget(msg.getChatId());
        String channel = target.channel();
        String chatId = target.chatId();
        // 构建唯一的会话键，格式为 "channel:chatId"，用于定位和管理会话状态
        String key = channel + ":" + chatId;

        // 确定当前消息在对话中的角色
        String currentRole = "user";

        // 准备系统轮次的会话上下文
        // 加载相关的历史记忆、会话状态，并为系统消息的处理做预处理
        PreparedSessionContext prepared = sessionPreparationService.prepareSystemTurn(key);

        // 构建系统请求上下文
        // 组装发送给 LLM 的必要信息，包括消息内容、会话上下文、渠道信息、角色以及历史消息窗口大小
        AgentRequestContext request = agentContextService.buildSystemRequest(
                msg,
                prepared,
                channel,
                chatId,
                currentRole,
                historyWindowAsMessages() // 计算并传入历史消息窗口的最大条数
        );

        // 执行系统级的 Agent 逻辑
        // 调用 LLM 进行处理，可能涉及工具调用或状态更新，但不一定产生直接的用户可见回复
        ExecutionOutcome outcome = agentExecutionService.executeSystem(request);

        // 持久化系统轮次的处理结果
        // 将 LLM 的输出、状态变更等保存回会话存储，并生成最终的出站消息对象
        PersistenceResult persistence = sessionPersistenceService.persistSystemTurn(
                msg,
                channel,
                chatId,
                request,
                outcome
        );
        return persistence.outboundMessage();
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
        return processDirect(content, sessionKey, channel, chatId, Map.of());
    }

    /**
     * 直接调用 Agent 处理逻辑，支持自定义元数据。
     * 该方法绕过消息总线，同步执行消息处理流程，适用于测试或内部直接调用场景。
     *
     * @param content      用户输入的内容
     * @param sessionKey   会话键，用于标识和隔离不同的对话上下文
     * @param channel      通信渠道，例如 "cli", "web" 等
     * @param chatId       聊天 ID，用于区分同一渠道下的不同对话
     * @param metadata     附加的元数据映射，可包含额外的上下文信息
     * @return 出站消息，包含 Agent 的回复内容
     * @throws Exception 处理过程中可能抛出的异常，如 LLM 调用失败、持久化错误等
     */
    public OutboundMessage processDirect(
            String content,
            String sessionKey,
            String channel,
            String chatId,
            Map<String, Object> metadata
    ) throws Exception {
        // 创建入站消息对象，封装用户输入及上下文信息
        InboundMessage msg = newDirectMessage(content, sessionKey, channel, chatId, metadata);

        // 计算有效的会话键，处理统一会话模式或覆盖逻辑
        String effectiveKey = effectiveSessionKey(msg);

        return withSessionLock(effectiveKey, () -> processMessage(msg, effectiveKey));
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

    /**
     * 分发并执行命令。
     *
     * @param msg          入站消息，包含用户请求的原始信息
     * @param session      当前会话对象，可能为 null
     * @param sessionKey   会话键，用于标识特定会话
     * @param raw          原始命令字符串，例如 "/stop" 或 "/help"
     * @param priorityOnly 是否仅分发高优先级命令
     * @return 命令执行后的出站消息，如果执行失败或无响应则返回 null
     */
    private OutboundMessage dispatchCommand(
            InboundMessage msg,
            Session session,
            String sessionKey,
            String raw,
            boolean priorityOnly
    ) {
        // 构建命令上下文，封装执行命令所需的所有参数
        CommandRouter.CommandContext ctx = new CommandRouter.CommandContext(msg, session, sessionKey, raw, this);
        try {
            // 根据 priorityOnly 标志决定调用优先分发还是普通分发
            CompletableFuture<OutboundMessage> future = priorityOnly
                    ? commandRouter.dispatchPriority(ctx)
                    : commandRouter.dispatch(ctx);
            
            // 阻塞等待命令执行完成并获取结果，如果 future 为 null 则返回 null
            OutboundMessage result = future != null ? future.join() : null;
            if (result != null) {
                return result;
            }
            if (raw != null && raw.trim().startsWith("/")) {
                return OutboundMessages.replyTo(msg, "command error: unknown command: " + raw.trim().split("\\s+", 2)[0]);
            }
            return null;
        } catch (Exception e) {
            // 捕获命令执行过程中的异常，记录警告日志
            log.warn("命令执行失败: {}", raw, e);
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage() != null && !cause.getMessage().isBlank()
                    ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            return OutboundMessages.replyTo(msg, "command error: " + message);
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
     * 创建直接调用的入站消息。
     * <p>
     * 用于绕过消息总线，直接构造一个模拟用户输入的 InboundMessage 对象。
     *
     * @param content   消息内容
     * @param sessionKey 会话键
     * @param channel   通信渠道（如 "cli"）
     * @param chatId    聊天 ID
     * @param metadata  附加的元数据
     * @return 构造好的 InboundMessage 对象
     */
    private InboundMessage newDirectMessage(
            String content,
            String sessionKey,
            String channel,
            String chatId,
            Map<String, Object> metadata
    ) {
        // 调用 InboundMessages 工厂方法创建消息，发送者固定为 "user"，附件列表为空，时间戳为 null
        return InboundMessages.of(channel, "user", chatId, content, List.of(), metadata, sessionKey);
    }

    /**
     * 生成回复消息。
     * <p>
     * 基于原始入站消息生成一个标准的回复出站消息。
     *
     * @param msg     原始入站消息
     * @param content 回复内容
     * @return 出站消息对象
     */
    private OutboundMessage reply(InboundMessage msg, String content) {
        // 使用 OutboundMessages 工具类生成回复，保持原有的渠道和聊天 ID 关联
        return OutboundMessages.replyTo(msg, content);
    }

    /**
     * 生成纯文本回复消息。
     * <p>
     * 创建一个简单的出站消息，不包含复杂的回复结构，仅包含渠道、聊天 ID 和内容。
     *
     * @param msg     原始入站消息，用于提取渠道和聊天 ID
     * @param content 回复内容
     * @return 出站消息对象
     */
    private OutboundMessage plainReply(InboundMessage msg, String content) {
        // 直接构造出站消息，不使用 replyTo 的完整上下文关联逻辑
        return OutboundMessages.of(msg.getChannel(), msg.getChatId(), content);
    }

    /**
     * 解析系统目标标识。
     * <p>
     * 从原始的 chatId 字符串中解析出渠道（channel）和具体的聊天 ID（chatId）。
     * 格式预期为 "channel:chatId"。如果格式不符合，则默认渠道为 "cli"。
     *
     * @param rawChatId 原始的聊天 ID 字符串
     * @return 解析后的 SystemTarget 记录对象
     */
    private SystemTarget parseSystemTarget(String rawChatId) {
        // 检查 rawChatId 是否非空且包含分隔符 ":"
        if (rawChatId != null && rawChatId.contains(":")) {
            // 按 ":" 分割字符串，限制分割次数为 2，以处理 chatId 本身包含 ":" 的情况
            String[] parts = rawChatId.split(":", 2);
            // 返回解析出的渠道和聊天 ID
            return new SystemTarget(parts[0], parts[1]);
        }
        // 如果格式不匹配，默认渠道为 "cli"，整个字符串作为 chatId
        return new SystemTarget("cli", rawChatId);
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
     * 去除字符串首尾空白。
     *
     * @param s 原始字符串
     * @return 处理后的字符串
     */
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static int parseInt(String s, int def) {
        try {
            return s != null ? Integer.parseInt(s) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private void submitDispatchTask(InboundMessage msg) {
        String sessionKey = effectiveSessionKey(msg);
        Future<?> future = executor.submit(() -> {
            boolean permitAcquired = false;
            try {
                permitAcquired = acquireConcurrencyPermit();
                dispatch(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                releaseConcurrencyPermit(permitAcquired);
            }
        });
        registerActiveTask(sessionKey, future);
    }

    private boolean acquireConcurrencyPermit() throws InterruptedException {
        if (concurrencyGate != null) {
            concurrencyGate.acquire();
            return true;
        }
        return false;
    }

    private void releaseConcurrencyPermit(boolean permitAcquired) {
        if (permitAcquired && concurrencyGate != null) {
            concurrencyGate.release();
        }
    }

    private void registerActiveTask(String sessionKey, Future<?> future) {
        activeTasks.computeIfAbsent(sessionKey, key -> Collections.synchronizedList(new ArrayList<>()))
                .add(future);
    }

    private void cleanupCompletedTasks(String sessionKey) {
        List<Future<?>> tasks = activeTasks.get(sessionKey);
        if (tasks != null) {
            tasks.removeIf(Future::isDone);
        }
    }

    private void publishProcessingError(InboundMessage msg) {
        try {
            bus.publishOutbound(plainReply(msg, "抱歉，我遇到了一点错误。"));
        } catch (Exception publishErr) {
            log.warn("发布错误消息失败: channel={}, chatId={}", msg.getChannel(), msg.getChatId(), publishErr);
        }
    }

    private <T> T withSessionLock(String sessionKey, SessionWork<T> work) throws Exception {
        Object lock = sessionLocks.computeIfAbsent(sessionKey, key -> new Object());
        synchronized (lock) {
            return work.run();
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

    @FunctionalInterface
    private interface SessionWork<T> {
        T run() throws Exception;
    }

    private record SystemTarget(String channel, String chatId) {
    }
}
