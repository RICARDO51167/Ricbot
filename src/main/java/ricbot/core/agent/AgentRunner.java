package ricbot.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.core.hook.AgentHook;
import ricbot.core.hook.AgentHookContext;
import ricbot.tool.api.ToolRegistry;
import ricbot.llm.api.LLMProvider;
import ricbot.llm.api.LLMResponse;
import ricbot.llm.api.ToolCallRequest;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 对应 Python: AgentRunner
 *
 * 主要目标：
 * 1. 执行单次 agent loop
 * 2. 处理模型调用、tool 调用、hook、checkpoint、注入消息
 */
public class AgentRunner {

    /**
     * 对应 Python: _MAX_INJECTIONS_PER_TURN
     */
    public static final int MAX_INJECTIONS_PER_TURN = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LLMProvider provider;

    public AgentRunner(LLMProvider provider) {
        this.provider = provider;
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
                    int finalIteration = iteration;
                    int finalIteration1 = iteration;
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
                                        .setIteration(finalIteration)
                                        .setSessionKey(spec.getSessionKey());
                                if (hook != null) {
                                    safeHook(() -> hook.onStream(streamCtx, delta), hook);
                                }
                            },
                            resuming -> {
                                AgentHookContext streamCtx = new AgentHookContext()
                                        .setMessages(messages)
                                        .setIteration(finalIteration1)
                                        .setSessionKey(spec.getSessionKey());
                                if (hook != null) {
                                    safeHook(() -> hook.onStreamEnd(streamCtx, resuming), hook);
                                }
                            }
                    );
                } else {
                    response = provider.chat(
                            messages,
                            tools != null ? tools.getDefinitions() : List.of(),
                            spec.getModel(),
                            null,
                            null,
                            null,
                            null
                    );
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

            // assistant 消息先入历史
            Map<String, Object> assistantMessage = new LinkedHashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", response.getContent());

            if (response.hasToolCalls()) {
                List<Map<String, Object>> toolCallDicts = response.getToolCalls().stream()
                        .map(ToolCallRequest::toOpenAIToolCall)
                        .collect(Collectors.toList());
                assistantMessage.put("tool_calls", toolCallDicts);
            }

            if (response.getReasoningContent() != null) {
                assistantMessage.put("reasoning_content", response.getReasoningContent());
            }
            if (response.getThinkingBlocks() != null) {
                assistantMessage.put("thinking_blocks", response.getThinkingBlocks());
            }

            messages.add(assistantMessage);

            // checkpoint
            if (spec.getCheckpointCallback() != null && response.hasToolCalls()) {
                Map<String, Object> checkpoint = new LinkedHashMap<>();
                checkpoint.put("assistant_message", assistantMessage);
                checkpoint.put("completed_tool_results", new ArrayList<>());
                checkpoint.put("pending_tool_calls", response.getToolCalls().stream()
                        .map(ToolCallRequest::toOpenAIToolCall)
                        .toList());
                spec.getCheckpointCallback().accept(checkpoint);
            }

            // 没有 tool call，结束
            if (!response.hasToolCalls()) {
                finalContent = response.getContent();
                if (hook != null) {
                    finalContent = hook.finalizeContent(context, finalContent);
                }
                stopReason = response.getFinishReason() != null ? response.getFinishReason() : "stop";
                break;
            }

            // tool 前 hook
            if (hook != null) {
                safeHook(() -> hook.beforeExecuteTools(context), hook);
            }

            List<Map<String, Object>> toolResults = spec.isConcurrentTools()
                    ? executeToolsConcurrent(tools, response.getToolCalls(), toolsUsed, toolEvents, spec)
                    : executeToolsSequential(tools, response.getToolCalls(), toolsUsed, toolEvents, spec);

            messages.addAll(toolResults);

            // tool 后 hook
            if (hook != null) {
                safeHook(() -> hook.afterExecuteTools(context), hook);
            }

            // checkpoint 更新
            if (spec.getCheckpointCallback() != null) {
                Map<String, Object> checkpoint = new LinkedHashMap<>();
                checkpoint.put("assistant_message", assistantMessage);
                checkpoint.put("completed_tool_results", toolResults);
                checkpoint.put("pending_tool_calls", List.of());
                spec.getCheckpointCallback().accept(checkpoint);
            }

            // follow-up 注入
            if (spec.getInjectionCallback() != null) {
                List<Map<String, Object>> injected = spec.getInjectionCallback().inject();
                if (injected != null && !injected.isEmpty()) {
                    int take = Math.min(MAX_INJECTIONS_PER_TURN, injected.size());
                    messages.addAll(injected.subList(0, take));
                    hadInjections = true;
                }
            }
        }

        if (finalContent == null) {
            stopReason = "max_iterations";
            finalContent = spec.getMaxIterationsMessage();
        }

        result.setFinalContent(finalContent);
        result.setMessages(messages);
        result.setStopReason(stopReason);
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
            results.add(executeSingleTool(tools, toolCall, toolsUsed, toolEvents, spec));
        }

        return results;
    }

    private List<Map<String, Object>> executeToolsConcurrent(
            ToolRegistry tools,
            List<ToolCallRequest> toolCalls,
            List<String> toolsUsed,
            List<Map<String, Object>> toolEvents,
            AgentRunSpec spec
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, Math.min(toolCalls.size(), 4)));
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();

            for (ToolCallRequest toolCall : toolCalls) {
                futures.add(executor.submit(() -> executeSingleTool(tools, toolCall, toolsUsed, toolEvents, spec)));
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<String, Object> executeSingleTool(
            ToolRegistry tools,
            ToolCallRequest toolCall,
            List<String> toolsUsed,
            List<Map<String, Object>> toolEvents,
            AgentRunSpec spec
    ) {
        String toolName = toolCall.getName();
        toolsUsed.add(toolName);

        Object result;
        String status = "ok";
        String detail;

        try {
            result = tools.execute(toolName, toolCall.getArguments());
            detail = truncate(String.valueOf(result), 300);
            if (result instanceof String s && s.startsWith("Error")) {
                status = "error";
                if (spec.isFailOnToolError()) {
                    detail = s;
                }
            }
        } catch (Exception e) {
            result = "Error executing " + toolName + ": " + e.getMessage();
            status = "error";
            detail = truncate(String.valueOf(result), 300);
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", toolName);
        event.put("status", status);
        event.put("detail", detail);
        toolEvents.add(event);

        Map<String, Object> toolMsg = new LinkedHashMap<>();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", toolCall.getId());
        toolMsg.put("name", toolName);
        toolMsg.put("content", truncateToolResult(result, spec.getMaxToolResultChars()));

        return toolMsg;
    }

    private Object truncateToolResult(Object value, int maxChars) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (maxChars > 0 && s.length() > maxChars) {
            return s.substring(0, maxChars) + "\n... (truncated)";
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
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
