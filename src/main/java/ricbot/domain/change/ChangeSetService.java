package ricbot.domain.change;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.team.VerificationResult;
import ricbot.domain.workspace.RuntimeArtifactFilter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ChangeSetService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path workspace;
    private final Path root;

    public ChangeSetService(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.root = this.workspace.resolve(".changesets");
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

    public GitChangeSet attachVerifierResult(String changeSetId, VerificationResult result) {
        GitChangeSet current = require(changeSetId);
        GitChangeSet next = current.withVerifier(
                result != null ? result.status().name() : "",
                result != null ? result.reasons() : List.of()
        );
        save(next);
        return next;
    }

    public GitChangeSet markApproved(String changeSetId) {
        GitChangeSet next = require(changeSetId).withStatus(GitChangeSetStatus.APPROVED);
        save(next);
        return next;
    }

    public GitChangeSet commit(String changeSetId, String commitMessage) {
        GitChangeSet current = require(changeSetId);
        Path targetWorkspace = commandWorkspace(current);
        ensureGitRepository(targetWorkspace);
        if (current.changedFiles().isEmpty()) {
            throw new IllegalStateException("changeset has no changed files: " + changeSetId);
        }
        if (!changedFilesStillPresent(current)) {
            throw new IllegalStateException("working tree no longer contains all changeset files: " + changeSetId);
        }
        String message = commitMessage != null && !commitMessage.isBlank()
                ? commitMessage.trim()
                : !current.commitMessage().isBlank()
                ? current.commitMessage()
                : generateCommitMessage(current);
        for (String path : current.changedFiles()) {
            git(targetWorkspace, "add", "--", path);
        }
        git(targetWorkspace, "commit", "-m", message);
        String commitHash = git(targetWorkspace, "rev-parse", "HEAD").trim();
        GitChangeSet committed = current.withCommitMessage(message).withCommitResult(commitHash);
        save(committed);
        return committed;
    }

    public GitChangeSet rollback(String changeSetId) {
        GitChangeSet current = require(changeSetId);
        Path targetWorkspace = commandWorkspace(current);
        ensureGitRepository(targetWorkspace);
        if (current.rollbackCommands().isEmpty()) {
            throw new IllegalStateException("changeset has no rollback commands: " + changeSetId);
        }
        List<String> executed = new ArrayList<>();
        for (String command : current.rollbackCommands()) {
            executeRollbackCommand(targetWorkspace, command);
            executed.add(command);
        }
        GitChangeSet rolledBack = current.withRollbackResult("executed " + executed.size() + " rollback command(s): " + String.join("; ", executed));
        save(rolledBack);
        return rolledBack;
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
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<GitChangeSet> out = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                GitChangeSet changeSet = load(dir.getFileName().toString());
                if (changeSet != null) {
                    out.add(changeSet);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return out.stream()
                .sorted(Comparator.comparing(GitChangeSet::updatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .toList();
    }

    public GitChangeSet load(String changeSetId) {
        String id = changeSetId != null ? changeSetId.trim() : "";
        if (id.isBlank()) {
            return null;
        }
        Path file = root.resolve(id).resolve("changeset.json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return GitChangeSet.fromMap(MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE));
        } catch (Exception e) {
            return null;
        }
    }

    public Path changeSetDir(String changeSetId) {
        return root.resolve(changeSetId);
    }

    private GitChangeSet require(String changeSetId) {
        GitChangeSet changeSet = load(changeSetId);
        if (changeSet == null) {
            throw new IllegalArgumentException("changeset not found: " + changeSetId);
        }
        return changeSet;
    }

    private void save(GitChangeSet changeSet) {
        try {
            Path dir = root.resolve(changeSet.id());
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve("changeset.json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(changeSet.toMap()) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            Files.writeString(
                    dir.resolve("diff.patch"),
                    changeSet.diffPatch(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Exception e) {
            throw new RuntimeException("write changeset failed: " + changeSet.id(), e);
        }
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
        if (files.stream().allMatch(path -> path.contains("/domain/team/"))) {
            return "team workflow";
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

    private String git(String... args) {
        return git(workspace, args);
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
