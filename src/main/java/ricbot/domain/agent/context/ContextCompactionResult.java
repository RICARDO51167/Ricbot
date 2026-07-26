package ricbot.domain.agent.context;

import java.util.List;
import java.util.Map;

public record ContextCompactionResult(
        boolean compacted,
        boolean degraded,
        List<Map<String, Object>> eventMessages,
        List<Map<String, Object>> activeMessages,
        List<String> sourceMessageIds,
        String model,
        int sourceTokens,
        int resultTokens,
        String promptDigest,
        String resultDigest
) {
    public ContextCompactionResult {
        eventMessages = immutable(eventMessages);
        activeMessages = immutable(activeMessages);
        sourceMessageIds = List.copyOf(sourceMessageIds != null ? sourceMessageIds : List.of());
        model = model != null ? model : "";
        promptDigest = promptDigest != null ? promptDigest : "";
        resultDigest = resultDigest != null ? resultDigest : "";
    }
    private static List<Map<String, Object>> immutable(List<Map<String, Object>> messages) {
        return messages == null ? List.of() : messages.stream()
                .map(message -> Map.copyOf(message != null ? message : Map.of())).toList();
    }
}
