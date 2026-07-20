package ricbot.infra.execution;

public record ExecutionCapabilities(
        boolean available,
        boolean filesystemIsolation,
        boolean networkIsolation,
        boolean remote,
        String detail
) {
    public ExecutionCapabilities {
        detail = detail != null ? detail.trim() : "";
    }
}
