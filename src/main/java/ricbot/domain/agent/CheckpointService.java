package ricbot.domain.agent;

import ricbot.integration.llm.api.ToolCallRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializes recoverable graph boundaries; storage remains supplied by AgentRunSpec. */
public final class CheckpointService {
    public void publish(AgentRunSpec spec, String runId, String journalRunId, long journalSequence,
                        int iteration, RunCheckpointPhase phase, AgentNodeState nodeState,
                        List<Map<String, Object>> messages, int checkpointMessageOffset,
                        Map<String, Object> assistantMessage,
                        List<Map<String, Object>> completedToolResults,
                        List<ToolCallRequest> pendingToolCalls) {
        if (spec.getCheckpointCallback() == null) return;
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("checkpoint_id", journalRunId + ":" + iteration + ":" + phase.name());
        checkpoint.put("run_id", runId);
        checkpoint.put("journal_run_id", journalRunId);
        checkpoint.put("journal_sequence", journalSequence);
        checkpoint.put("session_key", spec.getSessionKey() != null ? spec.getSessionKey() : "");
        checkpoint.put("iteration", iteration);
        checkpoint.put("phase", phase.name());
        if (nodeState != null) {
            checkpoint.put("node_state", Map.of(
                    "schema_version", nodeState.schemaVersion(), "node", nodeState.node().name(),
                    "iteration", nodeState.iteration(), "transition", nodeState.transition(),
                    "terminal", nodeState.terminal(), "updated_at", nodeState.updatedAt().toString()));
        }
        checkpoint.put("run_messages", new ArrayList<>(messages.subList(
                Math.max(0, Math.min(checkpointMessageOffset, messages.size())), messages.size())));
        checkpoint.put("assistant_message", assistantMessage);
        checkpoint.put("completed_tool_results", completedToolResults != null ? completedToolResults : List.of());
        checkpoint.put("pending_tool_calls", pendingToolCalls != null
                ? pendingToolCalls.stream().map(ToolCallRequest::toOpenAIToolCall).toList() : List.of());
        spec.getCheckpointCallback().accept(checkpoint);
    }
}
