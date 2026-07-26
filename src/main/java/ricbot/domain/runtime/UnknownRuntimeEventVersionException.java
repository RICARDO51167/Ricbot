package ricbot.domain.runtime;

public final class UnknownRuntimeEventVersionException extends IllegalStateException {
    public UnknownRuntimeEventVersionException(int schemaVersion) {
        super("unsupported runtime event schema " + schemaVersion + "; execution stopped to preserve replay safety");
    }
}
