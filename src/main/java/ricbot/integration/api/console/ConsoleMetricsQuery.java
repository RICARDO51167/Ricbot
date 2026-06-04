package ricbot.integration.api.console;

public record ConsoleMetricsQuery(
        String sessionId,
        String since,
        String until
) {
}
