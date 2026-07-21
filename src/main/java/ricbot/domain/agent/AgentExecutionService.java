package ricbot.domain.agent;

import ricbot.domain.config.ProviderCapability;
import ricbot.domain.hook.AgentHook;
import ricbot.infra.runtime.RuntimeUtils;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

// 代理执行服务类，负责管理 Agent 的运行逻辑、配置及结果处理
final class AgentExecutionService {

    private static final Logger LOGGER = Logger.getLogger(AgentExecutionService.class.getName());

    // Agent 运行器实例，用于执行具体的 Agent 任务
    private final GraphRunService runner;
    // 工具注册表，提供 Agent 可调用的工具集合
    private final ToolRegistry tools;
    // 工作空间路径，Agent 操作的文件系统根目录
    private final Path workspace;
    // 使用的模型名称
    private final String model;
    // 最大迭代次数，限制 Agent 的工具调用循环次数
    private final int maxIterations;
    // 工具返回结果的最大字符数限制
    private final int maxToolResultChars;
    // 提供商重试模式配置
    private final String providerRetryMode;
    // 上下文窗口令牌数限制
    private final int contextWindowTokens;
    // 上下文块数量限制（可选）
    private final Integer contextBlockLimit;
    // 静态/启发式模型能力，用于运行时保守降级
    private final ProviderCapability providerCapability;
    // 类型化运行事件的持久化出口
    private final RunEventSink runEventSink;
    private final SideEffectStore sideEffectStore;

    // 构造函数，初始化所有必要配置
    AgentExecutionService(
            GraphRunService runner,
            ToolRegistry tools,
            Path workspace,
            String model,
            int maxIterations,
            int maxToolResultChars,
            String providerRetryMode,
            int contextWindowTokens,
            Integer contextBlockLimit
    ) {
        this(
                runner,
                tools,
                workspace,
                model,
                maxIterations,
                maxToolResultChars,
                providerRetryMode,
                contextWindowTokens,
                contextBlockLimit,
                null,
                RunEventSink.disabled()
        );
    }

    // 构造函数，初始化所有必要配置
    AgentExecutionService(
            GraphRunService runner,
            ToolRegistry tools,
            Path workspace,
            String model,
            int maxIterations,
            int maxToolResultChars,
            String providerRetryMode,
            int contextWindowTokens,
            Integer contextBlockLimit,
            ProviderCapability providerCapability
    ) {
        this(
                runner,
                tools,
                workspace,
                model,
                maxIterations,
                maxToolResultChars,
                providerRetryMode,
                contextWindowTokens,
                contextBlockLimit,
                providerCapability,
                RunEventSink.disabled(),
                SideEffectStore.disabled()
        );
    }

    AgentExecutionService(
            GraphRunService runner,
            ToolRegistry tools,
            Path workspace,
            String model,
            int maxIterations,
            int maxToolResultChars,
            String providerRetryMode,
            int contextWindowTokens,
            Integer contextBlockLimit,
            ProviderCapability providerCapability,
            RunEventSink runEventSink
    ) {
        this(runner, tools, workspace, model, maxIterations, maxToolResultChars,
                providerRetryMode, contextWindowTokens, contextBlockLimit, providerCapability,
                runEventSink, SideEffectStore.disabled());
    }

    AgentExecutionService(
            GraphRunService runner,
            ToolRegistry tools,
            Path workspace,
            String model,
            int maxIterations,
            int maxToolResultChars,
            String providerRetryMode,
            int contextWindowTokens,
            Integer contextBlockLimit,
            ProviderCapability providerCapability,
            RunEventSink runEventSink,
            SideEffectStore sideEffectStore
    ) {
        this.runner = runner;
        this.tools = tools;
        this.workspace = workspace;
        this.model = model;
        this.maxIterations = maxIterations;
        this.maxToolResultChars = maxToolResultChars;
        this.providerRetryMode = providerRetryMode;
        this.contextWindowTokens = contextWindowTokens;
        this.contextBlockLimit = contextBlockLimit;
        this.providerCapability = providerCapability;
        this.runEventSink = runEventSink != null ? runEventSink : RunEventSink.disabled();
        this.sideEffectStore = sideEffectStore != null ? sideEffectStore : SideEffectStore.disabled();
    }

    // 执行交互式 Agent 任务
    // @param request Agent 请求上下文，包含初始消息、会话信息等
    // @param checkpointCallback 检查点回调函数，用于保存中间状态
    // @return ExecutionOutcome 执行结果对象
    ExecutionOutcome executeInteractive(AgentRequestContext request, Consumer<Map<String, Object>> checkpointCallback) throws Exception {
        // 构建运行规格并执行 Agent
        AgentRunResult result = runner.run(buildSpec(
                request.initialMessages(),
                request.session().getKey(),
                request.hook(),
                maxIterations,
                maxIterationsMessage(maxIterations),
                checkpointCallback,
                request.session(),
                request.message().getMetadata()
        ));

        // 获取钩子对象
        AgentHook hook = request.hook();
        AgentRetryPolicy retryPolicy = AgentRetryPolicy.maxRetries(1);
        // 如果未启用流式输出或没有钩子，且停止原因为工具循环或工具错误循环
        if (shouldRetryAfter(hook, result) && retryPolicy.recordFailure(result.getStopReason())) {
            // 计算新的最大迭代次数：取 maxIterations+6 和 maxIterations*2 中的较大值，且不超过 30
            int bumped = Math.min(30, Math.max(maxIterations + 6, maxIterations * 2));
            // 如果新的迭代次数大于当前设置，则重新运行 Agent
            if (bumped > maxIterations) {
                AgentRunSpec retrySpec = buildSpec(
                        result.getMessages(),
                        request.session().getKey(),
                        hook,
                        bumped,
                        maxIterationsMessage(bumped),
                        checkpointCallback,
                        request.session(),
                        request.message().getMetadata()
                );
                retrySpec.setCheckpointMessageOffset(request.initialMessages().size());
                retrySpec.getMetadata().put("retryReason", retryPolicy.lastRetryReason().orElse(null));
                retrySpec.getMetadata().put("retryCount", retryPolicy.retryCount());
                LOGGER.info(() -> "Agent retry requested: reason="
                        + retryPolicy.lastRetryReason().orElse("unknown")
                        + ", retryCount=" + retryPolicy.retryCount()
                        + ", maxIterations=" + bumped);
                result = runner.run(retrySpec);
            }
        }

        // 获取最终内容
        String finalContent = result.getFinalContent();
        // 如果最终内容为空，使用默认空响应消息
        if (RuntimeUtils.isBlankText(finalContent)) {
            finalContent = RuntimeUtils.EMPTY_FINAL_RESPONSE_MESSAGE;
        }
        // 返回执行结果
        return new ExecutionOutcome(result, finalContent);
    }

    // 执行系统级 Agent 任务（非交互式）
    // @param request Agent 请求上下文
    // @return ExecutionOutcome 执行结果对象
    ExecutionOutcome executeSystem(AgentRequestContext request) throws Exception {
        return executeSystem(request, null);
    }

    // 执行系统级 Agent 任务，并在每个可恢复边界持久化检查点。
    ExecutionOutcome executeSystem(
            AgentRequestContext request,
            Consumer<Map<String, Object>> checkpointCallback
    ) throws Exception {
        // 系统任务不使用交互钩子，但与交互任务共享同一套持久化检查点语义。
        AgentRunResult result = runner.run(buildSpec(
                request.initialMessages(),
                request.session().getKey(),
                null,
                maxIterations,
                maxIterationsMessage(maxIterations),
                checkpointCallback,
                request.session(),
                request.message().getMetadata()
        ));

        // 如果最终内容为空，使用默认完成消息，否则使用实际内容
        String finalContent = RuntimeUtils.isBlankText(result.getFinalContent())
                ? "后台任务已完成。"
                : result.getFinalContent();
        // 返回执行结果
        return new ExecutionOutcome(result, finalContent);
    }

    // 构建 Agent 运行规格对象
    // @param initialMessages 初始消息列表
    // @param sessionKey 会话密钥
    // @param hook Agent 钩子
    // @param iterations 最大迭代次数
    // @param maxIterationMessage 达到最大迭代次数时的提示信息
    // @param checkpointCallback 检查点回调
    // @param session 会话对象
    // @return AgentRunSpec 运行规格对象
    private AgentRunSpec buildSpec(
            java.util.List<java.util.Map<String, Object>> initialMessages,
            String sessionKey,
            AgentHook hook,
            int iterations,
            String maxIterationMessage,
            Consumer<Map<String, Object>> checkpointCallback,
            ricbot.domain.session.Session session,
            Map<String, Object> requestMetadata
    ) {
        // 创建并配置 AgentRunSpec 对象
        AgentRunSpec spec = new AgentRunSpec()
                .setInitialMessages(initialMessages) // 设置初始消息
                .setTools(tools) // 设置工具注册表
                .setModel(model) // 设置模型
                .setMaxIterations(iterations) // 设置最大迭代次数
                .setMaxToolResultChars(maxToolResultChars) // 设置工具结果最大字符数
                .setHook(hook) // 设置钩子
                .setProviderRetryMode(providerRetryMode) // 设置重试模式
                .setErrorMessage("抱歉，调用模型时遇到错误。") // 设置错误消息
                .setMaxIterationsMessage(maxIterationMessage) // 设置最大迭代提示消息
                .setConcurrentTools(true) // 启用并发工具调用
                .setWorkspace(workspace) // 设置工作空间
                .setSessionKey(sessionKey) // 设置会话密钥
                .setContextWindowTokens(contextWindowTokens) // 设置上下文窗口令牌数
                .setContextBlockLimit(contextBlockLimit) // 设置上下文块限制
                .setProviderCapability(providerCapability) // 设置模型能力元数据
                .setCheckpointCallback(checkpointCallback) // 设置检查点回调
                .setRunEventSink(runEventSink)
                .setSideEffectStore(sideEffectStore)
                // 设置工具生命周期回调，用于记录工具执行状态
                .setToolLifecycleCallback(new AgentRunSpec.ToolLifecycleCallback() {
                    @Override
                    public void onToolStart(String toolName, Map<String, Object> arguments) {
                        // 同步会话对象，确保线程安全
                        synchronized (session) {
                            // 从会话中获取任务状态
                            TaskState taskState = TaskState.fromSession(session);
                            // 标记工具开始执行
                            taskState.markToolStart(toolName, arguments);
                            // 持久化任务状态到会话
                            taskState.persist(session);
                        }
                    }

                    @Override
                    public void onToolFinish(Map<String, Object> event) {
                        // 同步会话对象，确保线程安全
                        synchronized (session) {
                            // 从会话中获取任务状态
                            TaskState taskState = TaskState.fromSession(session);
                            // 标记工具执行结束
                            taskState.markToolFinish(event);
                            // 持久化任务状态到会话
                            taskState.persist(session);
                        }
                    }
                });
        if (requestMetadata != null) {
            for (Map.Entry<String, Object> entry : requestMetadata.entrySet()) {
                Object value = entry.getValue();
                if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
                    spec.getMetadata().put(entry.getKey(), value);
                }
            }
        }
        return spec;
    }

    // 生成达到最大迭代次数时的提示消息
    // @param iterations 当前的最大迭代次数
    // @return 提示消息字符串
    private String maxIterationsMessage(int iterations) {
        return "我已达到最大迭代次数（agents.defaults.max_tool_iterations=" + iterations + "），但仍未完成任务。可尝试提高该值（例如 12 或 16）后重试。";
    }

    private boolean shouldRetryAfter(AgentHook hook, AgentRunResult result) {
        if (hook != null && hook.wantsStreaming()) {
            return false;
        }
        String stopReason = result.getStopReason();
        return "tool_loop".equals(stopReason) || "tool_error_loop".equals(stopReason);
    }
}
