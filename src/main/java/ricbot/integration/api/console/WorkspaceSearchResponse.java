package ricbot.integration.api.console;

import java.util.List;

public record WorkspaceSearchResponse(
        String workspace,
        String keyword,
        List<WorkspaceSearchResult> results
) {
}
