package ricbot.integration.api.console;

import java.util.List;

public interface ConsoleEventStore {
    void append(ConsoleEvent event);

    List<ConsoleEvent> listBySession(String sessionId, String category, String after, int limit);

    List<ConsoleEvent> listByRun(String runId, String category, String after, int limit);

    ConsoleEvent findById(String eventId);

    List<ConsoleEvent> listAll(int limit);
}
