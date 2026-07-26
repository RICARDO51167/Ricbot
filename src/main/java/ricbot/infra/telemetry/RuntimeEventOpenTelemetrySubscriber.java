package ricbot.infra.telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import ricbot.domain.runtime.RuntimeDigest;
import ricbot.domain.runtime.RuntimeEventEnvelope;
import ricbot.domain.runtime.RuntimeEventSubscriber;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Builds the runtime span tree from facts and records only sizes, types and digests by default. */
public final class RuntimeEventOpenTelemetrySubscriber implements RuntimeEventSubscriber, AutoCloseable {
    private final Tracer tracer;
    private final Map<String, Span> runs = new ConcurrentHashMap<>();
    private final Map<String, Span> activations = new ConcurrentHashMap<>();

    public RuntimeEventOpenTelemetrySubscriber(Tracer tracer) {
        this.tracer = java.util.Objects.requireNonNull(tracer, "tracer");
    }

    @Override public void onEvent(RuntimeEventEnvelope event) {
        Span run = runs.computeIfAbsent(event.runId(), id -> tracer.spanBuilder("Run " + id)
                .setSpanKind(SpanKind.INTERNAL).startSpan());
        run.setAttribute("ricbot.event.sequence", event.globalSequence());
        run.setAttribute("ricbot.event.type", event.eventType());
        run.setAttribute("ricbot.payload.length", event.payload().toString().length());
        run.setAttribute("ricbot.payload.digest", RuntimeDigest.sha256(event.payload()));

        String activationKey = !event.activationId().isBlank() ? event.activationId()
                : !event.taskId().isBlank() ? "task:" + event.taskId() : "";
        if (!activationKey.isBlank()) {
            Span activation = activations.computeIfAbsent(event.runId() + ":" + activationKey,
                    ignored -> tracer.spanBuilder(spanName(event)).setParent(Context.root().with(run)).startSpan());
            activation.setAttribute("ricbot.activation.id", activationKey);
            activation.setAttribute("ricbot.event.type", event.eventType());
            if (settled(event.eventType())) {
                if (event.eventType().contains("FAILED") || event.eventType().contains("UNKNOWN")) {
                    activation.setStatus(StatusCode.ERROR);
                }
                activation.end();
                activations.remove(event.runId() + ":" + activationKey);
            }
        }
        if (event.eventType().endsWith("RUN_COMPLETED") || event.eventType().endsWith("RUN_CANCELLED")
                || event.eventType().endsWith("RUN_FAILED")) {
            if (event.eventType().endsWith("RUN_FAILED")) run.setStatus(StatusCode.ERROR);
            run.end();
            runs.remove(event.runId());
        }
    }

    private static String spanName(RuntimeEventEnvelope event) {
        String type = event.eventType();
        if (!event.taskId().isBlank()) return "Child Task Run " + event.taskId();
        if (type.contains("COMPACT")) return "Compact";
        if (type.contains("CONTEXT")) return "Context";
        if (type.contains("MODEL")) return "Model";
        if (type.contains("SIDE_EFFECT") || type.contains("TOOL")) return "Tool";
        if (type.contains("PATCH")) return "Patch";
        if (type.contains("VERIFY")) return "Verifier";
        return type;
    }

    private static boolean settled(String type) {
        return type.contains("COMPLETED") || type.contains("SUCCEEDED") || type.contains("FAILED")
                || type.contains("UNKNOWN") || type.contains("ACKNOWLEDGED") || type.contains("COMMITTED");
    }

    @Override public void close() {
        activations.values().forEach(Span::end);
        runs.values().forEach(Span::end);
        activations.clear();
        runs.clear();
    }
}
