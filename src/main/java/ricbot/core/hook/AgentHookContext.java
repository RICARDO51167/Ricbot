package ricbot.core.hook;


import ricbot.llm.api.LLMResponse;
import ricbot.llm.api.ToolCallRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hook 上下文
 *
 * 主要目标：
 * 1. 给 Hook 暴露当前执行状态
 * 2. 包含：
 *    - 当前 messages
 *    - 当前模型响应
 *    - 当前 tool calls
 *    - usage
 *    - iteration
 *    - stop reason
 *
 * 对应 Python: AgentHookContext
 */
public class AgentHookContext {

    private List<Map<String, Object>> messages = new ArrayList<>();
    private LLMResponse response;
    private List<ToolCallRequest> toolCalls = new ArrayList<>();
    private Map<String, Integer> usage;
    private int iteration;
    private String stopReason;
    private String sessionKey;
    private Object extra;

    public AgentHookContext() {
    }

    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    public AgentHookContext setMessages(List<Map<String, Object>> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
        return this;
    }

    public LLMResponse getResponse() {
        return response;
    }

    public AgentHookContext setResponse(LLMResponse response) {
        this.response = response;
        return this;
    }

    public List<ToolCallRequest> getToolCalls() {
        return toolCalls;
    }

    public AgentHookContext setToolCalls(List<ToolCallRequest> toolCalls) {
        this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<>();
        return this;
    }

    public Map<String, Integer> getUsage() {
        return usage;
    }

    public AgentHookContext setUsage(Map<String, Integer> usage) {
        this.usage = usage;
        return this;
    }

    public int getIteration() {
        return iteration;
    }

    public AgentHookContext setIteration(int iteration) {
        this.iteration = iteration;
        return this;
    }

    public String getStopReason() {
        return stopReason;
    }

    public AgentHookContext setStopReason(String stopReason) {
        this.stopReason = stopReason;
        return this;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public AgentHookContext setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
        return this;
    }

    public Object getExtra() {
        return extra;
    }

    public AgentHookContext setExtra(Object extra) {
        this.extra = extra;
        return this;
    }
}