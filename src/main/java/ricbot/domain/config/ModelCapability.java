package ricbot.domain.config;

import java.util.LinkedHashMap;
import java.util.Map;

public record ModelCapability(
        String model,
        String supportsToolCalling,
        String supportsStreaming,
        String supportsVision,
        String supportsJsonMode,
        String supportsReasoningEffort,
        int contextWindowTokens,
        int maxOutputTokens,
        String apiMode
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("model", model);
        map.put("supportsToolCalling", supportsToolCalling);
        map.put("supportsStreaming", supportsStreaming);
        map.put("supportsVision", supportsVision);
        map.put("supportsJsonMode", supportsJsonMode);
        map.put("supportsReasoningEffort", supportsReasoningEffort);
        map.put("contextWindowTokens", contextWindowTokens);
        map.put("maxOutputTokens", maxOutputTokens);
        map.put("apiMode", apiMode);
        return map;
    }
}
