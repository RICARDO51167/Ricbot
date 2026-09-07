package ricbot.infra.telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import ricbot.domain.runtime.dto.RuntimeDigest;
import ricbot.domain.runtime.RuntimeEvent;
import ricbot.domain.runtime.RuntimeEventSubscriber;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 根据事实构建运行时 Span 树，默认仅记录大小、类型和摘要。
 */
public final class RuntimeEventOpenTelemetrySubscriber implements RuntimeEventSubscriber, AutoCloseable {
    private final Tracer tracer;
    // 存储当前运行的 Span，Key 为 runId
    private final Map<String, Span> runs = new ConcurrentHashMap<>();
    // 存储当前激活的 Span，Key 为 "runId:activationKey"
    private final Map<String, Span> activations = new ConcurrentHashMap<>();

    public RuntimeEventOpenTelemetrySubscriber(Tracer tracer) {
        this.tracer = java.util.Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public void onEvent(RuntimeEvent event) {
        // 1. 处理 Run Span：创建或获取运行级别的 Span
        String runId = event.runId();
        Span run = runs.computeIfAbsent(runId, id ->
            tracer.spanBuilder("Run " + id)
                .setParent(parentContext(String.valueOf(event.payload().getOrDefault("traceParent", ""))))
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan()
        );

        // 2. 为 Run Span 设置通用属性
        run.setAttribute("ricbot.event.sequence", event.sequence());
        run.setAttribute("ricbot.event.type", event.type());
        run.setAttribute("ricbot.payload.length", event.payload().toString().length());
        run.setAttribute("ricbot.payload.digest", RuntimeDigest.sha256(event.payload()));
        run.setAttribute("ricbot.correlation.id", String.valueOf(event.payload().getOrDefault("correlationId", "")));
        run.setAttribute("ricbot.causation.id", String.valueOf(event.payload().getOrDefault("causationId", "")));

        // 3. 处理 Activation Span：确定激活 ID 并管理对应的 Span
        String activationKey = String.valueOf(event.payload().getOrDefault("activationId", ""));

        if (!activationKey.isBlank()) {
            String activationId = runId + ":" + activationKey;
            
            // 创建或获取激活 Span，父级设为当前的 Run Span
            Span activation = activations.computeIfAbsent(activationId, ignored ->
                tracer.spanBuilder(spanName(event))
                    .setParent(Context.root().with(run))
                    .startSpan()
            );

            activation.setAttribute("ricbot.activation.id", activationKey);
            activation.setAttribute("ricbot.event.type", event.type());

            // 如果事件是终结状态（完成、失败等），则结束该激活 Span
            if (settled(event.type())) {
                if (event.type().contains("FAILED") || event.type().contains("UNKNOWN")) {
                    activation.setStatus(StatusCode.ERROR);
                }
                activation.end();
                activations.remove(activationId);
            }
        }

        // 4. 处理 Run 的终结状态：结束 Run Span
        if (event.type().endsWith("RUN_COMPLETED") || event.type().endsWith("RUN_CANCELLED")
                || event.type().endsWith("RUN_FAILED")) {
            if (event.type().endsWith("RUN_FAILED")) {
                run.setStatus(StatusCode.ERROR);
            }
            run.end();
            runs.remove(runId);
        }
    }

    /**
     * 解析 traceParent 字符串以创建有效的 OpenTelemetry Context。
     * 格式通常为：trace-id-span-id-flags
     */
    private static Context parentContext(String traceParent) {
        if (traceParent == null) {
            return Context.root();
        }

        String[] parts = traceParent.trim().split("-");
        // 验证格式：长度应为4，traceId 32位，spanId 16位
        if (parts.length != 4 || parts[1].length() != 32 || parts[2].length() != 16) {
            return Context.root();
        }

        try {
            SpanContext parent = SpanContext.createFromRemoteParent(
                parts[1],
                parts[2],
                TraceFlags.fromHex(parts[3], 0),
                TraceState.getDefault()
            );
            return parent.isValid() ? Context.root().with(Span.wrap(parent)) : Context.root();
        } catch (RuntimeException ignored) {
            return Context.root();
        }
    }

    /**
     * 根据事件类型生成更具可读性的 Span 名称。
     */
    private static String spanName(RuntimeEvent event) {
        String type = event.type();

        String childRunId = String.valueOf(event.payload().getOrDefault("childRunId", ""));
        if (!childRunId.isBlank()) return "Child Run " + childRunId;
        if (type.contains("COMPACT")) {
            return "Compact";
        }
        if (type.contains("CONTEXT")) {
            return "Context";
        }
        if (type.contains("MODEL")) {
            return "Model";
        }
        if (type.contains("SIDE_EFFECT") || type.contains("TOOL")) {
            return "Tool";
        }
        if (type.contains("PATCH")) {
            return "Patch";
        }
        if (type.contains("VERIFY")) {
            return "Verifier";
        }
        return type;
    }

    /**
     * 判断事件类型是否表示激活状态的终结（如完成、成功、失败等）。
     */
    private static boolean settled(String type) {
        return type.contains("COMPLETED") || type.contains("SUCCEEDED") || type.contains("FAILED")
                || type.contains("UNKNOWN") || type.contains("ACKNOWLEDGED") || type.contains("COMMITTED");
    }

    @Override
    public void close() {
        // 确保所有未结束的 Span 都被关闭
        activations.values().forEach(Span::end);
        runs.values().forEach(Span::end);
        activations.clear();
        runs.clear();
    }
}
