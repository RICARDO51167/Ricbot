package ricbot.domain.agent.structured;

import ricbot.domain.agent.usage.UsageLedger;

public record StructuredResult<T>(T value, Mode mode, int repairCalls, String error, UsageLedger usage) {
    public StructuredResult(T value, Mode mode, int repairCalls, String error) {
        this(value, mode, repairCalls, error, UsageLedger.empty());
    }
    public StructuredResult { usage = usage != null ? usage : UsageLedger.empty(); }
    public enum Mode { STRICT_TOOL, JSON, TEXT_FALLBACK }
    public boolean valid() { return value != null && (error == null || error.isBlank()); }
}
