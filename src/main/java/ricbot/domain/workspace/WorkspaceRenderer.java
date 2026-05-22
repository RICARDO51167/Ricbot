package ricbot.domain.workspace;

import java.nio.file.Path;
import java.util.List;

public class WorkspaceRenderer {
    public String renderStatus(WorkspaceSession session) {
        if (session == null) {
            return "No active workspace session.";
        }
        return "workspace " + session.id() + "\n"
                + "type: " + session.type() + "\n"
                + "status: " + session.status() + "\n"
                + "baseWorkspace: " + blank(session.baseWorkspace()) + "\n"
                + "workspacePath: " + blank(session.workspacePath()) + "\n"
                + "branchName: " + blank(session.branchName()) + "\n"
                + "goal: " + blank(session.goal()) + "\n"
                + "updatedAt: " + session.updatedAt();
    }

    public String renderList(List<WorkspaceSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return "workspace sessions\n- none";
        }
        StringBuilder sb = new StringBuilder("workspace sessions\n");
        for (WorkspaceSession session : sessions) {
            sb.append("- ")
                    .append(session.id())
                    .append(" type=").append(session.type())
                    .append(" status=").append(session.status())
                    .append(" taskId=").append(blank(String.valueOf(session.metadata().getOrDefault("taskId", ""))))
                    .append(" branch=").append(blank(session.branchName()))
                    .append(" createdAt=").append(session.createdAt())
                    .append(" path=").append(session.workspacePath())
                    .append(" goal=").append(blank(session.goal()))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    public String renderDiff(WorkspaceSession session, String diff, int maxChars) {
        if (session == null) {
            return "No workspace session found.";
        }
        String value = diff != null ? diff : "";
        String rendered = value.length() <= maxChars ? value : value.substring(0, Math.max(0, maxChars)) + "\n[truncated]";
        return "workspace diff " + session.id() + "\n"
                + "type: " + session.type() + "\n"
                + "status: " + session.status() + "\n"
                + "workspacePath: " + session.workspacePath() + "\n\n"
                + (rendered.isBlank() ? "No diff." : rendered);
    }

    public String renderLifecycleStatus(WorkspaceLifecycleService.WorkspaceStatus status) {
        if (status == null) {
            return "No workspace status found.";
        }
        WorkspaceSession session = status.session();
        return "workspace status " + session.id() + "\n"
                + "taskId: " + blank(String.valueOf(session.metadata().getOrDefault("taskId", ""))) + "\n"
                + "type: " + session.type() + "\n"
                + "status: " + session.status() + "\n"
                + "branch: " + blank(status.branch()) + "\n"
                + "dirty: " + status.dirty() + "\n"
                + "workspacePath: " + session.workspacePath() + "\n"
                + "gitStatus:\n"
                + (status.statusShort().isBlank() ? "  clean" : indent(status.statusShort()));
    }

    public String renderLifecycleDiff(WorkspaceLifecycleService.WorkspaceDiff diff, int maxChars) {
        if (diff == null) {
            return "No workspace diff found.";
        }
        WorkspaceSession session = diff.session();
        String patch = diff.patch().length() <= maxChars ? diff.patch() : diff.patch().substring(0, Math.max(0, maxChars)) + "\n[truncated]";
        return "workspace diff " + session.id() + "\n"
                + "taskId: " + blank(String.valueOf(session.metadata().getOrDefault("taskId", ""))) + "\n"
                + "workspacePath: " + session.workspacePath() + "\n"
                + "changedFiles: " + (diff.changedFiles().isEmpty() ? "none" : String.join(", ", diff.changedFiles())) + "\n"
                + "diffStat:\n"
                + (diff.stat().isBlank() ? "  none" : indent(diff.stat()))
                + "\n\n"
                + (patch.isBlank() ? "No diff." : patch);
    }

    public String sessionSource(Path baseWorkspace, WorkspaceSession session) {
        if (baseWorkspace == null || session == null) {
            return "";
        }
        return ".workspaces/" + session.id() + "/session.json";
    }

    private String blank(String value) {
        return value != null && !value.isBlank() ? value : "(none)";
    }

    private String indent(String value) {
        if (value == null || value.isBlank()) {
            return "  none";
        }
        return value.lines().map(line -> "  " + line).reduce((left, right) -> left + "\n" + right).orElse("  none");
    }
}
