package ricbot.domain.trace;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public class TraceRenderer {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public String renderList(List<TraceStore.TraceSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return "No traces.";
        }
        StringBuilder sb = new StringBuilder("traces\n");
        for (TraceStore.TraceSummary summary : summaries) {
            sb.append("- ").append(summary.traceId())
                    .append(" events=").append(summary.eventCount())
                    .append(" types=").append(summary.eventTypes().isEmpty() ? "none" : String.join(",", summary.eventTypes()))
                    .append(" approvals=").append(summary.approvalRequestIds().size())
                    .append(" changeSets=").append(summary.changeSetIds().size())
                    .append(!summary.commitHash().isBlank() ? " commitHash=" + summary.commitHash() : "")
                    .append(!summary.rollbackStatus().isBlank() ? " rollbackStatus=" + summary.rollbackStatus() : "")
                    .append(" updatedAt=").append(summary.lastEventAt())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    public String renderSummary(TraceStore.TraceSummary summary, List<TraceEvent> events) {
        if (summary == null) {
            return "No trace found.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("trace ").append(summary.traceId()).append("\n");
        sb.append("path: ").append(summary.path()).append("\n");
        sb.append("eventCount: ").append(summary.eventCount()).append("\n");
        sb.append("eventTypes: ").append(summary.eventTypes().isEmpty() ? "none" : String.join(", ", summary.eventTypes())).append("\n");
        sb.append("approvals: ").append(summary.approvalRequestIds().isEmpty() ? "none" : String.join(", ", summary.approvalRequestIds())).append("\n");
        sb.append("changeSets: ").append(summary.changeSetIds().isEmpty() ? "none" : String.join(", ", summary.changeSetIds())).append("\n");
        sb.append("verifierStatus: ").append(summary.verifierStatuses().isEmpty() ? "none" : String.join(", ", summary.verifierStatuses())).append("\n");
        sb.append("commitHash: ").append(summary.commitHash().isBlank() ? "none" : summary.commitHash()).append("\n");
        sb.append("rollbackStatus: ").append(summary.rollbackStatus().isBlank() ? "none" : summary.rollbackStatus()).append("\n");
        sb.append("lastEventAt: ").append(summary.lastEventAt()).append("\n\n");
        sb.append("timeline\n");
        List<TraceEvent> tail = events != null ? events.stream().skip(Math.max(0, events.size() - 8)).toList() : List.of();
        if (tail.isEmpty()) {
            sb.append("- none\n");
        } else {
            for (TraceEvent event : tail) {
                sb.append("- ").append(event.createdAt())
                        .append(" ").append(event.type())
                        .append(" actor=").append(event.actor())
                        .append(!event.approvalRequestId().isBlank() ? " approval=" + event.approvalRequestId() : "")
                        .append(!event.changeSetId().isBlank() ? " changeSet=" + event.changeSetId() : "")
                        .append(!event.teamSessionId().isBlank() ? " team=" + event.teamSessionId() : "")
                        .append(" ").append(event.message())
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    public String renderEvents(List<TraceEvent> events) {
        if (events == null || events.isEmpty()) {
            return "No trace events.";
        }
        StringBuilder sb = new StringBuilder("trace events\n");
        for (TraceEvent event : events) {
            sb.append("- ").append(event.createdAt())
                    .append(" ").append(event.type())
                    .append(" eventId=").append(event.eventId())
                    .append(" actor=").append(event.actor())
                    .append(!event.approvalRequestId().isBlank() ? " approval=" + event.approvalRequestId() : "")
                    .append(!event.changeSetId().isBlank() ? " changeSet=" + event.changeSetId() : "")
                    .append(" message=").append(event.message())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    public String renderExport(List<TraceEvent> events) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (TraceEvent event : events) {
            try {
                sb.append(MAPPER.writeValueAsString(event.toMap())).append("\n");
            } catch (Exception e) {
                sb.append(event.toMap()).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    public String renderTimeline(TraceTimeline timeline) {
        if (timeline == null || (timeline.events().isEmpty() && !timeline.warnings().isEmpty())) {
            return timeline == null ? "No trace found." : "No trace found.\nwarnings: " + String.join("; ", timeline.warnings());
        }
        StringBuilder sb = new StringBuilder("trace timeline\n");
        sb.append("traceId: ").append(blank(timeline.traceId())).append("\n");
        sb.append("sessionId: ").append(blank(timeline.sessionId())).append("\n");
        sb.append("taskId: ").append(blank(timeline.taskId())).append("\n");
        sb.append("status: ").append(timeline.status()).append("\n");
        sb.append("startedAt: ").append(blank(timeline.startedAt())).append("\n");
        sb.append("endedAt: ").append(blank(timeline.endedAt())).append("\n");
        sb.append("summary: ").append(blank(timeline.summary())).append("\n");
        sb.append("relatedWorkspace: ").append(related(timeline.relatedWorkspace(), "id")).append("\n");
        sb.append("relatedChangeSet: ").append(related(timeline.relatedChangeSet(), "id")).append("\n");
        sb.append("relatedReport: ").append(reportSummary(timeline.relatedReport())).append("\n");
        sb.append("warnings: ").append(timeline.warnings().isEmpty() ? "none" : String.join("; ", timeline.warnings())).append("\n\n");
        sb.append("timeline\n");
        if (timeline.events().isEmpty()) {
            sb.append("- none\n");
        } else {
            for (TraceTimelineEvent event : timeline.events()) {
                sb.append("- ").append(blank(event.timestamp()))
                        .append(" [").append(event.severity()).append("]")
                        .append(" ").append(event.source())
                        .append(" ").append(event.type())
                        .append(" ").append(event.title())
                        .append(event.detail().isBlank() ? "" : " | " + event.detail())
                        .append(event.refs().isEmpty() ? "" : " | refs=" + event.refs())
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String related(java.util.Map<String, Object> values, String key) {
        if (values == null || values.isEmpty()) {
            return "none";
        }
        Object id = values.get(key);
        return id != null && !String.valueOf(id).isBlank() ? String.valueOf(id) : values.toString();
    }

    private String reportSummary(java.util.Map<String, Object> report) {
        if (report == null || report.isEmpty()) {
            return "none";
        }
        return "status=" + report.getOrDefault("status", "")
                + " health=" + report.getOrDefault("health", "");
    }

    private String blank(String value) {
        return value != null && !value.isBlank() ? value : "none";
    }
}
