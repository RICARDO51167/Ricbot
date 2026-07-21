package ricbot.domain.agent;

import ricbot.integration.llm.api.LLMProvider;

import java.util.concurrent.ExecutorService;

/**
 * Compatibility facade. New runtime orchestration belongs to {@link GraphRunService}.
 */
public class AgentRunner extends GraphRunService {
    public AgentRunner(LLMProvider provider) {
        super(provider);
    }

    public AgentRunner(LLMProvider provider, ExecutorService toolExecutor, boolean ownsToolExecutor) {
        super(provider, toolExecutor, ownsToolExecutor);
    }
}
