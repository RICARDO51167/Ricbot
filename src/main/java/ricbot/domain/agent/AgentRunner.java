package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.hook.AgentHook;
import ricbot.domain.hook.AgentHookContext;
import ricbot.tool.api.ToolRegistry;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Agent 运行器，负责执行 Agent 主循环、处理模型调用、工具调用及生命周期钩子。
 */
public class AgentRunner implements AutoCloseable {

    public static final int MAX_INJECTIONS_PER_TURN = 3;

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
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

    private final LLMProvider provider;
    private final ExecutorService toolExecutor;
    private final boolean ownsToolExecutor;

    public AgentRunner(LLMProvider provider) {
        this(provider, SHARED_TOOL_EXECUTOR, false);
    }

    public AgentRunner(LLMProvider provider, ExecutorService toolExecutor, boolean ownsToolExecutor) {
        this.provider = provider;
        this.toolExecutor = toolExecutor != null ? toolExecutor : SHARED_TOOL_EXECUTOR;
        this.ownsToolExecutor = ownsToolExecutor;
    }

    public AgentRunResult run(AgentRunSpec spec) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>(
                spec.getInitialMessages() != null ? spec.getInitialMessages() : List.of()
        );

        AgentRunResult result = new AgentRunResult();
        List<String> toolsUsed = new ArrayList<>();
        List<Map<String, Object>> toolEvents = new ArrayList<>();
        boolean hadInjections = false;
        String finalContent = null;
        String stopReason = "stop";
        String stopDetail = null;
        int consecutiveToolTurns = 0;
        int blankAssistantTurns = 0;
        int toolErrorTurns = 0;
        int injectionRounds = 0;

        AgentHook hook = spec.getHook();
        ToolRegistry tools = spec.getTools();

        for (int iteration = 1; iteration <= spec.getMaxIterations(); iteration++) {
            AgentHookContext context = new AgentHookContext()
                    .setMessages(messages)
                    .setIteration(iteration)
                    .setSessionKey(spec.getSessionKey());

            if (hook != null) {
                safeHook(() -> hook.beforeIteration(context), hook);
            }

            LLMResponse response;
            try {
                boolean wantsStreaming = hook != null && hook.wantsStreaming();

                if (wantsStreaming) {
                    int it = iteration;
                    response = provider.chatStream(
                            messages,
                            tools != null ? tools.getDefinitions() : List.of(),
                            spec.getModel(),
                            null,
                            null,
                            null,
                            null,
                            delta -> {
                                AgentHookContext streamCtx = new AgentHookContext()
                                        .setMessages(messages)
                                        .setIteration(it)
                                        .setSessionKey(spec.getSessionKey());
                                if (hook != null) {
                                    safeHook(() -> hook.onStream(streamCtx, delta), hook);
                                }
                            },
                            resuming -> {
                                AgentHookContext streamCtx = new AgentHookContext()
                                        .setMessages(messages)
                                        .setIteration(it)
                                        .setSessionKey(spec.getSessionKey());
                                if (hook != null) {
                                    safeHook(() -> hook.onStreamEnd(streamCtx, resuming.hasToolCalls()), hook);
                                }
                            }
                    );
                } else {
                    String retryMode = spec.getProviderRetryMode() != null ? spec.getProviderRetryMode().trim() : "";
                    if ("none".equalsIgnoreCase(retryMode) || "off".equalsIgnoreCase(retryMode) || "disabled".equalsIgnoreCase(retryMode)) {
                        response = provider.chat(
                                messages,
                                tools != null ? tools.getDefinitions() : List.of(),
                                spec.getModel(),
                                null,
                                null,
                                null,
                                null
                        );
                    } else {
                        response = provider.chatWithRetry(
                                messages,
                                tools != null ? tools.getDefinitions() : List.of(),
                                spec.getModel()
                        );
                    }
                }
            } catch (Exception e) {
                if (hook != null) {
                    safeHook(() -> hook.onError(context, e), hook);
                }

                result.setFinalContent(spec.getErrorMessage());
                result.setMessages(messages);
                result.setStopReason("error");
                result.setError(e.getMessage());
                result.setToolsUsed(toolsUsed);
                result.setToolEvents(toolEvents);
                return result;
            }

            context.setResponse(response)
                    .setToolCalls(response.getToolCalls())
                    .setUsage(response.getUsage());

            if (hook != null) {
                safeHook(() -> hook.afterIteration(context), hook);
            }

            if (response.getUsage() != null) {
                result.setUsage(response.getUsage());
            }

            Map<String, Object> assistantMessage = new LinkedHashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", response.getContent());

            if (response.hasToolCalls()) {
                List<Map<String, Object>> toolCallDicts = response.getToolCalls().stream()
                        .map(ToolCallRequest::toOpenAIToolCall)
                        .collect(Collectors.toList());
                assistantMessage.put("tool_calls", toolCallDicts);
            }

            messages.add(assistantMessage);
            if (response.getContent() == null || response.getContent().isBlank()) {
                blankAssistantTurns++;
            }

            if (spec.getCheckpointCallback() != null && response.hasToolCalls()) {
                Map<String, Object> checkpoint = new LinkedHashMap<>();
                checkpoint.put("assistant_message", assistantMessage);
                checkpoint.put("completed_tool_results", new ArrayList<>());
                checkpoint.put("pending_tool_calls", response.getToolCalls().stream()
                        .map(ToolCallRequest::toOpenAIToolCall)
                        .toList());
                spec.getCheckpointCallback().accept(checkpoint);
            }

            if (!response.hasToolCalls()) {
                finalContent = response.getContent();
                if (hook != null) {
                    finalContent = hook.finalizeContent(context, finalContent);
                }
                stopReason = response.getFinishReason() != null ? response.getFinishReason() : "stop";
                break;
            }
            consecutiveToolTurns++;

            if (hook != null) {
                safeHook(() -> hook.beforeExecuteTools(context), hook);
            }

            int toolEventStart = toolEvents.size();
            List<Map<String, Object>> toolResults = spec.isConcurrentTools()
                    ? executeToolsConcurrent(tools, response.getToolCalls(), toolsUsed, toolEvents, spec)
                    : executeToolsSequential(tools, response.getToolCalls(), toolsUsed, toolEvents, spec);

            messages.addAll(toolResults);

            if (hook != null) {
                safeHook(() -> hook.afterExecuteTools(context), hook);
            }

            if (spec.getCheckpointCallback() != null) {
                Map<String, Object> checkpoint = new LinkedHashMap<>();
                checkpoint.put("assistant_message", assistantMessage);
                checkpoint.put("completed_tool_results", toolResults);
                checkpoint.put("pending_tool_calls", List.of());
                spec.getCheckpointCallback().accept(checkpoint);
            }

            if (spec.isFailOnToolError()) {
                Map<String, Object> firstError = firstToolError(toolEvents, toolEventStart);
                if (firstError != null) {
                    stopReason = "tool_error";
                    stopDetail = String.valueOf(firstError.getOrDefault("detail", "tool_error"));
                    finalContent = spec.getErrorMessage();
                    break;
                }
            }
            if (firstToolError(toolEvents, toolEventStart) != null) {
                toolErrorTurns++;
            }

            if (spec.getInjectionCallback() != null) {
                List<Map<String, Object>> injected;
                try {
                    injected = spec.getInjectionCallback().inject();
                } catch (Exception e) {
                    injected = null;
                    if (hook != null) {
                        safeHook(() -> hook.onError(context, e), hook);
                    } else {
                        log.warn("注入回调失败: sessionKey={}", spec.getSessionKey(), e);
                    }
                }

                List<Map<String, Object>> normalized = normalizeInjectedMessages(injected, MAX_INJECTIONS_PER_TURN);
                if (!normalized.isEmpty()) {
                    messages.addAll(normalized);
                    hadInjections = true;
                    injectionRounds++;
                }
            }
        }

        if (finalContent == null) {
            stopReason = classifyMaxIterationReason(
                    spec.getMaxIterations(),
                    consecutiveToolTurns,
                    blankAssistantTurns,
                    toolErrorTurns,
                    injectionRounds
            );
            finalContent = spec.getMaxIterationsMessage();
        }

        result.setFinalContent(finalContent);
        result.setMessages(messages);
        result.setStopReason(stopReason);
        if (stopDetail != null && !stopDetail.isBlank()) {
            result.setError(stopDetail);
        } else if ("max_iterations".equals(stopReason) && consecutiveToolTurns > 0) {
            result.setError("max_iterations: tool_calls_turns=" + consecutiveToolTurns + ", had_injections=" + hadInjections);
        }
        result.setHadInjections(hadInjections);
        result.setToolsUsed(toolsUsed);
        result.setToolEvents(toolEvents);
        return result;
    }

    private List<Map<String, Object>> executeToolsSequential(
            ToolRegistry tools,
            List<ToolCallRequest> toolCalls,
            List<String> toolsUsed,
            List<Map<String, Object>> toolEvents,
            AgentRunSpec spec
    ) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (ToolCallRequest toolCall : toolCalls) {
            ToolExecution out = executeSingleTool(tools, toolCall, spec);
            toolsUsed.add(out.name());
            toolEvents.add(out.event());
            results.add(out.toolMsg());
        }

        return results;
    }

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

    private ToolExecution executeSingleTool(ToolRegistry tools, ToolCallRequest toolCall, AgentRunSpec spec) {
        String toolName = toolCall.getName();

        Object result;
        String status = "ok";
        String detail;
        Object contentObj;

        try {
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

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", toolName);
        event.put("status", status);
        event.put("detail", detail);

        Map<String, Object> toolMsg = buildToolMessage(
                toolCall.getId(),
                toolName,
                "ok".equals(status),
                result,
                "error".equals(status) ? detail : null,
                contentObj,
                spec.getMaxToolResultChars()
        );

        return new ToolExecution(toolName, toolMsg, event);
    }

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

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

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

    private record ToolExecution(String name, Map<String, Object> toolMsg, Map<String, Object> event) {}

    private record IndexedToolExecution(int index, ToolExecution execution) {}

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
