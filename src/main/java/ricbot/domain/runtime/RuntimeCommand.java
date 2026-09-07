package ricbot.domain.runtime;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RuntimeCommand.Transition.class, name = "transition"),
        @JsonSubTypes.Type(value = RuntimeCommand.Suspend.class, name = "suspend"),
        @JsonSubTypes.Type(value = RuntimeCommand.Complete.class, name = "complete"),
        @JsonSubTypes.Type(value = RuntimeCommand.Fail.class, name = "fail"),
        @JsonSubTypes.Type(value = RuntimeCommand.SpawnChildRuns.class, name = "spawn_child_runs"),
        @JsonSubTypes.Type(value = RuntimeCommand.ExternalInterrupt.class, name = "external_interrupt"),
        @JsonSubTypes.Type(value = RuntimeCommand.CancelAtBoundary.class, name = "cancel_at_boundary")
})
public sealed interface RuntimeCommand permits RuntimeCommand.Transition, RuntimeCommand.Suspend,
        RuntimeCommand.Complete, RuntimeCommand.Fail, RuntimeCommand.SpawnChildRuns,
        RuntimeCommand.ExternalInterrupt, RuntimeCommand.CancelAtBoundary {
    record Transition(RuntimePhase phase) implements RuntimeCommand {
        public Transition { if (phase == null || phase == RuntimePhase.WAIT) throw new IllegalArgumentException("transition phase must be executable"); }
    }
    record Suspend(WaitReason reason) implements RuntimeCommand {
        public Suspend { if (reason == null) throw new IllegalArgumentException("reason is required"); }
    }
    record Complete(Map<String, Object> result) implements RuntimeCommand {
        public Complete { result = Map.copyOf(result != null ? result : Map.of()); }
    }
    record Fail(String code, String message) implements RuntimeCommand {
        public Fail { code = clean(code); message = clean(message); }
    }
    record SpawnChildRuns(List<RunSpec> children, boolean waitForAll) implements RuntimeCommand {
        public SpawnChildRuns { children = List.copyOf(children != null ? children : List.of()); }
    }
    /** Routes a safe boundary back through INGEST without treating observations as durable state. */
    record ExternalInterrupt(List<String> eventIds) implements RuntimeCommand {
        public ExternalInterrupt { eventIds = List.copyOf(eventIds != null ? eventIds : List.of()); }
    }
    /** Persists cancellation at a safe boundary; unresolved write effects keep the Run waiting. */
    record CancelAtBoundary(String correlationId, List<String> unresolvedEffectIds) implements RuntimeCommand {
        public CancelAtBoundary {
            correlationId = clean(correlationId);
            unresolvedEffectIds = List.copyOf(unresolvedEffectIds != null ? unresolvedEffectIds : List.of());
        }
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
