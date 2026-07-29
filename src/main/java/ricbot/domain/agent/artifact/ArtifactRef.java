package ricbot.domain.agent.artifact;

import java.time.Instant;

/** Durable reference returned to the model instead of silently truncating content. */
public record ArtifactRef(String artifactId, String uri, String path, String sha256, long byteSize,
                          long charCount, String mediaType, String summary, String source,
                          String rootRunId, String runId, String taskId, Instant createdAt) {
    public ArtifactRef {
        artifactId = required(artifactId, "artifactId");
        uri = required(uri, "uri");
        path = required(path, "path");
        sha256 = required(sha256, "sha256");
        mediaType = mediaType != null && !mediaType.isBlank() ? mediaType : "text/plain; charset=utf-8";
        summary = summary != null ? summary : "";
        source = source != null ? source : "";
        rootRunId = clean(rootRunId);
        runId = required(runId, "runId");
        taskId = clean(taskId);
        createdAt = createdAt != null ? createdAt : Instant.now();
    }
    private static String required(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
