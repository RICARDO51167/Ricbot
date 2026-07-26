package ricbot.domain.agent;

import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes the model node; it does not own graph transitions or run state. */
public final class ModelNodeExecutor {
    private final LLMProvider provider;

    public ModelNodeExecutor(LLMProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public LLMResponse execute(AgentRunSpec spec, List<Map<String, Object>> messages,
                               List<Map<String, Object>> toolDefinitions, boolean stream,
                               LLMProvider.StreamDeltaHandler onDelta,
                               LLMProvider.StreamEndHandler onEnd) throws Exception {
        if (stream) {
            return provider.chatStream(messages, toolDefinitions, spec.getModel(),
                    null, null, null, null, onDelta, onEnd);
        }
        // The graph node retry policy is the sole retry owner.
        return provider.chat(messages, toolDefinitions, spec.getModel(), null, null, null, null);
    }
}
