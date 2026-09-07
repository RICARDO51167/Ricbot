package ricbot.application.runtime;

import ricbot.domain.runtime.DurableAgentRuntime;
import ricbot.domain.runtime.ExternalEvent;
import ricbot.domain.runtime.RunQuery;
import ricbot.domain.runtime.RunView;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Single durable cancellation boundary shared by /stop and /run cancel. */
public final class RunCancellationService {
    private final DurableAgentRuntime runtime;
    private final RunQuery query;
    private final Clock clock;

    public RunCancellationService(DurableAgentRuntime runtime, RunQuery query, Clock clock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.query = Objects.requireNonNull(query, "query");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RunView cancel(String runId, String reason) {
        String id = required(runId, "runId");
        return runtime.submit(id, new ExternalEvent.CancelRequested(
                "cancel-" + UUID.randomUUID(), id, clock.instant(),
                Map.of("reason", clean(reason))));
    }

    public List<RunView> cancelSession(String sessionId, String reason) {
        String id = required(sessionId, "sessionId");
        List<RunView> cancelled = new ArrayList<>();
        for (RunView view : query.list()) {
            if (view.state().status().terminal()) continue;
            String owner = String.valueOf(view.state().spec().metadata().getOrDefault("sessionId", "")).trim();
            if (!id.equals(owner)) continue;
            cancelled.add(cancel(view.state().spec().runId(), reason));
        }
        return List.copyOf(cancelled);
    }

    private static String required(String value, String name) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(name + " is required");
        return clean;
    }

    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
