package ricbot.domain.trace;

import ricbot.infra.runtime.SqliteRuntimeStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SQLite projection facade for trace queries; RuntimeEventEnvelope remains the durable fact. */
public class TraceStore {
    private final SqliteRuntimeStore runtime;

    public TraceStore(Path workspace) { this(ricbot.app.bootstrap.RuntimeStoreRegistry.shared(workspace)); }
    public TraceStore(SqliteRuntimeStore runtime) { this.runtime = java.util.Objects.requireNonNull(runtime, "runtime"); }

    public TraceEvent append(TraceEvent event) {
        if (event == null) return null;
        TraceEvent safe = event.traceId().isBlank()
                ? event.withTraceId(traceIdForSession(event.sessionId()))
                : event.withTraceId(safeTraceId(event.traceId()));
        return runtime.saveTraceEvent(safe);
    }

    public List<String> listTraces() { return runtime.traceIds(); }

    public List<TraceEvent> loadEvents(String traceId) {
        String id = safeTraceId(traceId);
        return id.isBlank() ? List.of() : runtime.traceEvents(id);
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
        String commitHash = "", rollbackStatus = "", lastEventAt = "";
        for (TraceEvent event : events) {
            eventTypes.add(event.type().name());
            if (!event.approvalRequestId().isBlank()) approvals.add(event.approvalRequestId());
            if (!event.changeSetId().isBlank()) changeSets.add(event.changeSetId());
            Object status = event.payload().get("status");
            if (event.type() == TraceEventType.VERIFICATION_RESULT && status != null)
                verifierStatuses.add(String.valueOf(status));
            Object commit = event.payload().get("commitHash");
            if (commit != null && !String.valueOf(commit).isBlank()) commitHash = String.valueOf(commit);
            Object rollback = event.payload().get("rollbackStatus");
            if (rollback != null && !String.valueOf(rollback).isBlank()) rollbackStatus = String.valueOf(rollback);
            lastEventAt = event.createdAt();
        }
        String id = safeTraceId(traceId);
        return new TraceSummary(id, events.size(), List.copyOf(eventTypes), List.copyOf(approvals),
                List.copyOf(changeSets), verifierStatuses, commitHash, rollbackStatus, lastEventAt, tracePath(id));
    }

    /** Compatibility query path, now pointing at the unified database instead of a JSONL file. */
    public Path eventsFile(String traceId) { return runtime.database(); }
    public String tracePath(String traceId) {
        return "sqlite:.ricbot/runtime.db#traces/" + safeTraceId(traceId);
    }
    public String traceIdForSession(String sessionId) {
        String value = clean(sessionId);
        if (value.isBlank()) value = "global";
        return safeTraceId("trace_" + value.toLowerCase(java.util.Locale.ROOT));
    }
    private static String clean(String value) { return value != null ? value.trim() : ""; }
    private static String safeTraceId(String value) {
        String sanitized = clean(value).replaceAll("[^A-Za-z0-9._-]+", "_");
        while (sanitized.contains("..")) sanitized = sanitized.replace("..", "_");
        if (sanitized.isBlank() || sanitized.equals(".")) sanitized = "trace_global";
        if (sanitized.startsWith(".")) sanitized = "trace_" + sanitized.substring(1);
        return sanitized;
    }

    public record TraceSummary(String traceId, int eventCount, List<String> eventTypes,
                               List<String> approvalRequestIds, List<String> changeSetIds,
                               List<String> verifierStatuses, String commitHash, String rollbackStatus,
                               String lastEventAt, String path) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("traceId", traceId); out.put("eventCount", eventCount); out.put("eventTypes", eventTypes);
            out.put("approvalRequestIds", approvalRequestIds); out.put("changeSetIds", changeSetIds);
            out.put("verifierStatuses", verifierStatuses); out.put("commitHash", commitHash);
            out.put("rollbackStatus", rollbackStatus); out.put("lastEventAt", lastEventAt); out.put("path", path);
            return Map.copyOf(out);
        }
    }
}
