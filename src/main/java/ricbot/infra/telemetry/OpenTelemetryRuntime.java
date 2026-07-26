package ricbot.infra.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.net.URI;
import java.time.Duration;

/** Optional OTLP SDK owner; no exporter or background thread is created by default. */
public final class OpenTelemetryRuntime implements AutoCloseable {
    private final OpenTelemetry telemetry;
    private final SdkTracerProvider provider;
    private final String endpoint;

    private OpenTelemetryRuntime(OpenTelemetry telemetry, SdkTracerProvider provider, String endpoint) {
        this.telemetry = telemetry;
        this.provider = provider;
        this.endpoint = endpoint != null ? endpoint : "";
    }

    public static OpenTelemetryRuntime fromEnvironment() {
        String endpoint = first(System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"),
                System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"), System.getenv("RICBOT_OTLP_ENDPOINT"));
        if (endpoint.isBlank()) {
            return new OpenTelemetryRuntime(GlobalOpenTelemetry.get(), null, "");
        }
        return create(endpoint, first(System.getenv("OTEL_SERVICE_NAME"), "ricbot"));
    }

    public static OpenTelemetryRuntime create(String endpoint, String serviceName) {
        String cleanEndpoint = required(endpoint, "endpoint").replaceAll("/+$", "");
        URI uri = URI.create(cleanEndpoint);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("OTLP endpoint must use HTTP or HTTPS");
        }
        OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(cleanEndpoint)
                .setTimeout(Duration.ofSeconds(10))
                .build();
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), required(serviceName, "serviceName"),
                AttributeKey.stringKey("service.version"), ricbot.app.bootstrap.BuildVersion.current()
        )));
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        return new OpenTelemetryRuntime(sdk, provider, cleanEndpoint);
    }

    public Tracer tracer(String instrumentationName, String version) {
        return telemetry.getTracer(instrumentationName, version);
    }

    public boolean exporting() {
        return provider != null;
    }

    public String endpoint() {
        return endpoint;
    }

    @Override
    public void close() {
        if (provider != null) provider.close();
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static String required(String value, String field) {
        String clean = value != null ? value.trim() : "";
        if (clean.isBlank()) throw new IllegalArgumentException(field + " is required");
        return clean;
    }
}
