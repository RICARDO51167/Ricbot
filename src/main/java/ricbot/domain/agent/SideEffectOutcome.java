package ricbot.domain.agent;

public record SideEffectOutcome(Object result, boolean reused, SideEffectRecord record) { }
