package ricbot.domain.agent.middleware;

import ricbot.integration.llm.api.LLMResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Deterministic onion composition. Earlier middleware is outermost. */
public final class AgentMiddlewareChain {
    private final List<AgentMiddleware> middleware;
    public AgentMiddlewareChain(List<AgentMiddleware> middleware) {
        this.middleware = List.copyOf(middleware != null ? middleware : List.of());
        long unique = this.middleware.stream().map(AgentMiddleware::id).distinct().count();
        if (unique != this.middleware.size()) throw new IllegalArgumentException("middleware ids must be unique");
    }
    public LLMResponse model(AgentMiddleware.ModelCallContext context, AgentMiddleware.ModelChain terminal) throws Exception {
        AgentMiddleware.ModelChain chain = terminal;
        for (int index = middleware.size() - 1; index >= 0; index--) {
            AgentMiddleware item = middleware.get(index);
            AgentMiddleware.ModelChain next = chain;
            chain = value -> item.aroundModelCall(value, next);
        }
        return chain.proceed(context);
    }
    public Object tool(AgentMiddleware.ToolCallContext context, AgentMiddleware.ToolChain terminal) throws Exception {
        AgentMiddleware.ToolChain chain = terminal;
        for (int index = middleware.size() - 1; index >= 0; index--) {
            AgentMiddleware item = middleware.get(index);
            AgentMiddleware.ToolChain next = chain;
            chain = value -> item.aroundToolCall(value, next);
        }
        return chain.proceed(context);
    }
    public Object compression(AgentMiddleware.CompressionContext context, AgentMiddleware.CompressionChain terminal) throws Exception {
        AgentMiddleware.CompressionChain chain = terminal;
        for (int index = middleware.size() - 1; index >= 0; index--) {
            AgentMiddleware item = middleware.get(index);
            AgentMiddleware.CompressionChain next = chain;
            chain = value -> item.aroundCompression(value, next);
        }
        return chain.proceed(context);
    }
    public String systemPrompt(AgentMiddleware.MiddlewareContext context, String prompt) {
        String result = prompt;
        for (AgentMiddleware item : middleware) result = item.transformSystemPrompt(context, result);
        return result;
    }
    public List<Map<String, Object>> tools(AgentMiddleware.MiddlewareContext context, List<Map<String, Object>> tools) {
        List<Map<String, Object>> result = new ArrayList<>(tools != null ? tools : List.of());
        for (AgentMiddleware item : middleware) result = new ArrayList<>(item.listTools(context, List.copyOf(result)));
        return List.copyOf(result);
    }
    public Map<String, Integer> stateVersions() {
        return middleware.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                AgentMiddleware::id, AgentMiddleware::stateVersion));
    }

    public void validateStateVersions(Object rawState) {
        if (!(rawState instanceof Map<?, ?> state) || !(state.get("versions") instanceof Map<?, ?> versions)) {
            throw new IllegalStateException("middleware state is missing version metadata");
        }
        Map<String, Integer> expected = stateVersions();
        if (versions.size() != expected.size()) throw new IllegalStateException("unknown middleware state set");
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            Object value = versions.get(entry.getKey());
            if (!(value instanceof Number number) || number.intValue() != entry.getValue()) {
                throw new IllegalStateException("unsupported middleware state version: " + entry.getKey());
            }
        }
    }
}
