package ricbot.domain.agent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Maps durable run events to OpenTelemetry spans without owning an SDK/exporter. */
public final class OpenTelemetryRunEventSink implements RunEventSink {
    private final Tracer tracer;
    private final Map<String, Span> spans = new ConcurrentHashMap<>();

    public OpenTelemetryRunEventSink(Tracer tracer) {
        this.tracer = java.util.Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public void append(RunEvent event) {
        String key = event.sessionKey() + "\n" + event.runId();
        Span span = spans.computeIfAbsent(key, ignored -> tracer.spanBuilder("ricbot.agent.run")
                .setAttribute("ricbot.run.id", event.runId())
                .setAttribute("ricbot.session.key", event.sessionKey())
                .startSpan());
        Attributes attributes = Attributes.builder()
                .put("ricbot.event.sequence", event.sequence())
                .put("ricbot.event.type", event.type().name())
                .put("ricbot.run.status", event.status().name())
                .put("ricbot.run.iteration", (long) event.iteration())
                .build();
        span.addEvent("ricbot." + event.type().name().toLowerCase(java.util.Locale.ROOT), attributes);
        if (event.status() == RunStatus.FAILED) {
            span.setStatus(StatusCode.ERROR, String.valueOf(event.details().getOrDefault("error", "run failed")));
        }
        if (event.status().terminal()) {
            if (event.status() == RunStatus.CANCELLED) span.setAttribute("ricbot.run.cancelled", true);
            spans.remove(key);
            span.end();
        }
    }

    public int activeSpanCount() { return spans.size(); }
}
