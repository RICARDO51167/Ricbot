package ricbot.domain.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EvalRunDetail(
        EvalRunSummary summary,
        List<EvalCaseSummary> cases,
        String reportMarkdown,
        Map<String, Object> manifest,
        List<String> warnings
) {
    public EvalRunDetail {
        cases = cases != null ? List.copyOf(cases) : List.of();
        reportMarkdown = reportMarkdown != null ? reportMarkdown : "";
        manifest = manifest != null ? Map.copyOf(manifest) : Map.of();
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> mergedWarnings = new ArrayList<>();
        if (summary != null && summary.getWarnings() != null) {
            mergedWarnings.addAll(summary.getWarnings());
        }
        mergedWarnings.addAll(warnings);
        out.put("summary", summary != null ? summary.toMap() : Map.of());
        out.put("cases", cases.stream().map(EvalCaseSummary::toMap).toList());
        out.put("reportMarkdown", reportMarkdown);
        out.put("manifest", manifest);
        out.put("warnings", mergedWarnings.stream().distinct().toList());
        return out;
    }
}
