package ricbot.domain.agent.event;

import java.time.Instant;
import java.util.Map;

/** Typed live event protocol. Durable runtime envelopes may embed these records as payloads. */
public sealed interface AgentEvent permits AgentEvent.MessageStart, AgentEvent.MessageDelta,
        AgentEvent.MessageEnd, AgentEvent.ModelCall, AgentEvent.ToolCall, AgentEvent.ContextCompacted,
        AgentEvent.ArtifactOffloaded, AgentEvent.RuntimeHintUpdated, AgentEvent.BudgetUpdated,
        AgentEvent.Approval, AgentEvent.ToolExposureChanged, AgentEvent.Error {
    EventMeta meta();
    String type();

    record EventMeta(String eventId, long sequence, String runId, String sessionId, String taskId,
                     String causationId, String correlationId, Instant occurredAt) {
        public EventMeta {
            if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId is required");
            if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
            runId = clean(runId); sessionId = clean(sessionId); taskId = clean(taskId);
            causationId = clean(causationId); correlationId = clean(correlationId);
            occurredAt = occurredAt != null ? occurredAt : Instant.now();
        }
        private static String clean(String value) { return value != null ? value.trim() : ""; }
    }

    record MessageStart(EventMeta meta, String messageId) implements AgentEvent { public String type() { return "message_start"; } }
    record MessageDelta(EventMeta meta, String messageId, String delta) implements AgentEvent { public String type() { return "message_delta"; } }
    record MessageEnd(EventMeta meta, String messageId, String finishReason) implements AgentEvent { public String type() { return "message_end"; } }
    record ModelCall(EventMeta meta, String phase, String model, Map<String, Object> usage) implements AgentEvent { public String type() { return "model_call"; } }
    record ToolCall(EventMeta meta, String phase, String callId, String tool, boolean ok) implements AgentEvent { public String type() { return "tool_call"; } }
    record ContextCompacted(EventMeta meta, Map<String, Object> detail) implements AgentEvent { public String type() { return "context_compacted"; } }
    record ArtifactOffloaded(EventMeta meta, Map<String, Object> artifact) implements AgentEvent { public String type() { return "artifact_offloaded"; } }
    record RuntimeHintUpdated(EventMeta meta, Map<String, Object> hints) implements AgentEvent { public String type() { return "runtime_hint_updated"; } }
    record BudgetUpdated(EventMeta meta, String phase, Map<String, Object> snapshot) implements AgentEvent { public String type() { return "budget_updated"; } }
    record Approval(EventMeta meta, String phase, String requestId) implements AgentEvent { public String type() { return "approval"; } }
    record ToolExposureChanged(EventMeta meta, Map<String, Object> exposure) implements AgentEvent { public String type() { return "tool_exposure_changed"; } }
    record Error(EventMeta meta, String phase, String message) implements AgentEvent { public String type() { return "error"; } }
}
