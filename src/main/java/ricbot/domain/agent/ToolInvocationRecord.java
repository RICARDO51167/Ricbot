package ricbot.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Durable ledger entry for one tool call.
 *
 * <p>Arguments are represented by a deterministic digest rather than stored
 * verbatim. This is enough to detect mismatched retries without copying
 * credentials or large tool inputs into the event journal.</p>
 */
public record ToolInvocationRecord(
        String invocationId,
        String runId,
        int iteration,
        String toolCallId,
        String toolName,
        String argumentsDigest,
        boolean readOnly,
        String risk,
        ToolInvocationStatus status,
        Map<String, Object> resultMessage,
        String error,
        Instant startedAt,
        Instant completedAt
) {
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public ToolInvocationRecord {
        invocationId = requireText(invocationId, "invocationId");
        runId = requireText(runId, "runId");
        iteration = Math.max(0, iteration);
        toolCallId = requireText(toolCallId, "toolCallId");
        toolName = requireText(toolName, "toolName");
        argumentsDigest = requireText(argumentsDigest, "argumentsDigest");
        risk = clean(risk);
        status = Objects.requireNonNull(status, "status");
        resultMessage = resultMessage != null
                ? java.util.Collections.unmodifiableMap(new LinkedHashMap<>(resultMessage))
                : Map.of();
        error = clean(error);
        startedAt = Objects.requireNonNullElseGet(startedAt, Instant::now);
        if (status.finished() && completedAt == null) {
            completedAt = Instant.now();
        }
    }

    public static ToolInvocationRecord running(
            String runId,
            int iteration,
            String toolCallId,
            String toolName,
            Map<String, Object> arguments,
            boolean readOnly,
            String risk
    ) {
        String digest = argumentsDigest(arguments);
        return new ToolInvocationRecord(
                invocationId(runId, iteration, toolCallId, toolName, digest),
                runId,
                iteration,
                toolCallId,
                toolName,
                digest,
                readOnly,
                risk,
                ToolInvocationStatus.RUNNING,
                Map.of(),
                "",
                Instant.now(),
                null
        );
    }

    public ToolInvocationRecord completed(Map<String, Object> toolMessage, boolean succeeded, String failure) {
        return new ToolInvocationRecord(
                invocationId,
                runId,
                iteration,
                toolCallId,
                toolName,
                argumentsDigest,
                readOnly,
                risk,
                succeeded ? ToolInvocationStatus.SUCCEEDED : ToolInvocationStatus.FAILED,
                toolMessage,
                failure,
                startedAt,
                Instant.now()
        );
    }

    public ToolInvocationRecord retrying() {
        if (status != ToolInvocationStatus.UNKNOWN || !readOnly) {
            throw new IllegalStateException("only unknown read-only invocations can be retried automatically");
        }
        return new ToolInvocationRecord(
                invocationId,
                runId,
                iteration,
                toolCallId,
                toolName,
                argumentsDigest,
                true,
                risk,
                ToolInvocationStatus.RUNNING,
                Map.of(),
                "",
                Instant.now(),
                null
        );
    }

    public ToolInvocationRecord interruptedUnknown() {
        if (status.finished() || status == ToolInvocationStatus.UNKNOWN) {
            return this;
        }
        return new ToolInvocationRecord(
                invocationId,
                runId,
                iteration,
                toolCallId,
                toolName,
                argumentsDigest,
                readOnly,
                risk,
                ToolInvocationStatus.UNKNOWN,
                resultMessage,
                "execution outcome is unknown after interruption",
                startedAt,
                null
        );
    }

    static String argumentsDigest(Map<String, Object> arguments) {
        try {
            byte[] canonical = CANONICAL_MAPPER.writeValueAsBytes(arguments != null ? arguments : Map.of());
            return sha256(canonical);
        } catch (Exception e) {
            throw new IllegalArgumentException("tool arguments are not JSON serializable", e);
        }
    }

    private static String invocationId(
            String runId,
            int iteration,
            String toolCallId,
            String toolName,
            String argumentsDigest
    ) {
        String material = runId + "\n" + iteration + "\n" + toolCallId + "\n" + toolName + "\n" + argumentsDigest;
        return sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requireText(String value, String field) {
        String clean = clean(value);
        if (clean.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return clean;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
