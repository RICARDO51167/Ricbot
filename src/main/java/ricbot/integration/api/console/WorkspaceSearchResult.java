package ricbot.integration.api.console;

public record WorkspaceSearchResult(
        String name,
        String path,
        WorkspaceNodeType type,
        long size,
        String modifiedAt,
        int score
) {
}
