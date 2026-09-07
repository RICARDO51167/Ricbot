package ricbot.tool.api;

import java.util.List;
import java.util.Map;

public record ExternalActionResult(String actionId, String activationId, String invocationDigest,
                                   Status status, Map<String, Object> result, List<String> artifactUris) {
    public enum Status { SUCCEEDED, FAILED, CANCELLED }
    public ExternalActionResult { result = Map.copyOf(result != null ? result : Map.of()); artifactUris = List.copyOf(artifactUris != null ? artifactUris : List.of()); }
}
