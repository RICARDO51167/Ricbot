package ricbot.integration.api.console;

import java.util.List;

public record WorkspaceTreeNode(
        String name,
        String path,
        WorkspaceNodeType type,
        long size,
        String modifiedAt,
        List<WorkspaceTreeNode> children
) {
}
