package ricbot.integration.api.console;

public record ConsoleEventSearchQuery(
        String sessionId,
        String runId,
        String category,
        String status,
        String keyword,
        String since,
        String until,
        String after,
        int limit
) {
}
