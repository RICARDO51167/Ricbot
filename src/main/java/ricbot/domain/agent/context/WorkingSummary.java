package ricbot.domain.agent.context;

import ricbot.domain.agent.artifact.ArtifactRef;
import ricbot.domain.agent.context.dto.StructuredContextSummary;
import java.time.Instant;
import java.util.List;

/** Run-local summary layer; it is deliberately separate from long-term memory and messages. */
public record WorkingSummary(
        int schemaVersion, StructuredContextSummary summary, List<String> sourceSegmentIds,
        ArtifactRef sourceArtifact, String predecessorHash, String model, String summaryHash, Instant createdAt
) {
    public static final int SCHEMA_VERSION = 1;
    public WorkingSummary {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported WorkingSummary schema: " + schemaVersion);
        sourceSegmentIds = List.copyOf(sourceSegmentIds != null ? sourceSegmentIds : List.of());
        predecessorHash = predecessorHash != null ? predecessorHash : "";
        model = model != null ? model : "";
        summaryHash = summaryHash != null ? summaryHash : "";
        createdAt = createdAt != null ? createdAt : Instant.now();
    }
}
