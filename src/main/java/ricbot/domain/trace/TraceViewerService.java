package ricbot.domain.trace;

import ricbot.domain.change.ChangeSetService;
import ricbot.domain.change.GitChangeSet;
import ricbot.domain.team.StepAuditRecord;
import ricbot.domain.team.StepAuditService;
import ricbot.domain.team.TeamEngine;
import ricbot.domain.team.TeamSession;
import ricbot.domain.team.TeamTask;
import ricbot.domain.team.TeamTaskReport;
import ricbot.domain.workspace.WorkspaceSession;
import ricbot.domain.workspace.WorkspaceSessionStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TraceViewerService {
    private final Path workspace;
    private final TraceStore traceStore;
    private final StepAuditService stepAuditService;
    private final TeamEngine teamEngine;
    private final WorkspaceSessionStore workspaceStore;
    private final ChangeSetService changeSetService;

    public TraceViewerService(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.traceStore = new TraceStore(this.workspace);
        this.stepAuditService = new StepAuditService(this.workspace);
        this.teamEngine = new TeamEngine(this.workspace);
        this.workspaceStore = new WorkspaceSessionStore(this.workspace);
        this.changeSetService = new ChangeSetService(this.workspace);
    }

    public TraceTimeline lastTimeline() {
        TraceStore.TraceSummary latest = traceStore.loadLatestTrace();
        if (latest != null && latest.eventCount() > 0) {
            return show(latest.traceId());
        }
        TeamTask latestTask = latestTask();
        if (latestTask != null) {
            return show(latestTask.id());
        }
        return empty("No trace found.");
    }

    public TraceTimeline show(String id) {
        String token = clean(id);
        if (token.isBlank()) {
            return empty("No trace found: missing id.");
        }
        if (isTaskId(token)) {
            return byTask(token);
        }
        if (traceStore.listTraces().contains(token)) {
            return byTrace(token, "");
        }
        String traceId = token.startsWith("trace_") ? token : traceStore.traceIdForSession(token);
        if (!traceStore.loadEvents(traceId).isEmpty()) {
            return byTrace(traceId, token.startsWith("trace_") ? "" : token);
        }
        TeamTask task = findTask(token);
        if (task != null) {
            return byTask(task.id());
        }
        return empty("No trace found for: " + token);
    }

    private TraceTimeline byTask(String taskId) {
        List<String> warnings = new ArrayList<>();
        List<TraceTimelineEvent> events = new ArrayList<>();
        TeamTask task = findTask(taskId);
        if (task == null) {
            warnings.add("team task not found: " + taskId);
        }
        String sessionId = task != null ? task.sessionId() : "";
        String traceId = !sessionId.isBlank() ? traceStore.traceIdForSession(sessionId) : "";
        List<TraceEvent> traceEvents = traceId.isBlank() ? List.of() : traceStore.loadEvents(traceId).stream()
                .filter(event -> matchesTask(event, taskId))
                .toList();
        if (traceEvents.isEmpty()) {
            warnings.add("no trace events found for task: " + taskId);
        }
        events.addAll(traceEvents.stream().map(this::fromTrace).toList());

        List<StepAuditRecord> auditRecords = stepAuditService.listByTask(taskId);
        if (auditRecords.isEmpty()) {
            warnings.add("no step audit records found for task: " + taskId);
        }
        events.addAll(auditRecords.stream().map(this::fromAudit).toList());

        Map<String, Object> relatedWorkspace = relatedWorkspace(taskId);
        if (relatedWorkspace.isEmpty()) {
            warnings.add("no workspace session found for task: " + taskId);
        } else {
            events.add(workspaceEvent(relatedWorkspace));
        }

        GitChangeSet changeSet = relatedChangeSet(taskId, relatedWorkspace);
        Map<String, Object> relatedChangeSet = changeSet != null ? changeSet.toMap() : Map.of();
        if (changeSet == null) {
            warnings.add("no changeset found for task: " + taskId);
        } else {
            events.add(changeSetEvent(changeSet));
        }

        TeamTaskReport report = task != null ? safeReport(task) : null;
        Map<String, Object> relatedReport = report != null ? report.toMap() : Map.of();
        if (report != null) {
            events.add(reportEvent(report));
        }
        return build(traceId, sessionId, taskId, events, warnings, relatedWorkspace, relatedChangeSet, relatedReport);
    }

    private TraceTimeline byTrace(String traceId, String sessionIdHint) {
        List<String> warnings = new ArrayList<>();
        List<TraceTimelineEvent> events = new ArrayList<>();
        List<TraceEvent> traceEvents = traceStore.loadEvents(traceId);
        if (traceEvents.isEmpty()) {
            warnings.add("no trace events found for trace: " + traceId);
        }
        events.addAll(traceEvents.stream().map(this::fromTrace).toList());
        String sessionId = !clean(sessionIdHint).isBlank()
                ? clean(sessionIdHint)
                : traceEvents.stream().map(TraceEvent::sessionId).filter(value -> !value.isBlank()).findFirst().orElse("");
        String taskId = firstTaskId(traceEvents);
        Map<String, Object> workspace = !taskId.isBlank() ? relatedWorkspace(taskId) : Map.of();
        GitChangeSet changeSet = !taskId.isBlank() ? relatedChangeSet(taskId, workspace) : null;
        Map<String, Object> report = Map.of();
        if (!taskId.isBlank()) {
            TeamTask task = findTask(taskId);
            TeamTaskReport taskReport = task != null ? safeReport(task) : null;
            report = taskReport != null ? taskReport.toMap() : Map.of();
        }
        return build(traceId, sessionId, taskId, events, warnings, workspace,
                changeSet != null ? changeSet.toMap() : Map.of(), report);
    }

    private TraceTimeline build(
            String traceId,
            String sessionId,
            String taskId,
            List<TraceTimelineEvent> rawEvents,
            List<String> warnings,
            Map<String, Object> workspace,
            Map<String, Object> changeSet,
            Map<String, Object> report
    ) {
        List<TraceTimelineEvent> sorted = rawEvents.stream()
                .sorted(Comparator.comparing(event -> event.timestamp().isBlank() ? "9999-12-31T23:59:59Z" : event.timestamp()))
                .toList();
        String startedAt = sorted.isEmpty() ? "" : sorted.get(0).timestamp();
        String endedAt = sorted.isEmpty() ? "" : sorted.get(sorted.size() - 1).timestamp();
        String status = status(sorted, report);
        String summary = "events=" + sorted.size()
                + (!taskId.isBlank() ? " task=" + taskId : "")
                + (!workspace.isEmpty() ? " workspace=" + workspace.getOrDefault("id", workspace.getOrDefault("workspaceId", "")) : "")
                + (!changeSet.isEmpty() ? " changeSet=" + changeSet.getOrDefault("id", "") : "");
        return new TraceTimeline(traceId, sessionId, taskId, startedAt, endedAt, status, sorted, summary,
                dedupe(warnings), workspace, changeSet, report);
    }

    private TraceTimeline empty(String warning) {
        return new TraceTimeline("", "", "", "", "", "UNKNOWN", List.of(), "No trace found.",
                List.of(warning), Map.of(), Map.of(), Map.of());
    }

    private TraceTimelineEvent fromTrace(TraceEvent event) {
        return new TraceTimelineEvent(
                event.createdAt(),
                event.type().name(),
                event.type().name(),
                event.message(),
                TraceTimelineSource.TRACE,
                severity(event.type().name(), event.message(), event.payload()),
                refs(event.sessionId(), event.teamSessionId(), taskId(event), workspaceId(event), event.changeSetId())
        );
    }

    private TraceTimelineEvent fromAudit(StepAuditRecord record) {
        return new TraceTimelineEvent(
                record.createdAt(),
                record.eventType().name(),
                record.eventType().name(),
                record.message(),
                TraceTimelineSource.STEP_AUDIT,
                severity(record.eventType().name(), record.message(), Map.of("status", record.verificationStatus())),
                refs("", record.teamSessionId(), record.taskId(), stringValue(record.metadata().get("workspaceSessionId")), record.changeSetId())
        );
    }

    private TraceTimelineEvent workspaceEvent(Map<String, Object> workspace) {
        return new TraceTimelineEvent(
                stringValue(workspace.get("createdAt")),
                "WORKSPACE",
                "Workspace",
                "workspace " + workspace.getOrDefault("id", "") + " status=" + workspace.getOrDefault("status", ""),
                TraceTimelineSource.WORKSPACE,
                TraceTimelineSeverity.INFO,
                refs("", stringValue(workspace.get("teamSessionId")), stringValue(workspace.get("taskId")), stringValue(workspace.get("id")), "")
        );
    }

    private TraceTimelineEvent changeSetEvent(GitChangeSet changeSet) {
        return new TraceTimelineEvent(
                changeSet.createdAt(),
                "CHANGESET",
                "ChangeSet " + changeSet.status(),
                changeSet.diffSummary(),
                TraceTimelineSource.CHANGESET,
                TraceTimelineSeverity.INFO,
                refs(changeSet.sessionId(), changeSet.teamSessionId(), changeSet.taskId(), changeSet.workspaceSessionId(), changeSet.id())
        );
    }

    private TraceTimelineEvent reportEvent(TeamTaskReport report) {
        return new TraceTimelineEvent(
                "",
                "TEAM_REPORT",
                "TeamTaskReport " + report.status(),
                "health=" + report.health() + " progress=" + report.completedSteps() + "/" + report.totalSteps(),
                TraceTimelineSource.TEAM_REPORT,
                report.health().name().equals("CRITICAL") ? TraceTimelineSeverity.ERROR : TraceTimelineSeverity.INFO,
                refs("", report.teamSessionId(), report.taskId(), "", "")
        );
    }

    private Map<String, Object> relatedWorkspace(String taskId) {
        return workspaceStore.list().stream()
                .filter(session -> taskId.equals(String.valueOf(session.metadata().getOrDefault("taskId", "")).trim()))
                .findFirst()
                .map(session -> {
                    Map<String, Object> out = new LinkedHashMap<>(session.toMap());
                    out.put("taskId", String.valueOf(session.metadata().getOrDefault("taskId", "")));
                    out.put("teamSessionId", String.valueOf(session.metadata().getOrDefault("teamSessionId", "")));
                    return out;
                })
                .orElse(Map.of());
    }

    private GitChangeSet relatedChangeSet(String taskId, Map<String, Object> workspace) {
        String workspaceId = stringValue(workspace.get("id"));
        return changeSetService.list().stream()
                .filter(changeSet -> taskId.equals(changeSet.taskId()) || (!workspaceId.isBlank() && workspaceId.equals(changeSet.workspaceSessionId())))
                .findFirst()
                .orElse(null);
    }

    private TeamTaskReport safeReport(TeamTask task) {
        try {
            return teamEngine.taskReport(task.id());
        } catch (Exception e) {
            return null;
        }
    }

    private TeamTask findTask(String taskId) {
        TeamTask task = teamEngine.findTask(taskId);
        if (task != null) {
            return task;
        }
        for (TeamSession session : teamEngine.listSessions()) {
            for (TeamTask candidate : session.tasks()) {
                if (candidate.id().equals(taskId)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private TeamTask latestTask() {
        return teamEngine.listSessions().stream()
                .flatMap(session -> session.tasks().stream())
                .max(Comparator.comparing(TeamTask::updatedAt))
                .orElse(null);
    }

    private boolean matchesTask(TraceEvent event, String taskId) {
        return taskId.equals(taskId(event))
                || taskId.equals(String.valueOf(event.payload().getOrDefault("taskId", "")))
                || taskId.equals(String.valueOf(event.payload().getOrDefault("developerTaskId", "")));
    }

    private String firstTaskId(List<TraceEvent> events) {
        return events.stream().map(this::taskId).filter(value -> !value.isBlank()).findFirst().orElse("");
    }

    private String taskId(TraceEvent event) {
        Object raw = event.payload().get("taskId");
        return raw != null ? String.valueOf(raw).trim() : "";
    }

    private String workspaceId(TraceEvent event) {
        Object raw = event.payload().get("workspaceSessionId");
        return raw != null ? String.valueOf(raw).trim() : "";
    }

    private Map<String, String> refs(String sessionId, String teamSessionId, String taskId, String workspaceId, String changeSetId) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "sessionId", sessionId);
        put(out, "teamSessionId", teamSessionId);
        put(out, "taskId", taskId);
        put(out, "workspaceId", workspaceId);
        put(out, "changeSetId", changeSetId);
        return out;
    }

    private void put(Map<String, String> out, String key, String value) {
        String cleaned = clean(value);
        if (!cleaned.isBlank()) {
            out.put(key, cleaned);
        }
    }

    private TraceTimelineSeverity severity(String type, String message, Map<String, Object> payload) {
        String text = (type + " " + message + " " + payload).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("failed") || text.contains("error") || text.contains("reject") || text.contains("denied")) {
            return TraceTimelineSeverity.ERROR;
        }
        if (text.contains("blocked") || text.contains("approval") || text.contains("warning")) {
            return TraceTimelineSeverity.WARNING;
        }
        return TraceTimelineSeverity.INFO;
    }

    private String status(List<TraceTimelineEvent> events, Map<String, Object> report) {
        Object reportStatus = report.get("status");
        if (reportStatus != null && !String.valueOf(reportStatus).isBlank()) {
            return String.valueOf(reportStatus);
        }
        if (events.stream().anyMatch(event -> event.severity() == TraceTimelineSeverity.ERROR)) {
            return "FAILED";
        }
        return events.isEmpty() ? "UNKNOWN" : "COMPLETED";
    }

    private List<String> dedupe(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values != null ? values : List.<String>of()) {
            String cleaned = clean(value);
            if (!cleaned.isBlank() && !out.contains(cleaned)) {
                out.add(cleaned);
            }
        }
        return List.copyOf(out);
    }

    private boolean isTaskId(String value) {
        return clean(value).startsWith("teamtask_");
    }

    private String stringValue(Object raw) {
        return raw != null ? String.valueOf(raw).trim() : "";
    }

    private String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
