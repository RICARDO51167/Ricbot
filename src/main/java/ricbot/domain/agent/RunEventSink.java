package ricbot.domain.agent;

/** Synchronous durability boundary for typed run events. */
@FunctionalInterface
public interface RunEventSink {
    void append(RunEvent event);

    static RunEventSink disabled() {
        return event -> { };
    }

    static RunEventSink composite(RunEventSink... sinks) {
        java.util.List<RunEventSink> active = java.util.Arrays.stream(sinks != null ? sinks : new RunEventSink[0])
                .filter(java.util.Objects::nonNull)
                .toList();
        return event -> {
            RuntimeException failure = null;
            for (RunEventSink sink : active) {
                try {
                    sink.append(event);
                } catch (RuntimeException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            if (failure != null) throw failure;
        };
    }

    /**
     * Persists the durable fact first, then fans out to diagnostics without allowing
     * telemetry failures to invalidate an already committed Run event.
     */
    static RunEventSink durableWithDiagnostics(RunEventSink durable, RunEventSink... diagnostics) {
        RunEventSink required = java.util.Objects.requireNonNull(durable, "durable");
        java.util.List<RunEventSink> optional = java.util.Arrays.stream(
                        diagnostics != null ? diagnostics : new RunEventSink[0]
                )
                .filter(java.util.Objects::nonNull)
                .toList();
        return event -> {
            required.append(event);
            for (RunEventSink diagnostic : optional) {
                try {
                    diagnostic.append(event);
                } catch (RuntimeException ignored) {
                    // Diagnostic exporters are not a source of truth.
                }
            }
        };
    }
}
