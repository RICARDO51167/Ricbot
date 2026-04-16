package ricbot.domain.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentRunner 运行结果
 */
public class AgentRunResult {

    private String finalContent;

    private List<String> toolsUsed = new ArrayList<>();

    private List<Map<String, Object>> messages = new ArrayList<>();

    private String stopReason = "stop";

    private boolean hadInjections = false;

    private Map<String, Integer> usage = new HashMap<>();

    private String error;

    private List<Map<String, Object>> toolEvents = new ArrayList<>();

    public String getFinalContent() {
        return finalContent;
    }

    public AgentRunResult setFinalContent(String finalContent) {
        this.finalContent = finalContent;
        return this;
    }

    public List<String> getToolsUsed() {
        return toolsUsed;
    }

    public AgentRunResult setToolsUsed(List<String> toolsUsed) {
        this.toolsUsed = toolsUsed != null ? toolsUsed : new ArrayList<>();
        return this;
    }

    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    public AgentRunResult setMessages(List<Map<String, Object>> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
        return this;
    }

    public String getStopReason() {
        return stopReason;
    }

    public AgentRunResult setStopReason(String stopReason) {
        this.stopReason = stopReason;
        return this;
    }

    public AgentRunResult setHadInjections(boolean hadInjections) {
        this.hadInjections = hadInjections;
        return this;
    }

    public Map<String, Integer> getUsage() {
        return usage;
    }

    public AgentRunResult setUsage(Map<String, Integer> usage) {
        this.usage = usage != null ? usage : new HashMap<>();
        return this;
    }

    public String getError() {
        return error;
    }

    public AgentRunResult setError(String error) {
        this.error = error;
        return this;
    }

    public List<Map<String, Object>> getToolEvents() {
        return toolEvents;
    }

    public AgentRunResult setToolEvents(List<Map<String, Object>> toolEvents) {
        this.toolEvents = toolEvents != null ? toolEvents : new ArrayList<>();
        return this;
    }
}