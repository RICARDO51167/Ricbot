package ricbot.tool.api;

import java.util.Map;

public record ToolInvocation(String callId, String toolName, Map<String, Object> arguments) {
    public ToolInvocation {
        callId = callId != null ? callId.trim() : "";
        toolName = toolName != null ? toolName.trim() : "";
        arguments = Map.copyOf(arguments != null ? arguments : Map.of());
    }
}
