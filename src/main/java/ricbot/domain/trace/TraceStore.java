package ricbot.domain.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TraceStore {
    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path workspace;
    private final Path root;

    public TraceStore(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.root = this.workspace.resolve(".traces");
    }

    public TraceEvent append(TraceEvent event) {
        if (event == null) {
            return null;
        }
        TraceEvent safe = event.traceId().isBlank()
                ? event.withTraceId(traceIdForSession(event.sessionId()))
                : event.withTraceId(safeTraceId(event.traceId()));
        try {
            Path dir = root.resolve(safe.traceId());
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve("events.jsonl"),
                    MAPPER.writeValueAsString(safe.toMap()) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
        } catch (Exception e) {
            log.warn("skip trace write: {}", safe.traceId(), e);
        }
        return safe;
    }

    public List<String> listTraces() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(this::updatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (Exception e) {
            log.warn("skip trace list", e);
            return List.of();
        }
    }

    public List<TraceEvent> loadEvents(String traceId) {
        String id = safeTraceId(traceId);
        if (id.isBlank()) {
            return List.of();
        }
        Path file = eventsFile(id);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<TraceEvent> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                TraceEvent event = TraceEvent.fromMap(MAPPER.readValue(line, MAP_TYPE));
                if (event != null) {
                    out.add(event);
                }
            }
        } catch (Exception e) {
            log.warn("skip trace read: {}", id, e);
            return List.of();
        }
        return out;
    }

    public TraceSummary loadLatestTrace() {
        List<String> traces = listTraces();
        return traces.isEmpty() ? null : summarize(traces.get(0));
    }

    public TraceSummary summarize(String traceId) {
        List<TraceEvent> events = loadEvents(traceId);
        Set<String> eventTypes = new LinkedHashSet<>();
        Set<String> approvals = new LinkedHashSet<>();
        Set<String> changeSets = new LinkedHashSet<>();
        List<String> verifierStatuses = new ArrayList<>();
        String commitHash = "";
        String rollbackStatus = "";
        String lastEventAt = "";
        for (TraceEvent event : events) {
            eventTypes.add(event.type().name());
            if (!event.approvalRequestId().isBlank()) {
                approvals.add(event.approvalRequestId());
            }
            if (!event.changeSetId().isBlank()) {
                changeSets.add(event.changeSetId());
            }
            Object status = event.payload().get("status");
            if (event.type() == TraceEventType.VERIFICATION_RESULT && status != null) {
                verifierStatuses.add(String.valueOf(status));
            }
            Object rawCommitHash = event.payload().get("commitHash");
            if (rawCommitHash != null && !String.valueOf(rawCommitHash).isBlank()) {
                commitHash = String.valueOf(rawCommitHash);
            }
            Object rawRollbackStatus = event.payload().get("rollbackStatus");
            if (rawRollbackStatus != null && !String.valueOf(rawRollbackStatus).isBlank()) {
                rollbackStatus = String.valueOf(rawRollbackStatus);
            }
            lastEventAt = event.createdAt();
        }
        String id = safeTraceId(traceId);
        return new TraceSummary(
                id,
                events.size(),
                List.copyOf(eventTypes),
                List.copyOf(approvals),
                List.copyOf(changeSets),
                verifierStatuses,
                commitHash,
                rollbackStatus,
                lastEventAt,
                tracePath(id)
        );
    }

    public Path eventsFile(String traceId) {
        return root.resolve(safeTraceId(traceId)).resolve("events.jsonl");
    }

    public String tracePath(String traceId) {
        return ".traces/" + safeTraceId(traceId) + "/events.jsonl";
    }

    public String traceIdForSession(String sessionId) {
        String value = clean(sessionId);
        if (value.isBlank()) {
            value = "global";
        }
        return safeTraceId("trace_" + value.toLowerCase(java.util.Locale.ROOT));
    }

    private String updatedAt(Path path) {
        try {
            Path events = path.resolve("events.jsonl");
            return Files.exists(events)
                    ? Files.getLastModifiedTime(events).toInstant().toString()
                    : Files.getLastModifiedTime(path).toInstant().toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String safeTraceId(String value) {
        String sanitized = clean(value).replaceAll("[^A-Za-z0-9._-]+", "_");
        while (sanitized.contains("..")) {
            sanitized = sanitized.replace("..", "_");
        }
        if (sanitized.isBlank() || sanitized.equals(".")) {
            sanitized = "trace_global";
        }
        if (sanitized.startsWith(".")) {
            sanitized = "trace_" + sanitized.substring(1);
        }
        return sanitized;
    }

    public record TraceSummary(
            String traceId,
            int eventCount,
            List<String> eventTypes,
            List<String> approvalRequestIds,
            List<String> changeSetIds,
            List<String> verifierStatuses,
            String commitHash,
            String rollbackStatus,
            String lastEventAt,
            String path
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("traceId", traceId);
            out.put("eventCount", eventCount);
            out.put("eventTypes", eventTypes);
            out.put("approvalRequestIds", approvalRequestIds);
            out.put("changeSetIds", changeSetIds);
            out.put("verifierStatuses", verifierStatuses);
            out.put("commitHash", commitHash);
            out.put("rollbackStatus", rollbackStatus);
            out.put("lastEventAt", lastEventAt);
            out.put("path", path);
            return out;
        }
    }
}
