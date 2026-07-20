package ricbot.domain.agent;

import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeCapabilityWarning(
        String provider,
        String model,
        String capability,
        String decision,
        String message
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", provider);
        map.put("model", model);
        map.put("capability", capability);
        map.put("decision", decision);
        map.put("message", message);
        return map;
    }
}
