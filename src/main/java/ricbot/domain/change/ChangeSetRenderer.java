package ricbot.domain.change;

public class ChangeSetRenderer {

    public String renderStatus(GitChangeSet changeSet) {
        if (changeSet == null) {
            return "No ChangeSet found.";
        }
        return "changeset " + changeSet.id() + "\n"
                + "status: " + changeSet.status() + "\n"
                + "baseCommit: " + changeSet.baseCommit() + "\n"
                + "teamSessionId: " + blank(changeSet.teamSessionId()) + "\n"
                + "taskId: " + blank(changeSet.taskId()) + "\n"
                + "workspaceSessionId: " + blank(changeSet.workspaceSessionId()) + "\n"
                + "workspacePath: " + blank(changeSet.workspacePath()) + "\n"
                + "changedFiles: " + (changeSet.changedFiles().isEmpty() ? "none" : String.join(", ", changeSet.changedFiles())) + "\n"
                + "verifierStatus: " + blank(changeSet.verifierStatus()) + "\n"
                + "commitHash: " + blank(changeSet.commitHash()) + "\n"
                + "rollbackStatus: " + blank(changeSet.rollbackStatus()) + "\n"
                + "updatedAt: " + changeSet.updatedAt() + "\n\n"
                + changeSet.diffSummary();
    }

    public String renderDiff(GitChangeSet changeSet, int maxChars) {
        if (changeSet == null) {
            return "No ChangeSet found.";
        }
        String patch = changeSet.diffPatch();
        String rendered = patch.length() <= maxChars ? patch : patch.substring(0, Math.max(0, maxChars)) + "\n[truncated]";
        return "changeset diff " + changeSet.id() + "\n\n"
                + changeSet.diffSummary() + "\n\n"
                + rendered;
    }

    public String renderCommitMessage(GitChangeSet changeSet) {
        if (changeSet == null) {
            return "No ChangeSet found.";
        }
        return changeSet.commitMessage().isBlank()
                ? "No commit message generated."
                : changeSet.commitMessage();
    }

    public String renderRollback(GitChangeSet changeSet) {
        if (changeSet == null) {
            return "No ChangeSet found.";
        }
        if (changeSet.rollbackCommands().isEmpty()) {
            return "No rollback commands recorded.";
        }
        return "rollback commands for " + changeSet.id() + "\n"
                + String.join("\n", changeSet.rollbackCommands());
    }

    public String summaryLine(GitChangeSet changeSet) {
        if (changeSet == null) {
            return "";
        }
        return changeSet.id()
                + " status=" + changeSet.status()
                + " files=" + changeSet.changedFiles().size()
                + (!changeSet.verifierStatus().isBlank() ? " verifier=" + changeSet.verifierStatus() : "")
                + (!changeSet.workspaceSessionId().isBlank() ? " workspaceSession=" + changeSet.workspaceSessionId() : "")
                + (!changeSet.commitHash().isBlank() ? " commitHash=" + changeSet.commitHash() : "")
                + (!changeSet.rollbackStatus().isBlank() ? " rollbackStatus=" + changeSet.rollbackStatus() : "")
                + " summary=" + changeSet.diffSummary().replace("\n", " ");
    }

    private String blank(String value) {
        return value != null && !value.isBlank() ? value : "(none)";
    }
}
