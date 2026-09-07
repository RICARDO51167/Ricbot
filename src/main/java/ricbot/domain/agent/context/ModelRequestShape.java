package ricbot.domain.agent.context;

import java.util.List;
import java.util.Map;

/** Provider-neutral representation of everything that consumes the context window. */
public record ModelRequestShape(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        long outputReserveTokens,
        long contextWindowTokens,
        String model
) {
    public ModelRequestShape {
        messages = List.copyOf(messages != null ? messages : List.of());
        tools = List.copyOf(tools != null ? tools : List.of());
        outputReserveTokens = Math.max(0, outputReserveTokens);
        contextWindowTokens = Math.max(1, contextWindowTokens);
        model = model != null ? model : "";
    }
}
