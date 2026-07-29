package ricbot.domain.agent.context.dto;

import java.util.List;

/** Stable five-section context contract shared by the compact model and the main model. */
public record StructuredContextSummary(
        String taskOverview,
        String currentState,
        List<String> importantDiscoveries,
        List<String> nextSteps,
        List<String> contextToPreserve
) {
    public StructuredContextSummary {
        taskOverview = clean(taskOverview);
        currentState = clean(currentState);
        importantDiscoveries = clean(importantDiscoveries);
        nextSteps = clean(nextSteps);
        contextToPreserve = clean(contextToPreserve);
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull)
                .map(String::trim).filter(value -> !value.isBlank()).toList();
    }
}
