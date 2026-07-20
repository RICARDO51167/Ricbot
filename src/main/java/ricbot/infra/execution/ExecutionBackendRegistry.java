package ricbot.infra.execution;

import java.util.LinkedHashMap;
import java.util.Map;

/** Named backend selection with explicit, auditable fallback. */
public final class ExecutionBackendRegistry {
    private final Map<String, ExecutionBackend> backends = new LinkedHashMap<>();

    public ExecutionBackendRegistry register(ExecutionBackend backend) {
        if (backend == null) throw new IllegalArgumentException("backend is required");
        backends.put(backend.name(), backend);
        return this;
    }

    public ExecutionBackend require(String name) {
        ExecutionBackend backend = backends.get(name);
        if (backend == null) throw new IllegalArgumentException("execution backend is not registered: " + name);
        ExecutionCapabilities capabilities = backend.probe();
        if (!capabilities.available()) {
            throw new IllegalStateException("execution backend is unavailable: " + name + ": " + capabilities.detail());
        }
        return backend;
    }

    public Selection select(String preferred, String fallback, boolean allowFallback) {
        try {
            return new Selection(require(preferred), false, "");
        } catch (RuntimeException primaryFailure) {
            if (!allowFallback || fallback == null || fallback.isBlank()) throw primaryFailure;
            return new Selection(require(fallback), true, primaryFailure.getMessage());
        }
    }

    public record Selection(ExecutionBackend backend, boolean fallbackUsed, String reason) { }
}
