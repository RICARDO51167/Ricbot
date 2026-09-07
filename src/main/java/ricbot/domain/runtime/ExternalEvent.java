package ricbot.domain.runtime;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.Map;

/** Durable input. Streaming and progress observations deliberately do not implement this type. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ExternalEvent.UserMessage.class, name = "user_message"),
        @JsonSubTypes.Type(value = ExternalEvent.SteeringMessage.class, name = "steering_message"),
        @JsonSubTypes.Type(value = ExternalEvent.ApprovalDecision.class, name = "approval_decision"),
        @JsonSubTypes.Type(value = ExternalEvent.ChildRunCompleted.class, name = "child_run_completed"),
        @JsonSubTypes.Type(value = ExternalEvent.TimerExpired.class, name = "timer_expired"),
        @JsonSubTypes.Type(value = ExternalEvent.ExternalActionResult.class, name = "external_action_result"),
        @JsonSubTypes.Type(value = ExternalEvent.EffectConfirmation.class, name = "effect_confirmation"),
        @JsonSubTypes.Type(value = ExternalEvent.CancelRequested.class, name = "cancel_requested"),
        @JsonSubTypes.Type(value = ExternalEvent.RuntimeLimitReached.class, name = "runtime_limit_reached")
})
public sealed interface ExternalEvent permits ExternalEvent.UserMessage, ExternalEvent.SteeringMessage,
        ExternalEvent.ApprovalDecision, ExternalEvent.ChildRunCompleted, ExternalEvent.TimerExpired,
        ExternalEvent.ExternalActionResult, ExternalEvent.EffectConfirmation, ExternalEvent.CancelRequested,
        ExternalEvent.RuntimeLimitReached {
    String eventId();
    String correlationId();
    Instant occurredAt();
    Map<String, Object> payload();
    default String artifactReference() { return String.valueOf(payload().getOrDefault("artifactReference", "")); }

    record UserMessage(String eventId, String correlationId, Instant occurredAt,
                       Map<String, Object> payload) implements ExternalEvent { public UserMessage { Values.validate(eventId, correlationId, occurredAt); payload = Values.map(payload); } }
    record SteeringMessage(String eventId, String correlationId, Instant occurredAt,
                           Map<String, Object> payload) implements ExternalEvent { public SteeringMessage { Values.validate(eventId, correlationId, occurredAt); payload = Values.map(payload); } }
    record ApprovalDecision(String eventId, String correlationId, Instant occurredAt,
                            Map<String, Object> payload) implements ExternalEvent { public ApprovalDecision { Values.validate(eventId, correlationId, occurredAt); payload = Values.map(payload); } }
    record ChildRunCompleted(String eventId, String correlationId, Instant occurredAt,
                             Map<String, Object> payload) implements ExternalEvent { public ChildRunCompleted { Values.validate(eventId, correlationId, occurredAt); payload = Values.map(payload); } }
    record TimerExpired(String eventId, String correlationId, Instant occurredAt,
                        Map<String, Object> payload) implements ExternalEvent { public TimerExpired { Values.validate(eventId, correlationId, occurredAt); payload = Values.map(payload); } }
    record ExternalActionResult(String eventId, String correlationId, Instant occurredAt,
                                Map<String, Object> payload) implements ExternalEvent { public ExternalActionResult { Values.validate(eventId, correlationId, occurredAt); payload = Values.map(payload); } }
    record EffectConfirmation(String eventId, String correlationId, Instant occurredAt,
                              Map<String, Object> payload) implements ExternalEvent { public EffectConfirmation { Values.validate(eventId, correlationId, occurredAt); payload = Values.map(payload); } }
    record CancelRequested(String eventId, String correlationId, Instant occurredAt,
                           Map<String, Object> payload) implements ExternalEvent { public CancelRequested { Values.validate(eventId, correlationId, occurredAt); payload = Values.map(payload); } }
    /** Internal durable event emitted when a Run has consumed its lifetime superstep allowance. */
    record RuntimeLimitReached(String eventId, String correlationId, Instant occurredAt,
                               Map<String, Object> payload) implements ExternalEvent { public RuntimeLimitReached { Values.validate(eventId, correlationId, occurredAt); payload = Values.map(payload); } }

    final class Values {
        private Values() { }
        static void validate(String eventId, String correlationId, Instant occurredAt) {
            if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId is required");
            if (correlationId == null || correlationId.isBlank()) throw new IllegalArgumentException("correlationId is required");
            if (occurredAt == null) throw new IllegalArgumentException("occurredAt is required");
        }
        static Map<String, Object> map(Map<String, Object> value) { return Map.copyOf(value != null ? value : Map.of()); }
    }
}
