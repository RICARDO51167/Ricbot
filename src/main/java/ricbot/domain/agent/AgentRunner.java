package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper; // JSON 对象映射器，用于处理 JSON 数据
import ricbot.domain.config.ModelCapability;
import ricbot.domain.config.ProviderCapability;
import ricbot.domain.config.ProviderCapabilityResolver;
import ricbot.domain.hook.AgentHook; // Agent 钩子接口，用于在生命周期中插入自定义逻辑
import ricbot.domain.hook.AgentHookContext; // Agent 钩子上下文，包含当前执行状态信息
import ricbot.tool.api.ToolRegistry; // 工具注册表，用于管理和执行工具
import ricbot.integration.llm.api.LLMProvider; // LLM 提供者接口，用于与大语言模型交互
import ricbot.integration.llm.api.LLMResponse; // LLM 响应对象，包含模型返回的内容和工具调用等
import ricbot.integration.llm.api.ToolCallRequest; // 工具调用请求对象，包含工具名称和参数
import ricbot.domain.trace.TraceRecorder;
import ricbot.tool.api.ToolExecutionPolicy;
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
        String metadataRunId = spec != null && spec.getMetadata() != null
                ? String.valueOf(spec.getMetadata().getOrDefault("consoleRunId", "")).trim()
                : "";
        String runId = !metadataRunId.isBlank() ? metadataRunId : UUID.randomUUID().toString();
        Instant runStartedAt = Instant.now();
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
        List<Map<String, Object>> runEvents = new ArrayList<>();
        TraceRecorder traceRecorder = TraceRecorder.forRunEvents(runEvents);
        traceRecorder.recordRunEvent("run_start", Map.of(
                "run_id", runId,
                "session_key", spec.getSessionKey() != null ? spec.getSessionKey() : "",
                "model", spec.getModel() != null ? spec.getModel() : "",
                "max_iterations", spec.getMaxIterations()
        ));
        recordRetryIfPresent(traceRecorder, spec);
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
        int iterationsCompleted = 0;
        AgentRunController controller = spec.getRunTimeout() != null
                ? AgentRunController.withMaxTurnsAndTimeout(spec.getMaxIterations(), spec.getRunTimeout(), java.time.Clock.systemUTC())
                : AgentRunController.withMaxTurns(spec.getMaxIterations());
        if (spec.getRunControllerConsumer() != null) {
            spec.getRunControllerConsumer().accept(controller);
        }
        boolean cancelledRecorded = false;

        // 获取钩子实现
        AgentHook hook = spec.getHook();
        // 获取工具注册表
        ToolRegistry tools = spec.getTools();
        ProviderCapability capability = spec.getProviderCapability();
        RuntimePolicy runtimePolicy = resolveRuntimePolicy(spec, capability, tools, messages, traceRecorder);
        List<Map<String, Object>> toolDefinitions = runtimePolicy.toolDefinitions();
        if (runtimePolicy.unsupportedVisionMessage() != null) {
            result.setFinalContent(runtimePolicy.unsupportedVisionMessage());
            result.setMessages(messages);
            result.setStopReason("unsupported_capability");
            result.setError(runtimePolicy.unsupportedVisionMessage());
            result.setToolsUsed(toolsUsed);
            result.setToolEvents(toolEvents);
            finishRunResult(result, runId, runStartedAt, iterationsCompleted, runEvents, traceRecorder);
            return result;
        }
        if (runtimePolicy.noExposedToolsMessage() != null) {
            result.setFinalContent(runtimePolicy.noExposedToolsMessage());
            result.setMessages(messages);
            result.setStopReason("no_exposed_tools");
            result.setError(runtimePolicy.noExposedToolsMessage());
            result.setToolsUsed(toolsUsed);
            result.setToolEvents(toolEvents);
            finishRunResult(result, runId, runStartedAt, iterationsCompleted, runEvents, traceRecorder);
            return result;
        }

        // 开始主循环，最多执行 spec.getMaxIterations() 次
        while (controller.canContinue()) {
            controller.recordTurn();
            int iteration = controller.currentTurn();
            iterationsCompleted = iteration;
            if (controller.cancelled()) {
                stopReason = "cancelled";
                finalContent = "Agent run cancelled.";
                cancelledRecorded = recordCancelled(traceRecorder, controller, runId, iteration, cancelledRecorded);
                break;
            }
            // 创建钩子上下文，设置当前消息、迭代次数和会话 key
            AgentHookContext context = newHookContext(messages, iteration, spec.getSessionKey());

            // 如果存在钩子，执行迭代前钩子
            if (hook != null) {
                safeHook(() -> hook.beforeIteration(context), hook);
            }

            LLMResponse response; // 声明 LLM 响应变量
            Instant modelStartedAt = Instant.now();
            traceRecorder.recordRunEvent("model_request", metadata(iteration, Map.of(
                    "message_count", messages.size(),
                    "tool_definition_count", toolDefinitions.size()
            )));
            try {
                response = requestModel(spec, messages, toolDefinitions, hook, iteration, runtimePolicy.disableStreaming());
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
                traceRecorder.recordRunEvent("model_error", metadata(iteration, Map.of(
                        "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                        "duration_ms", Duration.between(modelStartedAt, Instant.now()).toMillis()
                )));
                finishRunResult(result, runId, runStartedAt, iterationsCompleted, runEvents, traceRecorder);
                return result; // 直接返回错误结果
            }
            traceRecorder.recordRunEvent("model_response", metadata(iteration, Map.of(
                    "finish_reason", response.getFinishReason() != null ? response.getFinishReason() : "",
                    "content_chars", response.getContent() != null ? response.getContent().length() : 0,
                    "tool_call_count", response.getToolCalls() != null ? response.getToolCalls().size() : 0,
                    "usage", response.getUsage() != null ? response.getUsage() : Map.of(),
                    "duration_ms", Duration.between(modelStartedAt, Instant.now()).toMillis()
            )));
            if (controller.cancelled()) {
                stopReason = "cancelled";
                finalContent = "Agent run cancelled.";
                cancelledRecorded = recordCancelled(traceRecorder, controller, runId, iteration, cancelledRecorded);
                break;
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
            if (runtimePolicy.disableToolCalling()) {
                addCapabilityWarning(traceRecorder, 0, capability, "supportsToolCalling", "IGNORED_MODEL_TOOL_CALLS",
                        "模型 capability 标记为不支持 tool calling，运行时不会进入工具调用循环。");
                finalContent = response.getContent() != null && !response.getContent().isBlank()
                        ? finalizeContent(hook, context, response.getContent())
                        : "当前模型不支持工具调用，已跳过工具执行。请切换支持 tool calling 的模型后重试。";
                stopReason = "unsupported_capability";
                break;
            }
            // 增加连续工具调用轮数计数
            consecutiveToolTurns++;
            if (controller.cancelled()) {
                stopReason = "cancelled";
                finalContent = "Agent run cancelled.";
                cancelledRecorded = recordCancelled(traceRecorder, controller, runId, iteration, cancelledRecorded);
                break;
            }

            // 工具执行前钩子
            if (hook != null) {
                safeHook(() -> hook.beforeExecuteTools(context), hook);
            }

            // 执行工具：根据配置选择并发或顺序执行
            int toolEventStart = toolEvents.size();
            List<String> requestedToolNames = response.getToolCalls().stream().map(ToolCallRequest::getName).toList();
            ToolExecutionPolicy toolExecutionPolicy = toolExecutionPolicy(tools);
            boolean concurrent = tools != null && toolExecutionPolicy.shouldRunConcurrently(
                    spec.isConcurrentTools(),
                    requestedToolNames,
                    tools::policyFor
            );
            traceRecorder.recordRunEvent("tool_batch", metadata(iteration, Map.of(
                    "tool_call_count", requestedToolNames.size(),
                    "execution_mode", concurrent ? "concurrent" : "sequential",
                    "tools", requestedToolNames
            )));
            List<Map<String, Object>> toolResults = concurrent
                    ? executeToolsConcurrent(tools, response.getToolCalls(), toolsUsed, toolEvents, spec, iteration)
                    : executeToolsSequential(tools, response.getToolCalls(), toolsUsed, toolEvents, spec, iteration);
            for (Map<String, Object> event : toolEvents.subList(toolEventStart, toolEvents.size())) {
                traceRecorder.recordToolEvent(String.valueOf(event.getOrDefault("tool_call_id", "")), metadata(iteration, event));
            }
            if (controller.cancelled()) {
                stopReason = "cancelled";
                finalContent = "Agent run cancelled.";
                cancelledRecorded = recordCancelled(traceRecorder, controller, runId, iteration, cancelledRecorded);
                break;
            }

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
            if (toolExecutionPolicy.shouldStopRunOnToolError(firstError, spec.isFailOnToolError())) {
                stopReason = "tool_error";
                stopDetail = String.valueOf(firstError.getOrDefault("detail", "tool_error"));
                finalContent = spec.getErrorMessage();
                break;
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
            if (controller.cancelled()) {
                stopReason = "cancelled";
                finalContent = "Agent run cancelled.";
                cancelledRecorded = recordCancelled(traceRecorder, controller, runId, iterationsCompleted, cancelledRecorded);
            } else if ("timeout".equals(controller.stopReason().orElse(""))) {
                stopReason = "timeout";
                finalContent = "Agent 运行超时，已在下一轮开始前停止。";
                traceRecorder.recordRunEvent("run_timeout", metadata(iterationsCompleted, Map.of(
                        "deadline", controller.deadline().map(Instant::toString).orElse(""),
                        "iterations", iterationsCompleted
                )));
            } else {
                // 分类最大迭代次数的具体原因
                stopReason = classifyMaxIterationReason(
                        spec.getMaxIterations(),
                        consecutiveToolTurns,
                        blankAssistantTurns,
                        toolErrorTurns,
                        injectionRounds
                );
                controller.stop(stopReason);
                finalContent = spec.getMaxIterationsMessage(); // 获取最大迭代提示消息
            }
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
        finishRunResult(result, runId, runStartedAt, iterationsCompleted, runEvents, traceRecorder);
        return result; // 返回结果
    }

    private void finishRunResult(
            AgentRunResult result,
            String runId,
            Instant startedAt,
            int iterations,
            List<Map<String, Object>> runEvents,
            TraceRecorder traceRecorder
    ) {
        traceRecorder.recordRunEvent("run_stop", Map.of(
                "stop_reason", result.getStopReason() != null ? result.getStopReason() : "",
                "iterations", iterations,
                "error", result.getError() != null ? result.getError() : ""
        ));
        traceRecorder.recordRunEvent("run_finish", Map.of(
                "run_id", runId,
                "stop_reason", result.getStopReason() != null ? result.getStopReason() : "",
                "iterations", iterations,
                "duration_ms", Duration.between(startedAt, Instant.now()).toMillis()
        ));
        result.setRunId(runId);
        result.setStartedAt(startedAt.toString());
        result.setEndedAt(Instant.now().toString());
        result.setIterations(iterations);
        result.setRunEvents(runEvents);
    }

    private boolean recordCancelled(
            TraceRecorder traceRecorder,
            AgentRunController controller,
            String runId,
            int iteration,
            boolean alreadyRecorded
    ) {
        if (alreadyRecorded) {
            return true;
        }
        traceRecorder.recordRunEvent("run_cancelled", metadata(iteration, Map.of(
                "runId", runId,
                "run_id", runId,
                "reason", controller.stopReason().orElse("cancelled"),
                "currentStatus", "cancelled"
        )));
        return true;
    }

    private Map<String, Object> metadata(int iteration, Map<String, Object> values) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("iteration", iteration);
        if (values != null) {
            event.putAll(values);
        }
        return event;
    }

    private void recordRetryIfPresent(TraceRecorder traceRecorder, AgentRunSpec spec) {
        Map<String, Object> metadata = spec.getMetadata();
        if (metadata == null || !metadata.containsKey("retryReason")) {
            return;
        }
        traceRecorder.recordRunEvent("run_retry", Map.of(
                "retry_reason", String.valueOf(metadata.getOrDefault("retryReason", "")),
                "retry_count", metadata.getOrDefault("retryCount", 0)
        ));
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
            int iteration,
            boolean forceNonStreaming
    ) throws Exception {
        if (!forceNonStreaming && hook != null && hook.wantsStreaming()) {
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

    private RuntimePolicy resolveRuntimePolicy(
            AgentRunSpec spec,
            ProviderCapability capability,
            ToolRegistry tools,
            List<Map<String, Object>> messages,
            TraceRecorder traceRecorder
    ) {
        List<Map<String, Object>> definitions = tools != null ? tools.getDefinitions() : List.of();
        List<String> registeredTools = tools != null ? tools.toolNames() : List.of();
        List<String> allowedTools = normalizeToolNames(spec.getAllowedTools());
        List<String> exposedToolsBeforeCapability = new ArrayList<>();
        List<String> missingAllowedTools = new ArrayList<>();
        if (!allowedTools.isEmpty()) {
            java.util.Set<String> registered = new java.util.LinkedHashSet<>(registeredTools);
            missingAllowedTools = allowedTools.stream()
                    .filter(name -> !registered.contains(name))
                    .toList();
            java.util.Set<String> allowed = new java.util.LinkedHashSet<>(allowedTools);
            definitions = definitions.stream()
                    .filter(schema -> allowed.contains(schemaName(schema)))
                    .toList();
        }
        exposedToolsBeforeCapability = definitions.stream()
                .map(AgentRunner::schemaName)
                .filter(name -> !name.isBlank())
                .toList();
        traceRecorder.recordRunEvent("tool_exposure", metadata(0, Map.of(
                "allowed_tools", allowedTools,
                "registered_tools", registeredTools,
                "exposed_tools", exposedToolsBeforeCapability,
                "missing_allowed_tools", missingAllowedTools
        )));
        String noExposedToolsMessage = null;
        if (!allowedTools.isEmpty() && definitions.isEmpty()) {
            noExposedToolsMessage = "no tools exposed to worker";
            traceRecorder.recordRunEvent("tool_exposure_warning", metadata(0, Map.of(
                    "warning", noExposedToolsMessage,
                    "allowed_tools", allowedTools,
                    "registered_tools", registeredTools,
                    "missing_allowed_tools", missingAllowedTools
            )));
        } else if (!missingAllowedTools.isEmpty()) {
            traceRecorder.recordRunEvent("tool_exposure_warning", metadata(0, Map.of(
                    "warning", "allowed tools missing from registry",
                    "missing_allowed_tools", missingAllowedTools
            )));
        }
        boolean disableToolCalling = false;
        boolean disableStreaming = false;
        String unsupportedVisionMessage = null;

        if (capability == null || capability.modelCapability() == null) {
            return new RuntimePolicy(definitions, false, false, null, noExposedToolsMessage);
        }

        ModelCapability modelCapability = capability.modelCapability();
        if (isCapabilityFalse(modelCapability.supportsToolCalling())) {
            disableToolCalling = true;
            if (!definitions.isEmpty()) {
                addCapabilityWarning(traceRecorder, 0, capability, "supportsToolCalling", "TOOLS_NOT_EXPOSED",
                        "模型 capability 标记为不支持 tool calling，本次请求不会向模型暴露 tools。");
            }
            definitions = List.of();
            if (!allowedTools.isEmpty()) {
                noExposedToolsMessage = "no tools exposed to worker: provider capability disables tool calling";
            }
        } else if (isCapabilityUnknown(modelCapability.supportsToolCalling()) && !definitions.isEmpty()) {
            addCapabilityWarning(traceRecorder, 0, capability, "supportsToolCalling", "KEEP_EXISTING_BEHAVIOR",
                    "模型 tool calling capability 未知，保持现有工具调用行为。");
        }

        if (spec.getHook() != null && spec.getHook().wantsStreaming()) {
            if (isCapabilityFalse(modelCapability.supportsStreaming())) {
                disableStreaming = true;
                addCapabilityWarning(traceRecorder, 0, capability, "supportsStreaming", "STREAMING_DISABLED_FALLBACK_TO_CHAT",
                        "模型 capability 标记为不支持 streaming，已自动降级为非流式请求。");
            } else if (isCapabilityUnknown(modelCapability.supportsStreaming())) {
                addCapabilityWarning(traceRecorder, 0, capability, "supportsStreaming", "KEEP_EXISTING_BEHAVIOR",
                        "模型 streaming capability 未知，保持现有流式请求行为。");
            }
        }

        if (isCapabilityFalse(modelCapability.supportsVision()) && containsImageContent(messages)) {
            unsupportedVisionMessage = "当前模型不支持图片输入，请换用支持 vision 的模型，或改用文本描述后重试。";
            addCapabilityWarning(traceRecorder, 0, capability, "supportsVision", "REJECT_IMAGE_INPUT",
                    unsupportedVisionMessage);
        }

        int window = modelCapability.contextWindowTokens();
        if (window > 0) {
            int estimate = estimateMessageTokens(messages);
            if (estimate > Math.max(1, (int) (window * 0.85))) {
                addCapabilityWarning(traceRecorder, 0, capability, "contextWindowTokens", "CONTEXT_NEAR_LIMIT",
                        "估算上下文接近模型窗口，后续应优先使用已有压缩/裁剪链路。");
            }
        }

        return new RuntimePolicy(definitions, disableToolCalling, disableStreaming, unsupportedVisionMessage, noExposedToolsMessage);
    }

    private List<String> normalizeToolNames(List<String> names) {
        if (names == null) {
            return List.of();
        }
        return names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String schemaName(Map<String, Object> schema) {
        if (schema == null) {
            return "";
        }
        Object fn = schema.get("function");
        if (fn instanceof Map<?, ?> fnMap) {
            Object name = fnMap.get("name");
            if (name instanceof String s) {
                return s;
            }
        }
        Object name = schema.get("name");
        return name instanceof String s ? s : "";
    }

    private void addCapabilityWarning(
            TraceRecorder traceRecorder,
            int iteration,
            ProviderCapability capability,
            String capabilityName,
            String decision,
            String message
    ) {
        RuntimeCapabilityWarning warning = new RuntimeCapabilityWarning(
                capability != null ? capability.providerName() : ProviderCapabilityResolver.UNKNOWN,
                capability != null && capability.modelCapability() != null ? capability.modelCapability().model() : "",
                capabilityName,
                decision,
                message
        );
        traceRecorder.recordRunEvent("capability_warning", metadata(iteration, warning.toMap()));
        log.warn("runtime capability warning: {}", warning.toMap());
    }

    private boolean isCapabilityFalse(String value) {
        return ProviderCapabilityResolver.FALSE.equalsIgnoreCase(value != null ? value.trim() : "");
    }

    private boolean isCapabilityUnknown(String value) {
        return ProviderCapabilityResolver.UNKNOWN.equalsIgnoreCase(value != null ? value.trim() : "");
    }

    private boolean containsImageContent(List<Map<String, Object>> messages) {
        if (messages == null) {
            return false;
        }
        for (Map<String, Object> message : messages) {
            if (message == null) {
                continue;
            }
            if (contentContainsImage(message.get("content"))) {
                return true;
            }
        }
        return false;
    }

    private boolean contentContainsImage(Object content) {
        if (content instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Object type = raw.get("type");
                    if ("image_url".equals(type) || "input_image".equals(type) || "image".equals(type)) {
                        return true;
                    }
                    if (raw.containsKey("image_url") || raw.containsKey("image")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int estimateMessageTokens(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int chars = 0;
        for (Map<String, Object> message : messages) {
            if (message == null) {
                continue;
            }
            chars += estimateContentChars(message.get("content"));
        }
        return Math.max(1, chars / 4);
    }

    private int estimateContentChars(Object content) {
        if (content == null) {
            return 0;
        }
        if (content instanceof String s) {
            return s.length();
        }
        if (content instanceof List<?> list) {
            int total = 0;
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Object text = raw.get("text");
                    if (text != null) {
                        total += String.valueOf(text).length();
                    }
                    Object imageUrl = raw.get("image_url");
                    if (imageUrl != null) {
                        total += 256;
                    }
                } else if (item != null) {
                    total += String.valueOf(item).length();
                }
            }
            return total;
        }
        return String.valueOf(content).length();
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
            AgentRunSpec spec,
            int iteration
    ) {
        List<Map<String, Object>> results = new ArrayList<>();

        // 遍历每个工具调用
        for (ToolCallRequest toolCall : toolCalls) {
            ToolExecution out = executeSingleTool(tools, toolCall, spec, iteration); // 执行单个工具
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
            AgentRunSpec spec,
            int iteration
    ) {
        CompletionService<IndexedToolExecution> cs = new ExecutorCompletionService<>(toolExecutor);
        int submitted = 0;
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCallRequest toolCall = toolCalls.get(i);
            int index = i;
            cs.submit(() -> new IndexedToolExecution(index, executeSingleTool(tools, toolCall, spec, iteration)));
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
    private ToolExecution executeSingleTool(ToolRegistry tools, ToolCallRequest toolCall, AgentRunSpec spec, int iteration) {
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
        event.put("iteration", iteration);
        event.put("tool_call_id", toolCall.getId());
        if (tools != null) {
            ToolRegistry.ToolPolicy policy = toolExecutionPolicy(tools).policyFor(toolName, tools.get(toolName));
            event.put("read_only", policy.readOnly());
            event.put("exclusive", policy.exclusive());
            event.put("concurrent_safe", policy.concurrentSafe());
            event.put("risk", policy.risk());
        }
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
                toolExecutionPolicy(tools).maxToolResultChars(spec.getMaxToolResultChars())
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

    private ToolExecutionPolicy toolExecutionPolicy(ToolRegistry tools) {
        return tools != null ? tools.executionPolicy() : ToolExecutionPolicy.defaultPolicy();
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

    private record RuntimePolicy(
            List<Map<String, Object>> toolDefinitions,
            boolean disableToolCalling,
            boolean disableStreaming,
            String unsupportedVisionMessage,
            String noExposedToolsMessage
    ) {}

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
