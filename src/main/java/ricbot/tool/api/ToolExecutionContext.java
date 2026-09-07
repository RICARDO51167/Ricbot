package ricbot.tool.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal immutable capability context. It intentionally never exposes graph state. */
public record ToolExecutionContext(
        String runId, String sessionId, String taskId, String activationId, String workspaceId,
        Path workspace, String role, ToolAuthorizationDecision authorization,
        Map<String, Object> backendCapabilities, Map<String, Object> fileReadReceipts,
        AtomicBoolean cancelled
) {
    public ToolExecutionContext {
        runId = clean(runId); sessionId = clean(sessionId); taskId = clean(taskId); activationId = clean(activationId);
        workspaceId = clean(workspaceId); role = clean(role);
        backendCapabilities = Map.copyOf(backendCapabilities != null ? backendCapabilities : Map.of());
        fileReadReceipts = Map.copyOf(fileReadReceipts != null ? fileReadReceipts : Map.of());
        cancelled = cancelled != null ? cancelled : new AtomicBoolean();
    }
    public boolean isCancelled() { return cancelled.get(); }
    public boolean approved() { return authorization != null && authorization.decision() == ToolAuthorizationDecision.Decision.ALLOW; }
    public static ToolExecutionContext normal() { return new ToolExecutionContext("", "", "", "", "", null, "", null, Map.of(), Map.of(), null); }
    public static ToolExecutionContext approvedContext() { return new ToolExecutionContext("", "", "", "", "", null, "", new ToolAuthorizationDecision(ToolAuthorizationDecision.Decision.ALLOW, "approved", java.util.List.of(), false), Map.of(), Map.of(), null); }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
