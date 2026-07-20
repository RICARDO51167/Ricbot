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
}
