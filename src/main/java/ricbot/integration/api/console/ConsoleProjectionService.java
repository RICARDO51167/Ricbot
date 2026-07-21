package ricbot.integration.api.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.agent.RunEvent;
import ricbot.domain.agent.RunJournalStore;
import ricbot.domain.agent.RunState;
import ricbot.domain.team.TeamEvent;
import ricbot.domain.team.TeamSession;
import ricbot.domain.team.TeamSessionStore;
import ricbot.domain.worker.WorkerStore;
import ricbot.infra.persistence.FileRuntimeFactJournal;
import ricbot.infra.persistence.RuntimeFactEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Rebuildable Console read model sourced only from durable runtime state. */
public final class ConsoleProjectionService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final RunJournalStore runJournal;
    private final FileRuntimeFactJournal factJournal;
    private final TeamSessionStore teams;
    private final WorkerStore workers;

    public ConsoleProjectionService(Path workspace, RunJournalStore runJournal) {
        Path root = workspace != null ? workspace.toAbsolutePath().normalize() : Path.of(".").toAbsolutePath().normalize();
        this.runJournal = runJournal != null ? runJournal : RunJournalStore.disabled();
        this.factJournal = new FileRuntimeFactJournal(root);
        this.teams = new TeamSessionStore(root);
        this.workers = new WorkerStore(root);
    }

    public List<ConsoleEvent> eventsForSession(String sessionId) {
        String session = clean(sessionId);
        if (session.isBlank()) return List.of();
        List<ConsoleEvent> events = new ArrayList<>();
        appendRunEvents(events, session);
        for (RuntimeFactEvent fact : factJournal.allEvents(50_000)) {
            if (session.equals(fact.sessionKey())) events.add(fromFact(fact));
        }
        for (TeamEvent event : teams.loadEvents(session)) events.add(fromTeam(event));
        for (WorkerStore.StoredWorker worker : workers.list()) {
            if (session.equals(worker.spec().scopeId())) events.add(fromWorker(worker));
        }
        return orderedDistinct(events);
    }

    public List<ConsoleEvent> allEvents(Collection<String> knownSessionIds) {
        Set<String> sessions = new LinkedHashSet<>();
        if (knownSessionIds != null) knownSessionIds.stream().map(ConsoleProjectionService::clean)
                .filter(value -> !value.isBlank()).forEach(sessions::add);
        List<RuntimeFactEvent> facts = factJournal.allEvents(50_000);
        facts.stream().map(RuntimeFactEvent::sessionKey).forEach(sessions::add);
        teams.listSessions().stream().map(TeamSession::id).forEach(sessions::add);
        workers.list().stream().map(worker -> worker.spec().scopeId()).forEach(sessions::add);

        List<ConsoleEvent> events = new ArrayList<>();
        for (String session : sessions) appendRunEvents(events, session);
        facts.stream().map(ConsoleProjectionService::fromFact).forEach(events::add);
        for (TeamSession session : teams.listSessions()) {
            teams.loadEvents(session.id()).stream().map(ConsoleProjectionService::fromTeam).forEach(events::add);
        }
        workers.list().stream().map(ConsoleProjectionService::fromWorker).forEach(events::add);
        return orderedDistinct(events);
    }

    private void appendRunEvents(List<ConsoleEvent> target, String sessionId) {
        for (RunState run : runJournal.runs(sessionId)) {
            for (RunEvent event : runJournal.events(sessionId, run.runId(), 0)) target.add(fromRun(event));
        }
    }

    private static ConsoleEvent fromRun(RunEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>(event.details());
        payload.put("sequence", event.sequence());
        payload.put("iteration", event.iteration());
        if (event.toolInvocation() != null) {
            payload.put("toolInvocation", MAPPER.convertValue(event.toolInvocation(), Map.class));
        }
        String name = event.type().name().toLowerCase(Locale.ROOT);
        return new ConsoleEvent(
                event.eventId(), event.sessionKey(), event.runId(), "run_event", name,
                category(name), event.status().name(), event.occurredAt().toString(),
                title(name), summary(event.details(), name), "agent", "run_journal", payload);
    }

    private static ConsoleEvent fromFact(RuntimeFactEvent event) {
        String type = event.type().toLowerCase(Locale.ROOT);
        String name = type.startsWith("console.action.")
                ? type.substring("console.action.".length()).replace('.', '_')
                : type.startsWith("trace.")
                ? type.substring("trace.".length())
                : type.startsWith("console.projection.")
                ? type.substring("console.projection.".length()).replace('.', '_')
                : type.replace('.', '_');
        String projectedCategory = text(event.details().get("projected_category"));
        String projectedStatus = text(event.details().get("projected_status"));
        return new ConsoleEvent(
                event.eventId(), event.sessionKey(), text(event.details().get("run_id")), "fact_event", name,
                projectedCategory.isBlank() ? category(name) : projectedCategory,
                projectedStatus.isBlank() ? status(name) : projectedStatus,
                event.occurredAt().toString(), title(name), event.message(),
                event.actor(), "runtime_fact_journal", event.details());
    }

    private static ConsoleEvent fromTeam(TeamEvent event) {
        String name = event.type().toLowerCase(Locale.ROOT);
        Map<String, Object> payload = new LinkedHashMap<>(event.metadata());
        payload.put("taskId", event.taskId());
        return new ConsoleEvent(
                event.eventId(), event.sessionId(), "", "team_event", name, "team", status(name),
                event.createdAt(), title(name), event.message(), event.actor(), "team_state", payload);
    }

    private static ConsoleEvent fromWorker(WorkerStore.StoredWorker worker) {
        var spec = worker.spec();
        var state = worker.state();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workerId", spec.workerId());
        payload.put("role", spec.role());
        payload.put("goal", spec.goal());
        payload.put("currentRunId", state.currentRunId());
        payload.put("detail", state.detail());
        payload.put("version", state.version());
        return new ConsoleEvent(
                "worker-state-" + spec.workerId() + "-" + state.version(), spec.scopeId(), state.currentRunId(),
                "worker_state", "worker_" + state.status().name().toLowerCase(Locale.ROOT), "worker",
                state.status().name(), state.updatedAt().toString(), "Worker: " + spec.workerId(), state.detail(),
                spec.role(), "worker_state", payload);
    }

    private static List<ConsoleEvent> orderedDistinct(List<ConsoleEvent> events) {
        Map<String, ConsoleEvent> byId = new LinkedHashMap<>();
        events.stream().sorted(Comparator.comparing(ConsoleEvent::time).thenComparing(ConsoleEvent::id))
                .forEach(event -> byId.putIfAbsent(event.id(), event));
        return List.copyOf(byId.values());
    }

    private static String category(String name) {
        String value = clean(name).toLowerCase(Locale.ROOT);
        if (value.contains("failed") || value.contains("error") || value.contains("denied")) return "error";
        if (value.contains("approval")) return "approval";
        if (value.contains("changeset") || value.contains("diff")) return "changeset";
        if (value.contains("tool")) return "tool";
        if (value.contains("worker")) return "worker";
        if (value.contains("team") || value.contains("task") || value.contains("verification")) return "team";
        if (value.contains("run") || value.contains("model") || value.contains("checkpoint")) return "run";
        return "system";
    }

    private static String status(String name) {
        String value = clean(name).toLowerCase(Locale.ROOT);
        if (value.contains("failed") || value.contains("rejected") || value.contains("denied")) return "ERROR";
        if (value.contains("completed") || value.contains("finished") || value.contains("approved")
                || value.contains("committed") || value.contains("succeeded") || value.contains("passed")) return "SUCCESS";
        if (value.contains("cancel")) return "CANCELLED";
        if (value.contains("requested") || value.contains("pending") || value.contains("required")) return "PENDING";
        return "INFO";
    }

    private static String title(String name) {
        String value = clean(name).replace('_', ' ').replace('.', ' ');
        if (value.isBlank()) return "Runtime event";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String summary(Map<String, Object> details, String fallback) {
        for (String key : List.of("summary", "reason", "message", "error")) {
            String value = text(details.get(key));
            if (!value.isBlank()) return value;
        }
        return title(fallback);
    }

    private static String text(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
