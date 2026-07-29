package ricbot.domain.agent.dto;

public record SideEffectOutcome(Object result, boolean reused, SideEffectRecord record) { }
