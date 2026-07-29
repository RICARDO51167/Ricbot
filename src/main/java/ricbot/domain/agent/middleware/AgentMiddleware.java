package ricbot.domain.agent.middleware;

import ricbot.integration.llm.api.LLMResponse;

import java.util.List;
import java.util.Map;

/** Stable middleware boundary; state must be JSON-serializable and stored by middleware id. */
public interface AgentMiddleware {
    String id();
    default int stateVersion() { return 1; }
    default LLMResponse aroundModelCall(ModelCallContext context, ModelChain next) throws Exception { return next.proceed(context); }
    default Object aroundToolCall(ToolCallContext context, ToolChain next) throws Exception { return next.proceed(context); }
    default Object aroundCompression(CompressionContext context, CompressionChain next) throws Exception { return next.proceed(context); }
    default String transformSystemPrompt(MiddlewareContext context, String prompt) { return prompt; }
    default List<Map<String, Object>> listTools(MiddlewareContext context, List<Map<String, Object>> tools) { return tools; }

    record MiddlewareContext(String runId, String sessionId, String taskId, Map<String, Object> state) { }
    record ModelCallContext(MiddlewareContext runtime, List<Map<String, Object>> messages,
                            List<Map<String, Object>> tools, String model, boolean finalizing) { }
    record ToolCallContext(MiddlewareContext runtime, String callId, String tool, Map<String, Object> arguments) { }
    record CompressionContext(MiddlewareContext runtime, List<Map<String, Object>> messages, String model) { }
    @FunctionalInterface interface ModelChain { LLMResponse proceed(ModelCallContext context) throws Exception; }
    @FunctionalInterface interface ToolChain { Object proceed(ToolCallContext context) throws Exception; }
    @FunctionalInterface interface CompressionChain { Object proceed(CompressionContext context) throws Exception; }
}
