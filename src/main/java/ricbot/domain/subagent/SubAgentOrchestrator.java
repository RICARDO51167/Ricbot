package ricbot.domain.subagent;

import ricbot.domain.agent.SessionRuntimeKeys;
import ricbot.domain.agent.TaskSummaryService;
import ricbot.domain.note.NoteService;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import ricbot.tool.filesystem.DiffReview;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SubAgentOrchestrator {
    private static final String DEFAULT_SESSION = "_default";
    private static final String OUTPUT_SCHEMA = "summary: string; findings: string[]; risks: string[]; "
            + "suggestedTests: string[]; relatedFiles: string[]; confidence: number";

    private final SessionManager sessionManager;
    private final NoteService noteService;
    private final Map<String, List<SubAgentResult>> inMemoryResults = new LinkedHashMap<>();

    public SubAgentOrchestrator() {
        this(null, null);
    }

    public SubAgentOrchestrator(SessionManager sessionManager) {
        this(sessionManager, null);
    }

    public SubAgentOrchestrator(SessionManager sessionManager, Path workspace) {
        this.sessionManager = sessionManager;
        this.noteService = workspace != null ? new NoteService(workspace) : null;
    }

    public SubAgentTask createPlannerTask(String userGoal, Object taskState) {
        String input = "userGoal: " + clean(userGoal)
                + "\ntaskState: " + (taskState != null ? String.valueOf(taskState) : "(none)");
        return new SubAgentTask(
                null,
                SubAgentRole.PLANNER,
                clean(userGoal),
                input,
                OUTPUT_SCHEMA,
                List.of(),
                null,
                "CREATED"
        );
    }

    public SubAgentTask createExplorerTask(String userGoal, List<String> relatedFiles, List<String> contextSources) {
        String input = "userGoal: " + clean(userGoal)
                + "\nrelatedFiles: " + join(relatedFiles)
                + "\ncontextSources: " + join(contextSources);
        return new SubAgentTask(
                null,
                SubAgentRole.EXPLORER,
                clean(userGoal),
                input,
                OUTPUT_SCHEMA,
                relatedFiles,
                null,
                "CREATED"
        );
    }

    public SubAgentTask createReviewerTask(
            DiffReview diffReview,
            TaskSummaryService.TaskSummary taskSummary
    ) {
        List<String> files = diffReview != null ? diffReview.changedFiles()
                : taskSummary != null ? taskSummary.changedFiles()
                : List.of();
        String input = "diffReview: " + (diffReview != null ? diffReview.summary() : "(none)")
                + "\ntaskSummary: " + (taskSummary != null ? taskSummary.toMap() : Map.of());
        return new SubAgentTask(
                null,
                SubAgentRole.REVIEWER,
                taskSummary != null && !taskSummary.goal().isBlank() ? taskSummary.goal() : "Review current task",
                input,
                OUTPUT_SCHEMA,
                files,
                null,
                "CREATED"
        );
    }

    public SubAgentResult recordResult(SubAgentResult result) {
        return recordResult(DEFAULT_SESSION, result);
    }

    public SubAgentResult recordResult(String sessionId, SubAgentResult result) {
        String key = sessionId != null && !sessionId.isBlank() ? sessionId : DEFAULT_SESSION;
        SubAgentResult safe = result != null ? result : new SubAgentResult("", SubAgentRole.EXPLORER, "", List.of(), List.of(), List.of(), List.of(), 0.1d, null);
        if (sessionManager == null || DEFAULT_SESSION.equals(key)) {
            inMemoryResults.computeIfAbsent(key, ignored -> new ArrayList<>()).add(safe);
        } else {
            Session session = sessionManager.getOrCreate(key);
            List<SubAgentResult> results = new ArrayList<>(resultsFromSession(session));
            results.add(safe);
            writeResults(session, results);
            sessionManager.save(session);
        }
        writeTemporaryNote(safe);
        return safe;
    }

    public List<SubAgentResult> listRecentResults(String sessionId) {
        String key = sessionId != null && !sessionId.isBlank() ? sessionId : DEFAULT_SESSION;
        List<SubAgentResult> results;
        if (sessionManager != null && !DEFAULT_SESSION.equals(key)) {
            results = resultsFromSession(sessionManager.getOrCreate(key));
        } else {
            results = inMemoryResults.getOrDefault(key, List.of());
        }
        return results.stream()
                .sorted(Comparator.comparing(SubAgentResult::createdAt, Comparator.nullsLast(String::compareTo)).reversed())
                .limit(10)
                .toList();
    }

    public String renderResultsForContext(String sessionId) {
        List<SubAgentResult> results = listRecentResults(sessionId).stream().limit(3).toList();
        if (results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SubAgentResult result : results) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(renderCompact(result));
        }
        return sb.toString();
    }

    public static List<SubAgentResult> resultsFromSession(Session session) {
        if (session == null || session.getMetadata() == null) {
            return List.of();
        }
        Object raw = session.getMetadata().get(SessionRuntimeKeys.SUBAGENT_RESULTS_KEY);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<SubAgentResult> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                SubAgentResult result = SubAgentResult.fromMap(map);
                if (result != null) {
                    out.add(result);
                }
            }
        }
        return out;
    }

    public static String renderCompact(SubAgentResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(result.role()).append(" task=").append(result.taskId())
                .append(" summary=").append(result.summary());
        if (!result.findings().isEmpty()) {
            sb.append(" findings=").append(String.join("; ", result.findings()));
        }
        if (!result.risks().isEmpty()) {
            sb.append(" risks=").append(String.join("; ", result.risks()));
        }
        if (!result.suggestedTests().isEmpty()) {
            sb.append(" suggestedTests=").append(String.join("; ", result.suggestedTests()));
        }
        sb.append(" confidence=").append(String.format(java.util.Locale.ROOT, "%.2f", result.confidence()));
        return sb.toString();
    }

    public static String renderDetail(SubAgentResult result) {
        if (result == null) {
            return "SubAgent result not found.";
        }
        return "SubAgentResult " + result.taskId()
                + "\nrole: " + result.role()
                + "\nsummary: " + result.summary()
                + "\nfindings: " + join(result.findings())
                + "\nrisks: " + join(result.risks())
                + "\nsuggestedTests: " + join(result.suggestedTests())
                + "\nrelatedFiles: " + join(result.relatedFiles())
                + "\nconfidence: " + String.format(java.util.Locale.ROOT, "%.2f", result.confidence())
                + "\ncreatedAt: " + result.createdAt();
    }

    private void writeResults(Session session, List<SubAgentResult> results) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<SubAgentResult> limited = results.stream()
                .sorted(Comparator.comparing(SubAgentResult::createdAt, Comparator.nullsLast(String::compareTo)).reversed())
                .limit(20)
                .sorted(Comparator.comparing(SubAgentResult::createdAt, Comparator.nullsLast(String::compareTo)))
                .toList();
        for (SubAgentResult result : limited) {
            rows.add(result.toMap());
        }
        session.getMetadata().put(SessionRuntimeKeys.SUBAGENT_RESULTS_KEY, rows);
    }

    private void writeTemporaryNote(SubAgentResult result) {
        if (noteService == null || result == null) {
            return;
        }
        noteService.create(
                "SubAgent " + result.role() + " - " + result.taskId(),
                "temporary",
                "note",
                "# SubAgent " + result.role() + "\n\n" + renderDetail(result) + "\n",
                List.of("subagent", result.role().name().toLowerCase(java.util.Locale.ROOT), "temporary")
        );
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }
}
