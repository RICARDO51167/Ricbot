package ricbot.domain.hook;

import ricbot.domain.agent.event.AgentEvent;

/** Minimal internal lifecycle callback used for CLI streaming and tool progress. */
public abstract class AgentHook {
    public boolean wantsStreaming() { return false; }
    public void afterIteration(AgentHookContext context) throws Exception {}
    public void beforeExecuteTools(AgentHookContext context) throws Exception {}
    public void onStream(AgentHookContext context, String delta) throws Exception {}
    public void onStreamEnd(AgentHookContext context, boolean resuming) throws Exception {}
    /** Typed protocol entrypoint; legacy stream callbacks are boundary adapters only. */
    public void onEvent(AgentHookContext context, AgentEvent event) throws Exception {
        if (event instanceof AgentEvent.MessageDelta delta) onStream(context, delta.delta());
        else if (event instanceof AgentEvent.MessageEnd end) {
            onStreamEnd(context, "tool_calls".equalsIgnoreCase(end.finishReason()));
        }
    }
    public String finalizeContent(AgentHookContext context, String content) { return content; }
}
