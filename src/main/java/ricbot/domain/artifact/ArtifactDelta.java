package ricbot.domain.artifact;

import java.util.List;
import java.util.Map;

/** Provider-neutral artifact change produced by a run. */
public record ArtifactDelta(String deltaId, String kind, String baseReference,
                            List<String> artifactReferences, Map<String, Object> metadata) {
    public ArtifactDelta {
        if (deltaId == null || deltaId.isBlank() || kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("deltaId and kind are required");
        }
        baseReference = baseReference != null ? baseReference.trim() : "";
        artifactReferences = List.copyOf(artifactReferences != null ? artifactReferences : List.of());
        metadata = Map.copyOf(metadata != null ? metadata : Map.of());
    }
}
