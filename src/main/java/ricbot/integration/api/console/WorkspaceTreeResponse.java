package ricbot.integration.api.console;

import java.util.List;

public record WorkspaceTreeResponse(
        String workspace,
        String root,
        List<WorkspaceTreeNode> nodes
) {
}
