package ricbot.integration.api.console;

public record WorkspaceFileContent(
        String path,
        String language,
        long size,
        String modifiedAt,
        boolean binary,
        boolean truncated,
        String content
) {
}
