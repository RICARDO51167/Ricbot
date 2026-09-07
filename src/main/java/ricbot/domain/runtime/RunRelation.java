package ricbot.domain.runtime;

public record RunRelation(String parentRunId, String childRunId, String retryOfRunId, String dependencyRunId) {
    public RunRelation {
        parentRunId = clean(parentRunId); childRunId = required(childRunId, "childRunId");
        retryOfRunId = clean(retryOfRunId); dependencyRunId = clean(dependencyRunId);
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
    private static String required(String value, String field) {
        String clean = clean(value); if (clean.isBlank()) throw new IllegalArgumentException(field + " is required"); return clean;
    }
}
