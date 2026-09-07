package ricbot.tool.api;

import java.time.Instant;
import java.util.Map;

public record ExternalActionRequest(String actionId, String runId, String activationId, String toolId,
                                    String invocationDigest, Map<String, Object> arguments,
                                    Status status, Instant createdAt) {
    public enum Status { WAITING, SUCCEEDED, FAILED, CANCELLED }
    public ExternalActionRequest { arguments = Map.copyOf(arguments != null ? arguments : Map.of()); status = status != null ? status : Status.WAITING; createdAt = createdAt != null ? createdAt : Instant.now(); }
}
