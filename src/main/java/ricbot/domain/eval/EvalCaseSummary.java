package ricbot.domain.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EvalCaseSummary(
        String id,
        String status,
        String failureKind,
        long durationMs,
        List<String> tools,
        String artifactPath
) {
    public EvalCaseSummary {
        id = clean(id);
        status = clean(status);
        failureKind = clean(failureKind);
        tools = tools != null ? List.copyOf(tools) : List.of();
        artifactPath = clean(artifactPath);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", status);
        out.put("failureKind", failureKind);
        out.put("durationMs", durationMs);
        out.put("tools", tools);
        out.put("artifactPath", artifactPath);
        return out;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
