package ricbot.tool.api;

import java.util.List;
import java.util.Map;

public sealed interface ToolResult permits ToolResult.Success, ToolResult.Failure, ToolResult.ExternalPending {
    List<ToolStateMutation> mutations();
    record Success(Object value, String preview, List<ToolStateMutation> mutations, Map<String, Object> metadata) implements ToolResult {
        public Success { preview = preview != null ? preview : String.valueOf(value); mutations = List.copyOf(mutations != null ? mutations : List.of()); metadata = Map.copyOf(metadata != null ? metadata : Map.of()); }
        public static Success of(Object value) { return new Success(value, String.valueOf(value), List.of(), Map.of()); }
    }
    record Failure(String code, String message, boolean retryable, List<ToolStateMutation> mutations) implements ToolResult {
        public Failure { code = code != null ? code : "TOOL_FAILED"; message = message != null ? message : ""; mutations = List.copyOf(mutations != null ? mutations : List.of()); }
    }
    record ExternalPending(String actionId, String invocationDigest, Map<String, Object> request,
                           List<ToolStateMutation> mutations) implements ToolResult {
        public ExternalPending { actionId = actionId != null ? actionId : ""; invocationDigest = invocationDigest != null ? invocationDigest : ""; request = Map.copyOf(request != null ? request : Map.of()); mutations = List.copyOf(mutations != null ? mutations : List.of()); }
    }
}
