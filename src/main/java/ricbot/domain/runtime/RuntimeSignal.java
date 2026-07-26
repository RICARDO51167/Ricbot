package ricbot.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeSignal(String signalId, String type, Map<String, Object> payload) {
    public RuntimeSignal {
        signalId = required(signalId, "signalId");
        type = required(type, "type");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload != null ? payload : Map.of()));
    }
    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
