package ricbot.infra.execution;

import java.util.List;
import java.util.Map;

/** Raised only after an interrupted backend has attempted bounded process-tree cleanup. */
public final class ExecutionInterruptedException extends RuntimeException {
    private final boolean processTreeTerminated;
    private final List<Long> remainingPids;
    private final String backend;

    public ExecutionInterruptedException(String backend, boolean processTreeTerminated,
                                         List<Long> remainingPids, Throwable cause) {
        super("execution interrupted; backend=" + clean(backend)
                + ", processTreeTerminated=" + processTreeTerminated
                + ", remainingPids=" + List.copyOf(remainingPids != null ? remainingPids : List.of()), cause);
        this.backend = clean(backend);
        this.processTreeTerminated = processTreeTerminated;
        this.remainingPids = List.copyOf(remainingPids != null ? remainingPids : List.of());
    }

    public boolean processTreeTerminated() { return processTreeTerminated; }
    public List<Long> remainingPids() { return remainingPids; }
    public String backend() { return backend; }
    public Map<String, Object> evidence() {
        return Map.of("backend", backend, "processTreeTerminated", processTreeTerminated,
                "remainingPids", remainingPids);
    }

    private static String clean(String value) {
        return value != null && !value.isBlank() ? value.trim() : "unknown";
    }
}
