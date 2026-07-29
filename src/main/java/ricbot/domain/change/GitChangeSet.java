package ricbot.domain.change;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GitChangeSet(
        String id,
        String sessionId,
        String teamSessionId,
        String taskId,
        String baseCommit,
        List<String> changedFiles,
        String diffSummary,
        String diffPatch,
        List<String> diffReviews,
        List<String> suggestedTests,
        List<String> executedTests,
        String verifierStatus,
        List<String> verifierReasons,
        String taskSummary,
        String commitMessage,
        List<String> rollbackCommands,
        String commitHash,
        String rollbackStatus,
        String workspaceSessionId,
        String workspacePath,
        GitChangeSetStatus status,
        String createdAt,
        String updatedAt,
        String verificationReportId,
        String verificationReportDigest,
        String verifiedDiffDigest,
        String verificationArtifact
) {
    public GitChangeSet {
        id = id != null && !id.isBlank() ? id : newId();
        sessionId = clean(sessionId);
        teamSessionId = clean(teamSessionId);
        taskId = clean(taskId);
        baseCommit = clean(baseCommit);
        changedFiles = copy(changedFiles);
        diffSummary = clean(diffSummary);
        diffPatch = diffPatch != null ? diffPatch : "";
        diffReviews = copy(diffReviews);
        suggestedTests = copy(suggestedTests);
        executedTests = copy(executedTests);
        verifierStatus = clean(verifierStatus);
        verifierReasons = copy(verifierReasons);
        taskSummary = clean(taskSummary);
        commitMessage = clean(commitMessage);
        rollbackCommands = copy(rollbackCommands);
        commitHash = clean(commitHash);
        rollbackStatus = clean(rollbackStatus);
        workspaceSessionId = clean(workspaceSessionId);
        workspacePath = clean(workspacePath);
        status = status != null ? status : GitChangeSetStatus.DRAFT;
        String now = Instant.now().toString();
        createdAt = createdAt != null && !createdAt.isBlank() ? createdAt : now;
        updatedAt = updatedAt != null && !updatedAt.isBlank() ? updatedAt : now;
        verificationReportId = clean(verificationReportId);
        verificationReportDigest = clean(verificationReportDigest);
        verifiedDiffDigest = clean(verifiedDiffDigest);
        verificationArtifact = clean(verificationArtifact);
    }

    public GitChangeSet(String id, String sessionId, String teamSessionId, String taskId, String baseCommit,
                        List<String> changedFiles, String diffSummary, String diffPatch, List<String> diffReviews,
                        List<String> suggestedTests, List<String> executedTests, String verifierStatus,
                        List<String> verifierReasons, String taskSummary, String commitMessage,
                        List<String> rollbackCommands, String commitHash, String rollbackStatus,
                        String workspaceSessionId, String workspacePath, GitChangeSetStatus status,
                        String createdAt, String updatedAt) {
        this(id, sessionId, teamSessionId, taskId, baseCommit, changedFiles, diffSummary, diffPatch, diffReviews,
                suggestedTests, executedTests, verifierStatus, verifierReasons, taskSummary, commitMessage,
                rollbackCommands, commitHash, rollbackStatus, workspaceSessionId, workspacePath, status,
                createdAt, updatedAt, "", "", "", "");
    }

    public GitChangeSet(
            String id,
            String sessionId,
            String teamSessionId,
            String taskId,
            String baseCommit,
            List<String> changedFiles,
            String diffSummary,
            String diffPatch,
            List<String> diffReviews,
            List<String> suggestedTests,
            List<String> executedTests,
            String verifierStatus,
            List<String> verifierReasons,
            String taskSummary,
            String commitMessage,
            List<String> rollbackCommands,
            String commitHash,
            String rollbackStatus,
            GitChangeSetStatus status,
            String createdAt,
            String updatedAt
    ) {
        this(id, sessionId, teamSessionId, taskId, baseCommit, changedFiles, diffSummary, diffPatch,
                diffReviews, suggestedTests, executedTests, verifierStatus, verifierReasons, taskSummary,
                commitMessage, rollbackCommands, commitHash, rollbackStatus, "", "", status, createdAt, updatedAt);
    }

    public GitChangeSet withCommitMessage(String nextCommitMessage) {
        return new GitChangeSet(id, sessionId, teamSessionId, taskId, baseCommit, changedFiles, diffSummary, diffPatch,
                diffReviews, suggestedTests, executedTests, verifierStatus, verifierReasons, taskSummary,
                nextCommitMessage, rollbackCommands, commitHash, rollbackStatus, workspaceSessionId, workspacePath,
                status, createdAt, Instant.now().toString(), verificationReportId, verificationReportDigest,
                verifiedDiffDigest, verificationArtifact);
    }

    public GitChangeSet withStatus(GitChangeSetStatus nextStatus) {
        return new GitChangeSet(id, sessionId, teamSessionId, taskId, baseCommit, changedFiles, diffSummary, diffPatch,
                diffReviews, suggestedTests, executedTests, verifierStatus, verifierReasons, taskSummary,
                commitMessage, rollbackCommands, commitHash, rollbackStatus, workspaceSessionId, workspacePath,
                nextStatus, createdAt, Instant.now().toString(), verificationReportId, verificationReportDigest,
                verifiedDiffDigest, verificationArtifact);
    }

    public GitChangeSet withVerifier(String status, List<String> reasons) {
        GitChangeSetStatus nextStatus = "PASS".equalsIgnoreCase(status)
                && this.status != GitChangeSetStatus.APPROVED
                && this.status != GitChangeSetStatus.COMMITTED
                && this.status != GitChangeSetStatus.ROLLED_BACK
                ? GitChangeSetStatus.VERIFIED
                : this.status;
        return new GitChangeSet(id, sessionId, teamSessionId, taskId, baseCommit, changedFiles, diffSummary, diffPatch,
                diffReviews, suggestedTests, executedTests, status, reasons, taskSummary,
                commitMessage, rollbackCommands, commitHash, rollbackStatus, workspaceSessionId, workspacePath,
                nextStatus, createdAt, Instant.now().toString(), "", "", "", "");
    }

    public GitChangeSet withVerificationReport(String nextVerifierStatus, List<String> reasons, String reportId,
                                               String reportDigest, String diffDigest, String artifact) {
        GitChangeSetStatus nextStatus = "PASS".equalsIgnoreCase(nextVerifierStatus)
                && status != GitChangeSetStatus.APPROVED && status != GitChangeSetStatus.COMMITTED
                && status != GitChangeSetStatus.ROLLED_BACK ? GitChangeSetStatus.VERIFIED : status;
        return new GitChangeSet(id, sessionId, teamSessionId, taskId, baseCommit, changedFiles, diffSummary, diffPatch,
                diffReviews, suggestedTests, executedTests, nextVerifierStatus, reasons, taskSummary,
                commitMessage, rollbackCommands, commitHash, rollbackStatus, workspaceSessionId, workspacePath,
                nextStatus, createdAt, Instant.now().toString(), reportId, reportDigest, diffDigest, artifact);
    }

    public GitChangeSet withCommitResult(String nextCommitHash) {
        return new GitChangeSet(id, sessionId, teamSessionId, taskId, baseCommit, changedFiles, diffSummary, diffPatch,
                diffReviews, suggestedTests, executedTests, verifierStatus, verifierReasons, taskSummary,
                commitMessage, rollbackCommands, nextCommitHash, rollbackStatus, workspaceSessionId, workspacePath,
                GitChangeSetStatus.COMMITTED, createdAt, Instant.now().toString(), verificationReportId,
                verificationReportDigest, verifiedDiffDigest, verificationArtifact);
    }

    public GitChangeSet withRollbackResult(String nextRollbackStatus) {
        return new GitChangeSet(id, sessionId, teamSessionId, taskId, baseCommit, changedFiles, diffSummary, diffPatch,
                diffReviews, suggestedTests, executedTests, verifierStatus, verifierReasons, taskSummary,
                commitMessage, rollbackCommands, commitHash, nextRollbackStatus, workspaceSessionId, workspacePath,
                GitChangeSetStatus.ROLLED_BACK, createdAt, Instant.now().toString(), verificationReportId,
                verificationReportDigest, verifiedDiffDigest, verificationArtifact);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("sessionId", sessionId);
        out.put("teamSessionId", teamSessionId);
        out.put("taskId", taskId);
        out.put("baseCommit", baseCommit);
        out.put("changedFiles", changedFiles);
        out.put("diffSummary", diffSummary);
        out.put("diffPatch", diffPatch);
        out.put("diffReviews", diffReviews);
        out.put("suggestedTests", suggestedTests);
        out.put("executedTests", executedTests);
        out.put("verifierStatus", verifierStatus);
        out.put("verifierReasons", verifierReasons);
        out.put("taskSummary", taskSummary);
        out.put("commitMessage", commitMessage);
        out.put("rollbackCommands", rollbackCommands);
        out.put("commitHash", commitHash);
        out.put("rollbackStatus", rollbackStatus);
        out.put("workspaceSessionId", workspaceSessionId);
        out.put("workspacePath", workspacePath);
        out.put("status", status.name());
        out.put("createdAt", createdAt);
        out.put("updatedAt", updatedAt);
        out.put("verificationReportId", verificationReportId);
        out.put("verificationReportDigest", verificationReportDigest);
        out.put("verifiedDiffDigest", verifiedDiffDigest);
        out.put("verificationArtifact", verificationArtifact);
        return out;
    }

    public static GitChangeSet fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new GitChangeSet(
                string(raw.get("id")),
                string(raw.get("sessionId")),
                string(raw.get("teamSessionId")),
                string(raw.get("taskId")),
                string(raw.get("baseCommit")),
                stringList(raw.get("changedFiles")),
                string(raw.get("diffSummary")),
                string(raw.get("diffPatch")),
                stringList(raw.get("diffReviews")),
                stringList(raw.get("suggestedTests")),
                stringList(raw.get("executedTests")),
                string(raw.get("verifierStatus")),
                stringList(raw.get("verifierReasons")),
                string(raw.get("taskSummary")),
                string(raw.get("commitMessage")),
                stringList(raw.get("rollbackCommands")),
                string(raw.get("commitHash")),
                string(raw.get("rollbackStatus")),
                string(raw.get("workspaceSessionId")),
                string(raw.get("workspacePath")),
                parseStatus(raw.get("status")),
                string(raw.get("createdAt")),
                string(raw.get("updatedAt")),
                string(raw.get("verificationReportId")),
                string(raw.get("verificationReportDigest")),
                string(raw.get("verifiedDiffDigest")),
                string(raw.get("verificationArtifact"))
        );
    }

    private static GitChangeSetStatus parseStatus(Object raw) {
        try {
            return raw != null ? GitChangeSetStatus.valueOf(String.valueOf(raw).toUpperCase(java.util.Locale.ROOT)) : GitChangeSetStatus.DRAFT;
        } catch (Exception e) {
            return GitChangeSetStatus.DRAFT;
        }
    }

    private static List<String> copy(List<String> values) {
        return values != null ? values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList() : List.of();
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String newId() {
        return "changeset_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
