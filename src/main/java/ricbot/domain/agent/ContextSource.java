package ricbot.domain.agent;

import java.util.LinkedHashMap;
import java.util.Map;

record ContextSource(String type, String id, String path, String label, double score, Map<String, Object> metadata) {
    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("id", id);
        out.put("path", path);
        out.put("label", label);
        out.put("score", Math.round(score * 1000.0d) / 1000.0d);
        if (metadata != null) {
            out.putAll(metadata);
        }
        return out;
    }

    static ContextSource of(String type, String id, String path, String label, double score) {
        return of(type, id, path, label, score, Map.of());
    }

    static ContextSource of(String type, String id, String path, String label, double score, Map<String, Object> metadata) {
        return new ContextSource(
                type != null ? type : "",
                id != null ? id : "",
                path != null ? path : "",
                label != null ? label : "",
                Math.max(0d, Math.min(1d, score)),
                metadata != null ? new LinkedHashMap<>(metadata) : Map.of()
        );
    }
}
