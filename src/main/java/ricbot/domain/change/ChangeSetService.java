package ricbot.domain.change;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.verification.VerificationReport;
import ricbot.domain.workspace.RuntimeArtifactFilter;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public class ChangeSetService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final Path workspace;
    private final SqliteRuntimeStore runtime;

    public ChangeSetService(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.runtime = ricbot.app.bootstrap.RuntimeStoreRegistry.shared(this.workspace);
    }

    public GitChangeSet createFromWorkingTree(String sessionId, String teamSessionId, String taskId) {
        return createFromGitWorkspace(workspace, sessionId, teamSessionId, taskId, "", "");
    }

    public GitChangeSet createFromWorkspace(String workspaceSessionId, Path workspacePath, String sessionId, String teamSessionId, String taskId) {
        if (workspaceSessionId == null || workspaceSessionId.isBlank()) {
            throw new IllegalArgumentException("workspaceSessionId is required");
        }
        Path target = safeWorktreePath(workspacePath);
        return createFromGitWorkspace(target, sessionId, teamSessionId, taskId, workspaceSessionId, target.toString());
    }

    private GitChangeSet createFromGitWorkspace(Path gitWorkspace, String sessionId, String teamSessionId, String taskId, String workspaceSessionId, String workspacePath) {
        ensureGitRepository(gitWorkspace);
        List<StatusRow> statusRows = statusRows(gitWorkspace);
        if (statusRows.isEmpty()) {
            throw new IllegalStateException("no user changes found");
        }
        String baseCommit = git(gitWorkspace, "rev-parse", "HEAD").trim();
        List<String> changedFiles = statusRows.stream().map(StatusRow::path).distinct().toList();
        String diffPatch = buildDiffPatch(gitWorkspace, statusRows);
        String diffSummary = summarize(statusRows, diffPatch);
        List<String> rollbackCommands = rollbackCommands(statusRows);
        GitChangeSet changeSet = new GitChangeSet(
                null,
                sessionId,
                teamSessionId,
                taskId,
                baseCommit,
                changedFiles,
                diffSummary,
                diffPatch,
                List.of(),
                List.of(),
                List.of(),
                "",
                List.of(),
                "",
                "",
                rollbackCommands,
                "",
                "",
                workspaceSessionId,
                workspacePath,
                GitChangeSetStatus.DRAFT,
                null,
                null
        );
        changeSet = changeSet.withCommitMessage(generateCommitMessage(changeSet));
        save(changeSet);
        VerificationReport matching = matchingReport(changeSet);
        if (matching != null) changeSet = attachVerificationReport(changeSet.id(), matching);
        return changeSet;
    }

    public boolean hasWorkingTreeChanges() {
        try {
            ensureGitRepository(workspace);
            return !statusRows(workspace).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public String generateCommitMessage(GitChangeSet changeSet) {
        if (changeSet == null) {
            return "";
        }
        String scope = commonScope(changeSet.changedFiles());
        String subject = changeSet.changedFiles().isEmpty()
                ? "Update project files"
                : "Update " + scope;
        StringBuilder sb = new StringBuilder();
        sb.append(subject).append("\n\n");
        sb.append(changeSet.diffSummary()).append("\n");
        if (!changeSet.suggestedTests().isEmpty()) {
            sb.append("\nSuggested tests:\n");
            for (String test : changeSet.suggestedTests()) {
                sb.append("- ").append(test).append("\n");
            }
        }
        return sb.toString().trim();
    }

    public GitChangeSet attachVerifierDecision(String changeSetId, VerificationReport.Status status, List<String> reasons) {
        GitChangeSet current = require(changeSetId);
        GitChangeSet next = current.withVerifier(
                status != null ? status.name() : "",
                reasons != null ? reasons : List.of()
        );
        save(next);
        return next;
    }

    public GitChangeSet attachVerificationReport(String changeSetId, VerificationReport report) {
        if (report == null) throw new IllegalArgumentException("verification report is required");
        GitChangeSet current = require(changeSetId);
        String currentDigest = currentDiffDigest(current);
        if (!currentDigest.equals(report.diffDigest())) {
            throw new IllegalStateException("verification report diff does not match changeset workspace");
        }
        String digest;
        try { digest = sha256(MAPPER.writeValueAsBytes(report)); }
        catch (Exception e) { throw new IllegalStateException("cannot digest verification report", e); }
        List<String> reasons = report.checks().stream()
                .filter(check -> check.status() != ricbot.domain.verification.VerificationCheckResult.Status.PASS)
                .map(check -> check.stage() + ":" + check.checkId() + ":" + check.status()).toList();
        GitChangeSet next = current.withVerificationReport(report.status().name(), reasons, report.reportId(),
                digest, report.diffDigest(), report.artifactDirectory());
        save(next);
        return next;
    }

    public GitChangeSet markApproved(String changeSetId) {
        GitChangeSet next = require(changeSetId).withStatus(GitChangeSetStatus.APPROVED);
        save(next);
        return next;
    }

    public GitChangeSet commit(String changeSetId, String commitMessage) {
        throw new IllegalStateException("changeset commit requires a claimed graph approval");
    }

    public GitChangeSet commit(String changeSetId, String commitMessage, ChangeSetActionAuthorization authorization) {
        GitChangeSet current = require(changeSetId);
        Path targetWorkspace = commandWorkspace(current);
        ensureGitRepository(targetWorkspace);
        String message = commitMessage != null && !commitMessage.isBlank()
                ? commitMessage.trim()
                : !current.commitMessage().isBlank()
                ? current.commitMessage()
                : generateCommitMessage(current);
        if (authorization == null) throw new IllegalStateException("changeset commit requires approval authorization");
        authorization.require(PendingChangeAction.ActionType.COMMIT, changeSetId, message);
        if (current.status() == GitChangeSetStatus.COMMITTED) return current;
        requireCommitEligible(current);
        if (current.changedFiles().isEmpty()) {
            throw new IllegalStateException("changeset has no changed files: " + changeSetId);
        }
        if (!changedFilesStillPresent(current)) {
            throw new IllegalStateException("working tree no longer contains all changeset files: " + changeSetId);
        }
        for (String path : current.changedFiles()) {
            git(targetWorkspace, "add", "--", path);
        }
        git(targetWorkspace, "commit", "-m", message, "-m",
                "Ricbot-Approval: " + authorization.requestId() + "\nRicbot-ChangeSet: " + changeSetId);
        String commitHash = git(targetWorkspace, "rev-parse", "HEAD").trim();
        GitChangeSet committed = current.withCommitMessage(message).withCommitResult(commitHash);
        save(committed);
        return committed;
    }

    /** Enforces commit safety for every adapter, not only the CLI. */
    public void requireCommitEligible(GitChangeSet changeSet) {
        if (changeSet == null) {
            throw new IllegalArgumentException("changeset is required");
        }
        if (changeSet.status() != GitChangeSetStatus.APPROVED) {
            throw new IllegalStateException("changeset must be APPROVED before commit: " + changeSet.id());
        }
        if (!"PASS".equalsIgnoreCase(changeSet.verifierStatus())) {
            String status = changeSet.verifierStatus().isBlank() ? "(none)" : changeSet.verifierStatus();
            throw new IllegalStateException("changeset must have verifierStatus=PASS before commit: "
                    + changeSet.id() + " (current=" + status + ")");
        }
        if (changeSet.verificationReportId().isBlank() || changeSet.verificationReportDigest().isBlank()
                || changeSet.verifiedDiffDigest().isBlank()) {
            throw new IllegalStateException("changeset requires a structured verification report before commit: "
                    + changeSet.id());
        }
        String currentDigest = currentDiffDigest(changeSet);
        if (!currentDigest.equals(changeSet.verifiedDiffDigest())) {
            throw new IllegalStateException("changeset diff changed after verification: " + changeSet.id());
        }
    }

    public String currentDiffDigest(GitChangeSet changeSet) {
        Path target = commandWorkspace(changeSet);
        String diff = git(target, "diff", "--binary", "HEAD");
        return sha256(diff.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private VerificationReport matchingReport(GitChangeSet changeSet) {
        String digest = currentDiffDigest(changeSet);
        return runtime.verificationReports().stream()
                .filter(report -> report.status() == VerificationReport.Status.PASS)
                .filter(report -> digest.equals(report.diffDigest()))
                .max(Comparator.comparing(VerificationReport::createdAt)).orElse(null);
    }

    public GitChangeSet rollback(String changeSetId) {
        throw new IllegalStateException("changeset rollback requires a claimed graph approval");
    }

    public GitChangeSet rollback(String changeSetId, ChangeSetActionAuthorization authorization) {
        GitChangeSet current = require(changeSetId);
        if (authorization == null) throw new IllegalStateException("changeset rollback requires approval authorization");
        authorization.require(PendingChangeAction.ActionType.ROLLBACK, changeSetId, "");
        Path targetWorkspace = commandWorkspace(current);
        ensureGitRepository(targetWorkspace);
        if (current.status() == GitChangeSetStatus.ROLLED_BACK) return current;
        if (current.rollbackCommands().isEmpty()) {
            throw new IllegalStateException("changeset has no rollback commands: " + changeSetId);
        }
        List<String> executed = new ArrayList<>();
        for (String command : current.rollbackCommands()) {
            executeRollbackCommand(targetWorkspace, command);
            executed.add(command);
        }
        GitChangeSet rolledBack = current.withRollbackResult("executed " + executed.size()
                + " rollback command(s): " + String.join("; ", executed));
        save(rolledBack);
        return rolledBack;
    }

    public GitChangeSet reconcileCommit(String changeSetId, String requestId) {
        GitChangeSet current = require(changeSetId);
        if (current.status() == GitChangeSetStatus.COMMITTED) return current;
        Path targetWorkspace = commandWorkspace(current);
        String head = git(targetWorkspace, "rev-parse", "HEAD").trim();
        String body = git(targetWorkspace, "log", "-1", "--format=%B");
        if (body.contains("Ricbot-Approval: " + requestId)
                && body.contains("Ricbot-ChangeSet: " + current.id())) {
            GitChangeSet recovered = current.withCommitResult(head);
            save(recovered);
            return recovered;
        }
        return null;
    }

    public GitChangeSet reconcileRollback(String changeSetId) {
        GitChangeSet current = require(changeSetId);
        if (current.status() == GitChangeSetStatus.ROLLED_BACK) return current;
        Path targetWorkspace = commandWorkspace(current);
        if (statusRows(targetWorkspace).isEmpty()) {
            GitChangeSet recovered = current.withRollbackResult("rollback recovered after restart");
            save(recovered);
            return recovered;
        }
        return null;
    }

    public boolean changedFilesStillPresent(GitChangeSet changeSet) {
        if (changeSet == null || changeSet.changedFiles().isEmpty()) {
            return false;
        }
        try {
            Path targetWorkspace = commandWorkspace(changeSet);
            ensureGitRepository(targetWorkspace);
            List<String> statusPaths = statusRows(targetWorkspace).stream().map(StatusRow::path).distinct().toList();
            return statusPaths.containsAll(changeSet.changedFiles());
        } catch (Exception e) {
            return false;
        }
    }

    public String render(String changeSetId) {
        return new ChangeSetRenderer().renderStatus(require(changeSetId));
    }

    public GitChangeSet latest() {
        return list().stream().findFirst().orElse(null);
    }

    public List<GitChangeSet> list() {
        return runtime.changeSets();
    }

    public GitChangeSet load(String changeSetId) {
        String id = changeSetId != null ? changeSetId.trim() : "";
        if (id.isBlank()) {
            return null;
        }
        return runtime.changeSet(id).orElse(null);
    }

    public Path changeSetDir(String changeSetId) {
        return runtime.database();
    }

    private GitChangeSet require(String changeSetId) {
        GitChangeSet changeSet = load(changeSetId);
        if (changeSet == null) {
            throw new IllegalArgumentException("changeset not found: " + changeSetId);
        }
        return changeSet;
    }

    private void save(GitChangeSet changeSet) {
        runtime.saveChangeSet(changeSet);
    }

    private void ensureGitRepository(Path directory) {
        try {
            git(directory, "rev-parse", "--is-inside-work-tree");
        } catch (Exception e) {
            throw new IllegalStateException("workspace is not a git repository: " + directory);
        }
    }

    private List<StatusRow> statusRows(Path directory) {
        String raw = git(directory, "status", "--porcelain", "-uall");
        List<StatusRow> out = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            if (line == null || line.isBlank() || line.length() < 4) {
                continue;
            }
            String code = line.substring(0, 2);
            String path = line.substring(3).trim();
            int rename = path.indexOf(" -> ");
            if (rename >= 0) {
                path = path.substring(rename + 4).trim();
            }
            if (!path.isBlank() && !RuntimeArtifactFilter.isRuntimeArtifact(path)) {
                out.add(new StatusRow(code, path));
            }
        }
        return out;
    }

    private String buildDiffPatch(Path directory, List<StatusRow> rows) {
        StringBuilder sb = new StringBuilder();
        List<String> trackedPaths = rows.stream()
                .filter(row -> !row.untracked())
                .map(StatusRow::path)
                .distinct()
                .toList();
        if (!trackedPaths.isEmpty()) {
            List<String> args = new ArrayList<>();
            args.add("diff");
            args.add("--");
            args.addAll(trackedPaths);
            String trackedDiff = git(directory, args.toArray(String[]::new));
            if (!trackedDiff.isBlank()) {
                sb.append(trackedDiff.stripTrailing()).append("\n");
            }
        }
        for (StatusRow row : rows) {
            if (!row.untracked()) {
                continue;
            }
            Path file = directory.resolve(row.path()).normalize();
            if (!file.startsWith(directory)) {
                sb.append("+[untracked file path escapes workspace]\n");
                continue;
            }
            sb.append("\ndiff --git a/").append(row.path()).append(" b/").append(row.path()).append("\n");
            sb.append("new file mode 100644\n--- /dev/null\n+++ b/").append(row.path()).append("\n");
            try {
                if (Files.isRegularFile(file)) {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8).stream().limit(200).toList()) {
                        sb.append("+").append(line).append("\n");
                    }
                } else {
                    sb.append("+[untracked non-regular file]\n");
                }
            } catch (Exception e) {
                sb.append("+[untracked file content omitted]\n");
            }
        }
        return sb.toString();
    }

    private String summarize(List<StatusRow> rows, String diffPatch) {
        int additions = 0;
        int deletions = 0;
        for (String line : diffPatch.split("\\R")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }
        long untracked = rows.stream().filter(StatusRow::untracked).count();
        long modified = rows.stream().filter(row -> !row.untracked()).count();
        return "Changed files: " + rows.size()
                + " (modified=" + modified + ", new=" + untracked + ")\n"
                + "Lines: +" + additions + "/-" + deletions + "\n"
                + "Files: " + String.join(", ", rows.stream().map(StatusRow::path).toList());
    }

    private List<String> rollbackCommands(List<StatusRow> rows) {
        List<String> out = new ArrayList<>();
        for (StatusRow row : rows) {
            if (row.untracked()) {
                out.add("rm " + shellPath(row.path()));
            } else {
                out.add("git restore -- " + shellPath(row.path()));
            }
        }
        return out;
    }

    private String commonScope(List<String> files) {
        if (files == null || files.isEmpty()) {
            return "working tree";
        }
        String first = files.get(0);
        if (files.size() == 1) {
            int slash = first.lastIndexOf('/');
            return slash >= 0 ? first.substring(slash + 1) : first;
        }
        if (files.stream().allMatch(path -> path.contains("/domain/change/"))) {
            return "changeset workflow";
        }
        return files.size() + " files";
    }

    private String shellPath(String path) {
        if (path.matches("[A-Za-z0-9_./:-]+")) {
            return path;
        }
        return "'" + path.replace("'", "'\"'\"'") + "'";
    }

    private void executeRollbackCommand(Path targetWorkspace, String command) {
        String value = command != null ? command.trim() : "";
        if (value.startsWith("git restore -- ")) {
            git(targetWorkspace, "restore", "--", unquoteShellPath(value.substring("git restore -- ".length()).trim()));
            return;
        }
        if (value.startsWith("git checkout -- ")) {
            git(targetWorkspace, "checkout", "--", unquoteShellPath(value.substring("git checkout -- ".length()).trim()));
            return;
        }
        if (value.startsWith("rm ")) {
            Path path = targetWorkspace.resolve(unquoteShellPath(value.substring("rm ".length()).trim())).normalize();
            if (!path.startsWith(targetWorkspace)) {
                throw new IllegalStateException("rollback path escapes workspace: " + command);
            }
            try {
                Files.deleteIfExists(path);
            } catch (Exception e) {
                throw new IllegalStateException("rollback rm failed: " + command, e);
            }
            return;
        }
        throw new IllegalStateException("unsupported rollback command: " + command);
    }

    private String unquoteShellPath(String raw) {
        String value = raw != null ? raw.trim() : "";
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("'\"'\"'", "'");
        }
        return value;
    }

    private Path commandWorkspace(GitChangeSet changeSet) {
        if (changeSet == null || changeSet.workspacePath().isBlank()) {
            return workspace;
        }
        return safeWorktreePath(Path.of(changeSet.workspacePath()));
    }

    private Path safeWorktreePath(Path rawPath) {
        if (rawPath == null) {
            throw new IllegalArgumentException("workspacePath is required");
        }
        Path path = rawPath.toAbsolutePath().normalize();
        if (path.equals(workspace)) {
            return workspace;
        }
        Path root = workspace.resolve(".workspaces").normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalStateException("workspacePath must stay under " + root + ": " + path);
        }
        return path;
    }

    private String git(Path directory, String... args) {
        Path dir = directory.toAbsolutePath().normalize();
        if (!dir.equals(workspace) && !dir.startsWith(workspace.resolve(".workspaces").normalize())) {
            throw new IllegalStateException("git command directory escapes workspace boundary: " + dir);
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        try {
            Process process = pb.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException(stderr.isBlank() ? stdout : stderr);
            }
            return stdout;
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException("git command failed: " + String.join(" ", command), e);
        }
    }

    private record StatusRow(String code, String path) {
        boolean untracked() {
            return code.startsWith("??") || code.startsWith("A ") || code.startsWith(" A");
        }
    }
}
