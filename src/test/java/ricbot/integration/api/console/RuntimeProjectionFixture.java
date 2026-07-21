package ricbot.integration.api.console;

import ricbot.domain.agent.FileRunJournalStore;
import ricbot.domain.agent.RunEvent;
import ricbot.domain.agent.RunEventType;
import ricbot.domain.agent.RunState;
import ricbot.domain.agent.RunStatus;
import ricbot.infra.persistence.FileRuntimeFactJournal;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/** Test fixture that seeds canonical runtime state from the old ConsoleEvent-shaped examples. */
public final class RuntimeProjectionFixture {
    private final Path workspace;
    private final FileRunJournalStore runs;
    private final FileRuntimeFactJournal facts;

    public RuntimeProjectionFixture(Path workspace) {
        this.workspace = workspace;
        this.runs = new FileRunJournalStore(workspace);
        this.facts = new FileRuntimeFactJournal(workspace);
    }

    public void append(ConsoleEvent event) {
        if (event == null) return;
        if ("run".equals(event.category()) && isLifecycle(event.name()) && !event.runId().isBlank()) {
            appendRun(event);
            return;
        }
        var details = new LinkedHashMap<>(event.payload());
        details.put("run_id", firstNonBlank(event.runId(), event.payload().get("runId"), event.payload().get("run_id")));
        details.put("projected_category", event.category());
        details.put("projected_status", event.status());
        facts.append(event.id(), event.sessionId(), "console.projection." + event.name(), event.actor(),
                event.summary(), details, instant(event.time()));
    }

    public List<ConsoleEvent> listBySession(String sessionId, String category, String after, int limit) {
        List<ConsoleEvent> events = sessionId == null || sessionId.isBlank()
                ? new ConsoleProjectionService(workspace, runs).allEvents(List.of())
                : new ConsoleProjectionService(workspace, runs).eventsForSession(sessionId);
        return events.stream()
                .filter(event -> category == null || category.isBlank() || category.equals(event.category()))
                .filter(new java.util.function.Predicate<>() {
                    private boolean past = after == null || after.isBlank();
                    @Override public boolean test(ConsoleEvent event) {
                        if (past) return true;
                        if (after.equals(event.id())) past = true;
                        return false;
                    }
                })
                .limit(limit > 0 ? limit : 500)
                .toList();
    }

    private void appendRun(ConsoleEvent event) {
        RunState current = runs.load(event.sessionId(), event.runId()).orElse(null);
        long sequence = current != null ? current.lastSequence() + 1 : 1;
        RunEventType type;
        RunStatus status;
        String name = event.name().toLowerCase(java.util.Locale.ROOT);
        if (current == null) {
            type = RunEventType.RUN_STARTED;
            status = RunStatus.CREATED;
        } else if (name.contains("finish")) {
            type = RunEventType.RUN_FINISHED;
            status = RunStatus.COMPLETED;
        } else if (name.contains("fail") || name.contains("error")) {
            type = RunEventType.RUN_FINISHED;
            status = RunStatus.FAILED;
        } else if (name.contains("cancel")) {
            type = RunEventType.RUN_FINISHED;
            status = RunStatus.CANCELLED;
        } else {
            type = RunEventType.MODEL_REQUESTED;
            status = RunStatus.MODEL_RUNNING;
        }
        var details = new LinkedHashMap<>(event.payload());
        details.put("projected_name", event.name());
        details.put("summary", event.summary());
        runs.append(new RunEvent(
                RunEvent.CURRENT_SCHEMA_VERSION, event.id(), sequence, event.runId(), event.sessionId(), 0,
                type, status, null, details, instant(event.time())));
    }

    private static boolean isLifecycle(String name) {
        String value = name != null ? name.toLowerCase(java.util.Locale.ROOT) : "";
        return value.startsWith("run_") || value.startsWith("model_");
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return Instant.now();
        }
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = value != null ? String.valueOf(value).trim() : "";
            if (!text.isBlank()) return text;
        }
        return "";
    }
}
