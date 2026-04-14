package ricbot.infra.git;


import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class GitStore {

    public record CommitInfo(String sha, String message, String timestamp) {
        public String format(String diff) {
            String header = "## " + firstLine(message) + "\n`" + sha + "` — " + timestamp + "\n";
            if (diff != null && !diff.isBlank()) {
                return header + "\n```diff\n" + diff + "\n```";
            }
            return header + "\n(no file changes)";
        }

        private static String firstLine(String s) {
            if (s == null || s.isBlank()) return "";
            int idx = s.indexOf('\n');
            return idx >= 0 ? s.substring(0, idx) : s;
        }
    }

    private final Path workspace;
    private final List<String> trackedFiles;

    public GitStore(Path workspace, List<String> trackedFiles) {
        this.workspace = workspace;
        this.trackedFiles = trackedFiles != null ? trackedFiles : List.of();
    }

    public boolean isInitialized() {
        return Files.isDirectory(workspace.resolve(".git"));
    }

    public boolean init() {
        if (isInitialized()) {
            return false;
        }
        try {
            Git git = Git.init().setDirectory(workspace.toFile()).call();

            Path gitignore = workspace.resolve(".gitignore");
            Files.writeString(gitignore, buildGitignore());

            for (String rel : trackedFiles) {
                Path p = workspace.resolve(rel);
                Files.createDirectories(p.getParent());
                if (!Files.exists(p)) {
                    Files.writeString(p, "");
                }
            }

            git.add().addFilepattern(".gitignore").call();
            for (String rel : trackedFiles) {
                git.add().addFilepattern(rel).call();
            }

            git.commit()
                    .setMessage("init: nanobot memory store")
                    .setAuthor("oldricbot", "nanobot@dream")
                    .call();

            git.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String autoCommit(String message) {
        if (!isInitialized()) {
            return null;
        }
        try (Git git = Git.open(workspace.toFile())) {
            Status st = git.status().call();
            if (st.isClean()) {
                return null;
            }

            for (String rel : trackedFiles) {
                git.add().addFilepattern(rel).call();
            }

            RevCommit commit = git.commit()
                    .setMessage(message)
                    .setAuthor("oldricbot", "nanobot@dream")
                    .call();

            return commit.getId().name().substring(0, 8);
        } catch (Exception e) {
            return null;
        }
    }

    public List<CommitInfo> log(int maxEntries) {
        List<CommitInfo> entries = new ArrayList<>();
        if (!isInitialized()) {
            return entries;
        }

        try (Git git = Git.open(workspace.toFile())) {
            Iterable<RevCommit> commits = git.log().setMaxCount(maxEntries).call();
            for (RevCommit commit : commits) {
                String ts = Instant.ofEpochSecond(commit.getCommitTime())
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                entries.add(new CommitInfo(
                        commit.getId().name().substring(0, 8),
                        commit.getFullMessage().trim(),
                        ts
                ));
            }
        } catch (Exception ignored) {
        }
        return entries;
    }

    public String diffCommits(String sha1, String sha2) {
        // 当前先保留占位实现
        // 你后面如果要我，我可以把 JGit diff 也补完整
        return "";
    }

    public CommitInfo findCommit(String shortSha, int maxEntries) {
        for (CommitInfo c : log(maxEntries)) {
            if (c.sha().startsWith(shortSha)) {
                return c;
            }
        }
        return null;
    }

    public ShowCommitDiffResult showCommitDiff(String shortSha, int maxEntries) {
        List<CommitInfo> commits = log(maxEntries);
        for (int i = 0; i < commits.size(); i++) {
            CommitInfo c = commits.get(i);
            if (c.sha().startsWith(shortSha)) {
                String diff = i + 1 < commits.size() ? diffCommits(commits.get(i + 1).sha(), c.sha()) : "";
                return new ShowCommitDiffResult(c, diff);
            }
        }
        return null;
    }

    public String revert(String commit) {
        // 原 Python 后半段被截断，这里先保留接口
        return null;
    }

    private String buildGitignore() {
        List<String> dirs = new ArrayList<>();
        for (String f : trackedFiles) {
            Path parent = Path.of(f).getParent();
            if (parent != null && !parent.toString().equals(".")) {
                dirs.add(parent.toString().replace("\\", "/"));
            }
        }

        StringBuilder sb = new StringBuilder("/*\n");
        dirs.stream().distinct().sorted().forEach(d -> sb.append("!").append(d).append("/\n"));
        for (String f : trackedFiles) {
            sb.append("!").append(f.replace("\\", "/")).append("\n");
        }
        sb.append("!.gitignore\n");
        return sb.toString();
    }

    public record ShowCommitDiffResult(CommitInfo commit, String diff) {}
}