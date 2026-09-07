package ricbot.tool.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tool contract used behind the v6 Effect Runtime. */
public interface Tool {
    ToolDescriptor descriptor();

    default ToolRiskEvidence assessRisk(ToolInvocation invocation, ToolExecutionContext context) {
        return ToolRiskEvidence.allow();
    }

    default List<String> resourceKeys(ToolInvocation invocation, ToolExecutionContext context) {
        return List.of();
    }

    /** Optional recovery hook for a write whose dispatch outcome is UNKNOWN. */
    default Optional<ToolResult> reconcile(ToolInvocation invocation, ToolExecutionContext context,
                                           Map<String, Object> executionEvidence) throws Exception {
        return Optional.empty();
    }

    ToolResult execute(ToolInvocation invocation, ToolExecutionContext context, ToolChunkSink chunks) throws Exception;
}
