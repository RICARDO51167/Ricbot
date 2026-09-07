package ricbot.domain.agent.context;

import java.util.List;
import java.util.Map;

/** Immutable result of compiling one concrete provider request. */
public record ModelInputPlan(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        List<ContextCandidate> candidates,
        Map<String, Object> runtimeHints,
        TokenEstimate tokens,
        String requestDigest,
        boolean toolExposureNarrowed
) {
    public ModelInputPlan {
        messages = List.copyOf(messages != null ? messages : List.of());
        tools = List.copyOf(tools != null ? tools : List.of());
        candidates = List.copyOf(candidates != null ? candidates : List.of());
        runtimeHints = Map.copyOf(runtimeHints != null ? runtimeHints : Map.of());
        requestDigest = requestDigest != null ? requestDigest : "";
    }
}
