package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper; // JSON 对象映射器，用于处理 JSON 数据
import ricbot.domain.hook.AgentHook; // Agent 钩子接口，用于在生命周期中插入自定义逻辑
import ricbot.domain.hook.AgentHookContext; // Agent 钩子上下文，包含当前执行状态信息
import ricbot.tool.api.ToolRegistry; // 工具注册表，用于管理和执行工具
import ricbot.integration.llm.api.LLMProvider; // LLM 提供者接口，用于与大语言模型交互
import ricbot.integration.llm.api.LLMResponse; // LLM 响应对象，包含模型返回的内容和工具调用等
import ricbot.integration.llm.api.ToolCallRequest; // 工具调用请求对象，包含工具名称和参数
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*; // 引入 Java 集合框架
import java.util.concurrent.*; // 引入并发包，用于多线程执行工具
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors; // 引入流收集器，用于集合操作

/**
 * 对应 Python: AgentRunner
 *
 * 主要目标：
 * 1. 执行单次 agent loop
 * 2. 处理模型调用、tool 调用、hook、checkpoint、注入消息
 */
public class AgentRunner implements AutoCloseable {

    /**
     * 对应 Python: _MAX_INJECTIONS_PER_TURN
     * 每轮循环中允许注入的最大消息数量
     */
    public static final int MAX_INJECTIONS_PER_TURN = 3;

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper(); // JSON 映射器实例
    private static final int DEFAULT_TOOL_THREADS = 4;
    private static final ExecutorService SHARED_TOOL_EXECUTOR = Executors.newFixedThreadPool(
            DEFAULT_TOOL_THREADS,
            new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "agent-tools-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    private final LLMProvider provider; // LLM 提供者实例
    private final ExecutorService toolExecutor;
    private final boolean ownsToolExecutor;

    /**
     * 构造函数
     * @param provider LLM 提供者
     */
    public AgentRunner(LLMProvider provider) {
        this(provider, SHARED_TOOL_EXECUTOR, false);
    }

    /**
     * 可注入执行器版本，方便测试与资源治理。
     */
    public AgentRunner(LLMProvider provider, ExecutorService toolExecutor, boolean ownsToolExecutor) {
        this.provider = provider;
        this.toolExecutor = toolExecutor != null ? toolExecutor : SHARED_TOOL_EXECUTOR;
        this.ownsToolExecutor = ownsToolExecutor;
    }

    /**
     * 运行 Agent 主循环
     * @param spec Agent 运行规格，包含初始消息、最大迭代次数、工具集等配置
     * @return AgentRunResult 运行结果，包含最终内容、消息历史、停止原因等
     * @throws Exception 异常
     */
    public AgentRunResult run(AgentRunSpec spec) throws Exception {
        // 初始化消息列表，如果 spec 中有初始消息则使用，否则为空列表
        List<Map<String, Object>> messages = new ArrayList<>(
                spec.getInitialMessages() != null ? spec.getInitialMessages() : List.of()
        );

        // 创建运行结果对象
        AgentRunResult result = new AgentRunResult();
        // 记录使用的工具名称列表
        List<String> toolsUsed = new ArrayList<>();
        // 记录工具执行事件列表
        List<Map<String, Object>> toolEvents = new ArrayList<>();
        // 标记是否有消息注入
        boolean hadInjections = false;
        // 最终内容
        String finalContent = null;
        // 停止原因，默认为 "stop"
        String stopReason = "stop";
        // 停止详情，用于记录具体的错误信息或原因
        String stopDetail = null;
        // 连续工具调用轮数计数器
        int consecutiveToolTurns = 0;
        // 助手返回空内容的轮数计数器
        int blankAssistantTurns = 0;
        // 工具执行错误的轮数计数器
        int toolErrorTurns = 0;
        // 消息注入的轮数计数器
        int injectionRounds = 0;

        // 获取钩子实现
        AgentHook hook = spec.getHook();
        // 获取工具注册表
        ToolRegistry tools = spec.getTools();
        List<Map<String, Object>> toolDefinitions = tools != null ? tools.getDefinitions() : List.of();

        // 开始主循环，最多执行 spec.getMaxIterations() 次
        for (int iteration = 1; iteration <= spec.getMaxIterations(); iteration++) {
            // 创建钩子上下文，设置当前消息、迭代次数和会话 key
            AgentHookContext context = newHookContext(messages, iteration, spec.getSessionKey());

            // 如果存在钩子，执行迭代前钩子
            if (hook != null) {
                safeHook(() -> hook.beforeIteration(context), hook);
            }

            LLMResponse response; // 声明 LLM 响应变量
            try {
                response = requestModel(spec, messages, toolDefinitions, hook, iteration);
            } catch (Exception e) {
                // 如果发生异常，且存在钩子，执行错误钩子
                if (hook != null) {
                    safeHook(() -> hook.onError(context, e), hook);
                }

                // 设置错误结果
                result.setFinalContent(spec.getErrorMessage());
                result.setMessages(messages);
                result.setStopReason("error");
                result.setError(e.getMessage());
                result.setToolsUsed(toolsUsed);
                result.setToolEvents(toolEvents);
                return result; // 直接返回错误结果
            }

            // 更新上下文中的响应、工具调用和使用量信息
            context.setResponse(response)
                    .setToolCalls(response.getToolCalls())
                    .setUsage(response.getUsage());

            // 如果存在钩子，执行迭代后钩子
            if (hook != null) {
                safeHook(() -> hook.afterIteration(context), hook);
            }

            // 如果响应中包含使用量信息，更新到结果中
            if (response.getUsage() != null) {
                result.setUsage(response.getUsage());
            }

            // 构建 assistant 消息，先加入历史
            Map<String, Object> assistantMessage = buildAssistantMessage(response);

            // 将 assistant 消息加入消息历史
            messages.add(assistantMessage);
            // 如果助手返回内容为空或空白，增加空白轮数计数
            if (response.getContent() == null || response.getContent().isBlank()) {
                blankAssistantTurns++;
            }

            // 检查点：如果有检查点回调且存在工具调用，保存当前状态
            publishCheckpoint(spec, assistantMessage, List.of(), response.getToolCalls());

            // 如果没有工具调用，结束循环
            if (!response.hasToolCalls()) {
                finalContent = finalizeContent(hook, context, response.getContent());
                // 确定停止原因，优先使用模型返回的 finishReason，否则默认为 "stop"
                stopReason = response.getFinishReason() != null ? response.getFinishReason() : "stop";
                break; // 跳出循环
            }
            // 增加连续工具调用轮数计数
            consecutiveToolTurns++;

            // 工具执行前钩子
            if (hook != null) {
                safeHook(() -> hook.beforeExecuteTools(context), hook);
            }

            // 执行工具：根据配置选择并发或顺序执行
            int toolEventStart = toolEvents.size();
            List<Map<String, Object>> toolResults = spec.isConcurrentTools()
                    ? executeToolsConcurrent(tools, response.getToolCalls(), toolsUsed, toolEvents, spec)
                    : executeToolsSequential(tools, response.getToolCalls(), toolsUsed, toolEvents, spec);

            // 将工具执行结果加入消息历史
            messages.addAll(toolResults);

            // 工具执行后钩子
            if (hook != null) {
                safeHook(() -> hook.afterExecuteTools(context), hook);
            }

            // 更新检查点：工具执行完成后
            publishCheckpoint(spec, assistantMessage, toolResults, List.of());

            // 如果配置了遇到工具错误即失败，检查是否有错误
            Map<String, Object> firstError = firstToolError(toolEvents, toolEventStart);
            if (spec.isFailOnToolError()) {
                if (firstError != null) {
                    stopReason = "tool_error";
                    stopDetail = String.valueOf(firstError.getOrDefault("detail", "tool_error"));
                    finalContent = spec.getErrorMessage();
                    break;
                }
            }
            // 如果本轮有工具错误，增加错误轮数计数
            if (firstError != null) {
                toolErrorTurns++;
            }

            // 处理 follow-up 注入消息
            List<Map<String, Object>> injected = collectInjectedMessages(spec, context, hook);
            if (!injected.isEmpty()) {
                messages.addAll(injected);
                hadInjections = true;
                injectionRounds++;
            }
        }

        // 如果循环结束后 finalContent 仍为 null，说明达到了最大迭代次数
        if (finalContent == null) {
            // 分类最大迭代次数的具体原因
            stopReason = classifyMaxIterationReason(
                    spec.getMaxIterations(),
                    consecutiveToolTurns,
                    blankAssistantTurns,
                    toolErrorTurns,
                    injectionRounds
            );
            finalContent = spec.getMaxIterationsMessage(); // 获取最大迭代提示消息
        }

        // 设置最终结果
        result.setFinalContent(finalContent);
        result.setMessages(messages);
        result.setStopReason(stopReason);
        // 如果有详细的停止信息，设置为 error 字段
        if (stopDetail != null && !stopDetail.isBlank()) {
            result.setError(stopDetail);
        } else if ("max_iterations".equals(stopReason) && consecutiveToolTurns > 0) {
            // 如果是因最大迭代次数停止且有工具调用，记录详细信息
            result.setError("max_iterations: tool_calls_turns=" + consecutiveToolTurns + ", had_injections=" + hadInjections);
        }
        result.setHadInjections(hadInjections);
        result.setToolsUsed(toolsUsed);
        result.setToolEvents(toolEvents);
        return result; // 返回结果
    }

    private AgentHookContext newHookContext(List<Map<String, Object>> messages, int iteration, String sessionKey) {
        return new AgentHookContext()
                .setMessages(messages)
                .setIteration(iteration)
                .setSessionKey(sessionKey);
    }

    private LLMResponse requestModel(
            AgentRunSpec spec,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> toolDefinitions,
            AgentHook hook,
            int iteration
    ) throws Exception {
        if (hook != null && hook.wantsStreaming()) {
            return provider.chatStream(
                    messages,
                    toolDefinitions,
                    spec.getModel(),
                    null,
                    null,
                    null,
                    null,
                    delta -> safeHook(
                            () -> hook.onStream(newHookContext(messages, iteration, spec.getSessionKey()), delta),
                            hook
                    ),
                    resuming -> safeHook(
                            () -> hook.onStreamEnd(
                                    newHookContext(messages, iteration, spec.getSessionKey()),
                                    resuming.hasToolCalls()
                            ),
                            hook
                    )
            );
        }

        if (shouldSkipProviderRetry(spec.getProviderRetryMode())) {
            return provider.chat(
                    messages,
                    toolDefinitions,
                    spec.getModel(),
                    null,
                    null,
                    null,
                    null
            );
        }

        return provider.chatWithRetry(messages, toolDefinitions, spec.getModel());
    }

    private boolean shouldSkipProviderRetry(String retryMode) {
        String mode = retryMode != null ? retryMode.trim() : "";
        return "none".equalsIgnoreCase(mode)
                || "off".equalsIgnoreCase(mode)
                || "disabled".equalsIgnoreCase(mode);
    }

    private Map<String, Object> buildAssistantMessage(LLMResponse response) {
        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", response.getContent());
        if (response.hasToolCalls()) {
            assistantMessage.put(
                    "tool_calls",
                    response.getToolCalls().stream()
                            .map(ToolCallRequest::toOpenAIToolCall)
                            .collect(Collectors.toList())
            );
        }
        return assistantMessage;
    }

    private void publishCheckpoint(
            AgentRunSpec spec,
            Map<String, Object> assistantMessage,
            List<Map<String, Object>> completedToolResults,
            List<ToolCallRequest> pendingToolCalls
    ) {
        if (spec.getCheckpointCallback() == null) {
            return;
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("assistant_message", assistantMessage);
        checkpoint.put("completed_tool_results", completedToolResults != null ? completedToolResults : List.of());
        checkpoint.put(
                "pending_tool_calls",
                pendingToolCalls != null
                        ? pendingToolCalls.stream().map(ToolCallRequest::toOpenAIToolCall).toList()
                        : List.of()
        );
        spec.getCheckpointCallback().accept(checkpoint);
    }

    private String finalizeContent(AgentHook hook, AgentHookContext context, String content) throws Exception {
        if (hook == null) {
            return content;
        }
        return hook.finalizeContent(context, content);
    }

    private List<Map<String, Object>> collectInjectedMessages(
            AgentRunSpec spec,
            AgentHookContext context,
            AgentHook hook
    ) throws Exception {
        if (spec.getInjectionCallback() == null) {
            return List.of();
        }

        List<Map<String, Object>> injected;
        try {
            injected = spec.getInjectionCallback().inject();
        } catch (Exception e) {
            if (hook != null) {
                safeHook(() -> hook.onError(context, e), hook);
            } else {
                log.warn("注入回调失败: sessionKey={}", spec.getSessionKey(), e);
            }
            return List.of();
        }
        return normalizeInjectedMessages(injected, MAX_INJECTIONS_PER_TURN);
    }

    /**
     * 顺序执行工具
     * @param tools 工具注册表
     * @param toolCalls 工具调用列表
     * @param toolsUsed 已使用工具列表（输出）
     * @param toolEvents 工具事件列表（输出）
     * @param spec 运行规格
     * @return 工具执行结果消息列表
     */
    private List<Map<String, Object>> executeToolsSequential(
            ToolRegistry tools,
            List<ToolCallRequest> toolCalls,
            List<String> toolsUsed,
            List<Map<String, Object>> toolEvents,
            AgentRunSpec spec
    ) {
        List<Map<String, Object>> results = new ArrayList<>();

        // 遍历每个工具调用
        for (ToolCallRequest toolCall : toolCalls) {
            ToolExecution out = executeSingleTool(tools, toolCall, spec); // 执行单个工具
            toolsUsed.add(out.name()); // 记录工具名
            toolEvents.add(out.event()); // 记录事件
            results.add(out.toolMsg()); // 添加结果消息
        }

        return results;
    }

    /**
     * 并发执行工具
     * @param tools 工具注册表
     * @param toolCalls 工具调用列表
     * @param toolsUsed 已使用工具列表（输出）
     * @param toolEvents 工具事件列表（输出）
     * @param spec 运行规格
     * @return 工具执行结果消息列表
     * @throws Exception 异常
     */
    private List<Map<String, Object>> executeToolsConcurrent(
            ToolRegistry tools,
            List<ToolCallRequest> toolCalls,
            List<String> toolsUsed,
            List<Map<String, Object>> toolEvents,
            AgentRunSpec spec
    ) {
        CompletionService<IndexedToolExecution> cs = new ExecutorCompletionService<>(toolExecutor);
        int submitted = 0;
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCallRequest toolCall = toolCalls.get(i);
            int index = i;
            cs.submit(() -> new IndexedToolExecution(index, executeSingleTool(tools, toolCall, spec)));
            submitted++;
        }

        Map<Integer, ToolExecution> byIndex = new HashMap<>();
        for (int i = 0; i < submitted; i++) {
            try {
                IndexedToolExecution out = cs.take().get();
                byIndex.put(out.index(), out.execution());
            } catch (Exception e) {
                ToolExecution fallback = new ToolExecution(
                        "unknown",
                        buildToolMessage("unknown", "unknown", false, null, "工具并发执行失败: " + e.getMessage(), null, Integer.MAX_VALUE),
                        Map.of(
                                "name", "unknown",
                                "status", "error",
                                "detail", truncate("工具并发执行失败: " + e.getMessage(), 300)
                        )
                );
                byIndex.put(i, fallback);
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolExecution out = byIndex.get(i);
            if (out == null) {
                continue;
            }
            toolsUsed.add(out.name());
            toolEvents.add(out.event());
            results.add(out.toolMsg());
        }
        return results;
    }

    /**
     * 执行单个工具
     * @param tools 工具注册表
     * @param toolCall 工具调用请求
     * @param spec 运行规格
     * @return ToolExecution 包含工具名、结果消息和事件
     */
    private ToolExecution executeSingleTool(ToolRegistry tools, ToolCallRequest toolCall, AgentRunSpec spec) {
        String toolName = toolCall.getName(); // 获取工具名
        if (spec.getToolLifecycleCallback() != null) {
            spec.getToolLifecycleCallback().onToolStart(toolName, toolCall.getArguments());
        }
        Instant startedAt = Instant.now();

        Object result; // 工具执行结果
        String status = "ok"; // 状态，默认为 ok
        String detail; // 详细信息
        Object contentObj;

        try {
            // 执行工具
            result = tools.execute(toolName, toolCall.getArguments());
            boolean ok = isToolOk(result);
            if (!ok) {
                status = "error";
            }
            detail = truncate(extractToolDetail(result), 300);

            if ("error".equals(status)) {
                if (result instanceof String s && isPlainErrorString(s)) {
                    contentObj = s;
                } else {
                    contentObj = buildToolResultPayload(toolName, false, result, extractToolDetail(result), null);
                }
            } else {
                contentObj = buildToolResultPayload(toolName, true, result, null, null);
            }
        } catch (Exception e) {
            // 捕获异常，设置错误结果
            result = Map.of("exception", e.getClass().getName(), "message", e.getMessage());
            status = "error";
            detail = truncate("执行 " + toolName + " 时出错: " + e.getMessage(), 300);
            contentObj = buildToolResultPayload(
                    toolName,
                    false,
                    result,
                    "执行 " + toolName + " 时出错: " + e.getMessage(),
                    e.getClass().getName()
            );
        }

        // 构建事件对象
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", toolName);
        event.put("status", status);
        event.put("detail", detail);
        event.put("tool_call_id", toolCall.getId());
        event.put("arguments_summary", summarizeArgs(toolCall.getArguments()));
        event.put("result_summary", summarizeResult(contentObj));
        event.put("duration_ms", Duration.between(startedAt, Instant.now()).toMillis());
        event.put("executed_at", Instant.now().toString());

        // 构建工具消息对象
        Map<String, Object> toolMsg = buildToolMessage(
                toolCall.getId(),
                toolName,
                "ok".equals(status),
                result,
                "error".equals(status) ? detail : null,
                contentObj,
                spec.getMaxToolResultChars()
        );
        Object content = toolMsg.get("content");
        if (content instanceof String s) {
            event.put("truncated", s.contains("\"truncated\":true") || s.contains("(truncated)"));
        }
        if (spec.getToolLifecycleCallback() != null) {
            spec.getToolLifecycleCallback().onToolFinish(event);
        }

        return new ToolExecution(toolName, toolMsg, event);
    }

    /**
     * 截断工具结果
     * @param value 结果值
     * @param maxChars 最大字符数
     * @return 截断后的字符串
     */
    private Object truncateToolResult(Object value, int maxChars) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (maxChars > 0 && s.length() > maxChars) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("type", "tool_result");
            wrapped.put("truncated", true);
            wrapped.put("total_chars", s.length());
            wrapped.put("preview", s.substring(0, Math.max(0, maxChars)));
            return toToolContent(wrapped);
        }
        return s;
    }

    /**
     * 截断字符串
     * @param s 原始字符串
     * @param max 最大长度
     * @return 截断后的字符串
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 安全执行钩子，捕获异常并根据配置决定是否重新抛出
     * @param runnable 可执行代码块
     * @param hook 钩子实例
     * @throws Exception 如果钩子配置为重新抛出异常
     */
    private void safeHook(ThrowingRunnable runnable, AgentHook hook) throws Exception {
        try {
            runnable.run();
        } catch (Exception e) {
            if (hook != null && hook.isReraise()) {
                throw e;
            }
            log.warn("hook 执行异常被吞掉: hook={}", hook != null ? hook.getClass().getName() : "null", e);
        }
    }

    /**
     * 工具执行结果记录
     * @param name 工具名
     * @param toolMsg 工具消息
     * @param event 事件信息
     */
    private record ToolExecution(String name, Map<String, Object> toolMsg, Map<String, Object> event) {}

    private record IndexedToolExecution(int index, ToolExecution execution) {}

    /**
     * 函数式接口，允许抛出异常
     */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private boolean isToolOk(Object result) {
        if (result == null) {
            return true;
        }
        if (result instanceof Map<?, ?> m) {
            Object ok = m.get("ok");
            if (ok instanceof Boolean b) {
                return b;
            }
            Object error = m.get("error");
            if (error != null) {
                return false;
            }
        }
        if (result instanceof String s) {
            String lower = s.trim().toLowerCase(Locale.ROOT);
            return !(lower.startsWith("错误") || lower.startsWith("error") || lower.startsWith("err"));
        }
        return true;
    }

    private boolean isPlainErrorString(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("错误") || lower.startsWith("error") || lower.startsWith("err");
    }

    private String extractToolDetail(Object result) {
        if (result == null) {
            return "";
        }
        if (result instanceof Map<?, ?> m) {
            Object error = m.get("error");
            if (error != null) {
                return String.valueOf(error);
            }
            Object message = m.get("message");
            if (message != null) {
                return String.valueOf(message);
            }
        }
        return String.valueOf(result);
    }

    private String summarizeArgs(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (parts.size() >= 3) {
                break;
            }
            parts.add(entry.getKey() + "=" + truncate(String.valueOf(entry.getValue()), 50));
        }
        return String.join(", ", parts);
    }

    private String summarizeResult(Object contentObj) {
        return truncate(String.valueOf(contentObj), 220);
    }

    private String toToolContent(Object obj) {
        if (obj == null) {
            return "";
        }
        if (obj instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private Map<String, Object> firstToolError(List<Map<String, Object>> toolEvents, int startIndex) {
        if (toolEvents == null) {
            return null;
        }
        for (int i = Math.max(0, startIndex); i < toolEvents.size(); i++) {
            Map<String, Object> ev = toolEvents.get(i);
            if (ev == null) {
                continue;
            }
            Object status = ev.get("status");
            if ("error".equals(status)) {
                return ev;
            }
        }
        return null;
    }

    private List<Map<String, Object>> normalizeInjectedMessages(List<Map<String, Object>> injected, int limit) {
        if (injected == null || injected.isEmpty() || limit <= 0) {
            return List.of();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> raw : injected) {
            if (raw == null) {
                continue;
            }
            Object role = raw.get("role");
            if (!(role instanceof String)) {
                continue;
            }

            Map<String, Object> msg = new LinkedHashMap<>(raw);
            msg.put("_injected", true);

            String key;
            try {
                key = MAPPER.writeValueAsString(msg);
            } catch (Exception e) {
                key = String.valueOf(msg);
            }

            if (!seen.add(key)) {
                continue;
            }

            out.add(msg);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private String classifyMaxIterationReason(
            int maxIterations,
            int toolTurns,
            int blankAssistantTurns,
            int toolErrorTurns,
            int injectionRounds
    ) {
        if (toolTurns >= maxIterations) {
            if (injectionRounds >= Math.max(1, maxIterations / 2)) {
                return "injection_loop";
            }
            if (blankAssistantTurns >= maxIterations) {
                return "empty_spin";
            }
            if (toolErrorTurns > 0) {
                return "tool_error_loop";
            }
            return "tool_loop";
        }
        return "max_iterations";
    }

    private Map<String, Object> buildToolMessage(
            String toolCallId,
            String toolName,
            boolean ok,
            Object result,
            String errorMessage,
            Object payload,
            int maxChars
    ) {
        Map<String, Object> toolMsg = new LinkedHashMap<>();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", toolCallId);
        toolMsg.put("name", toolName);

        Object contentPayload = payload != null ? payload : buildToolResultPayload(toolName, ok, result, errorMessage, null);
        toolMsg.put("content", truncateToolResult(toToolContent(contentPayload), maxChars));
        return toolMsg;
    }

    private Map<String, Object> buildToolResultPayload(
            String toolName,
            boolean ok,
            Object result,
            String errorMessage,
            String exceptionType
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool_result");
        payload.put("tool_name", toolName);
        payload.put("ok", ok);
        payload.put("result", result);
        if (!ok) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("message", errorMessage != null ? errorMessage : "tool execution failed");
            if (exceptionType != null && !exceptionType.isBlank()) {
                err.put("type", exceptionType);
            }
            payload.put("error", err);
        }
        return payload;
    }

    @Override
    public void close() {
        if (ownsToolExecutor && toolExecutor != null) {
            toolExecutor.shutdownNow();
        }
    }
}
