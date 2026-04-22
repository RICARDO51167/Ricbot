package ricbot.domain.agent;

import ricbot.domain.hook.AgentHook;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.session.Session;

import java.util.List;
import java.util.Map;

final class AgentRequestContext {

    private final InboundMessage message;
    private final String sessionKey;
    private final Session session;
    private final String combinedContext;
    private final PromptContextBundle promptContext;
    private final List<Map<String, Object>> history;
    private final List<Map<String, Object>> initialMessages;
    private final AgentHook hook;
    private final boolean userPersistedEarly;

    AgentRequestContext(
            InboundMessage message,
            String sessionKey,
            Session session,
            String combinedContext,
            PromptContextBundle promptContext,
            List<Map<String, Object>> history,
            List<Map<String, Object>> initialMessages,
            AgentHook hook,
            boolean userPersistedEarly
    ) {
        this.message = message;
        this.sessionKey = sessionKey;
        this.session = session;
        this.combinedContext = combinedContext;
        this.promptContext = promptContext;
        this.history = history;
        this.initialMessages = initialMessages;
        this.hook = hook;
        this.userPersistedEarly = userPersistedEarly;
    }

    InboundMessage message() {
        return message;
    }

    String sessionKey() {
        return sessionKey;
    }

    Session session() {
        return session;
    }

    String combinedContext() {
        return combinedContext;
    }

    PromptContextBundle promptContext() {
        return promptContext;
    }

    List<Map<String, Object>> history() {
        return history;
    }

    List<Map<String, Object>> initialMessages() {
        return initialMessages;
    }

    AgentHook hook() {
        return hook;
    }

    boolean userPersistedEarly() {
        return userPersistedEarly;
    }
}
