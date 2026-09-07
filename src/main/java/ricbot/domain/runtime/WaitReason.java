package ricbot.domain.runtime;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = WaitReason.ApprovalWait.class, name = "approval"),
        @JsonSubTypes.Type(value = WaitReason.UserInputWait.class, name = "user_input"),
        @JsonSubTypes.Type(value = WaitReason.ChildRunWait.class, name = "child_run"),
        @JsonSubTypes.Type(value = WaitReason.RetryWait.class, name = "retry"),
        @JsonSubTypes.Type(value = WaitReason.ExternalEventWait.class, name = "external_event")
})
public sealed interface WaitReason permits WaitReason.ApprovalWait, WaitReason.UserInputWait,
        WaitReason.ChildRunWait, WaitReason.RetryWait, WaitReason.ExternalEventWait {
    String correlationId();

    record ApprovalWait(String correlationId, String approvalRequestId) implements WaitReason {
        public ApprovalWait { correlationId = required(correlationId); approvalRequestId = required(approvalRequestId); }
    }
    record UserInputWait(String correlationId, String prompt) implements WaitReason {
        public UserInputWait { correlationId = required(correlationId); prompt = clean(prompt); }
    }
    record ChildRunWait(String correlationId, List<String> childRunIds) implements WaitReason {
        public ChildRunWait {
            correlationId = required(correlationId);
            childRunIds = List.copyOf(childRunIds != null ? childRunIds : List.of());
            if (childRunIds.isEmpty()) throw new IllegalArgumentException("childRunIds are required");
            if (childRunIds.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("childRunIds cannot contain blanks");
            }
        }
    }
    record RetryWait(String correlationId, Instant dueAt, int attempt) implements WaitReason {
        public RetryWait {
            correlationId = required(correlationId);
            if (dueAt == null) throw new IllegalArgumentException("dueAt is required");
            if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        }
    }
    record ExternalEventWait(String correlationId, String eventType, Map<String, Object> detail) implements WaitReason {
        public ExternalEventWait {
            correlationId = required(correlationId); eventType = required(eventType);
            detail = Map.copyOf(detail != null ? detail : Map.of());
        }
    }

    private static String clean(String value) { return value != null ? value.trim() : ""; }
    private static String required(String value) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException("value is required");
        return clean;
    }
}
