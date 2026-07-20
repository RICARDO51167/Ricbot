package ricbot.infra.git;

// 导入 JGit 的核心 Git 操作类
import org.eclipse.jgit.api.Git;
// 导入 JGit 的状态检查类，用于判断工作区是否有变更
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
// 导入 JGit 的提交对象类，用于获取提交详情
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

// 导入 Java NIO 文件操作工具类
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
// 导入 Java NIO 路径类
import java.nio.file.Path;
// 导入时间即时点类
import java.time.Instant;
// 导入时区类
import java.time.ZoneId;
// 导入日期时间格式化类
import java.time.format.DateTimeFormatter;
// 导入动态数组列表类
import java.util.ArrayList;
import java.util.HashSet;
// 导入列表接口类
import java.util.List;
import java.util.Set;

/**
 * GitStore 类用于管理基于 Git 的存储操作。
 * 它封装了初始化仓库、自动提交、查看日志、差异比较和回滚等功能。
 */
public class GitStore {

    /**
     * CommitInfo 记录类，用于封装提交的简要信息。
     * @param sha 提交的短哈希值
     * @param message 提交消息
     * @param timestamp 提交时间的格式化字符串
     */
    public record CommitInfo(String sha, String message, String timestamp) {
        /**
         * 将提交信息格式化为 Markdown 字符串。
         * @param diff 可选的差异内容
         * @return 格式化后的字符串
         */
        public String format(String diff) {
            // 构建头部信息：包含提交消息的第一行、短哈希和时间戳
            String header = "## " + firstLine(message) + "\n`" + sha + "` — " + timestamp + "\n";
            // 如果存在差异内容且不为空，则附加差异代码块
            if (diff != null && !diff.isBlank()) {
                return header + "\n```diff\n" + diff + "\n```";
            }
            // 否则提示无文件变更
            return header + "\n(no file changes)";
        }

        /**
         * 获取字符串的第一行。
         * @param s 输入字符串
         * @return 第一行内容，如果为空则返回空字符串
         */
        private static String firstLine(String s) {
            // 如果字符串为空或仅包含空白字符，返回空字符串
            if (s == null || s.isBlank()) return "";
            // 查找第一个换行符的位置
            int idx = s.indexOf('\n');
            // 如果存在换行符，截取前半部分；否则返回整个字符串
            return idx >= 0 ? s.substring(0, idx) : s;
        }
    }

    // 工作区的路径
    private final Path workspace;
    // 需要跟踪的文件路径列表
    private final List<String> trackedFiles;

    /**
     * 构造函数，初始化 GitStore。
     * @param workspace 工作区路径
     * @param trackedFiles 需要跟踪的文件列表，如果为 null 则初始化为空列表
     */
    public GitStore(Path workspace, List<String> trackedFiles) {
        this.workspace = workspace;
        this.trackedFiles = trackedFiles != null ? trackedFiles : List.of();
    }

    /**
     * 检查 Git 仓库是否已初始化。
     * @return 如果 .git 目录存在则返回 true，否则返回 false
     */
    public boolean isInitialized() {
        return Files.isDirectory(workspace.resolve(".git"));
    }

    /**
     * 初始化 Git 仓库。
     * @return 如果初始化成功返回 true，如果已初始化或发生错误返回 false
     */
    public boolean init() {
        // 如果仓库已初始化，直接返回 false
        if (isInitialized()) {
            return false;
        }
        try {
            // 初始化 Git 仓库并指定目录
            Git git = Git.init().setDirectory(workspace.toFile()).call();

            // 创建 .gitignore 文件路径
            Path gitignore = workspace.resolve(".gitignore");
            // 写入 .gitignore 内容，忽略所有文件但保留跟踪文件
            Files.writeString(gitignore, buildGitignore());

            // 遍历所有需要跟踪的文件
            for (String rel : trackedFiles) {
                // 解析文件的完整路径
                Path p = workspace.resolve(rel);
                // 创建父目录，如果不存在的话
                Files.createDirectories(p.getParent());
                // 如果文件不存在，创建一个空文件
                if (!Files.exists(p)) {
                    Files.writeString(p, "");
                }
            }

            // 将 .gitignore 文件添加到暂存区
            git.add().addFilepattern(".gitignore").call();
            // 将所有需要跟踪的文件添加到暂存区
            for (String rel : trackedFiles) {
                git.add().addFilepattern(rel).call();
            }

            // 提交初始更改
            git.commit()
                    .setMessage("init: ricbot memory store") // 设置提交消息
                    .setAuthor("ricbot", "ricbot@dream") // 设置作者信息
                    .call();

            // 关闭 Git 对象
            git.close();
            // 返回初始化成功
            return true;
        } catch (Exception e) {
            // 如果发生异常，返回初始化失败
            return false;
        }
    }

    /**
     * 自动提交当前工作区的变更。
     * @param message 提交消息
     * @return 如果提交成功返回短哈希值，否则返回 null
     */
    public String autoCommit(String message) {
        // 如果仓库未初始化，返回 null
        if (!isInitialized()) {
            return null;
        }
        try (Git git = Git.open(workspace.toFile())) {
            // 获取当前工作区状态
            Status st = git.status().call();
            // 如果工作区没有变更，返回 null
            if (st.isClean()) {
                return null;
            }

            // 将所有需要跟踪的文件添加到暂存区
            for (String rel : trackedFiles) {
                git.add().addFilepattern(rel).call();
            }

            // 执行提交操作
            RevCommit commit = git.commit()
                    .setMessage(message) // 设置提交消息
                    .setAuthor("ricbot", "ricbot@dream") // 设置作者信息
                    .call();

            // 返回提交 ID 的前 8 位作为短哈希
            return commit.getId().name().substring(0, 8);
        } catch (Exception e) {
            // 如果发生异常，返回 null
            return null;
        }
    }

    /**
     * 获取提交日志。
     * @param maxEntries 最大返回条目数
     * @return 提交信息列表
     */
    public List<CommitInfo> log(int maxEntries) {
        // 初始化结果列表
        List<CommitInfo> entries = new ArrayList<>();
        // 如果仓库未初始化，返回空列表
        if (!isInitialized()) {
            return entries;
        }

        try (Git git = Git.open(workspace.toFile())) {
            // 获取指定数量的提交记录
            Iterable<RevCommit> commits = git.log().setMaxCount(maxEntries).call();
            // 遍历每个提交记录
            for (RevCommit commit : commits) {
                // 格式化提交时间
                String ts = Instant.ofEpochSecond(commit.getCommitTime())
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                // 创建 CommitInfo 对象并添加到列表
                entries.add(new CommitInfo(
                        commit.getId().name().substring(0, 8), // 短哈希
                        commit.getFullMessage().trim(), // 提交消息
                        ts // 格式化时间
                ));
            }
        } catch (Exception ignored) {
            // 忽略异常
        }
        // 返回提交信息列表
        return entries;
    }

    /**
     * 获取两个提交之间的差异。
     * @param sha1 第一个提交的哈希值
     * @param sha2 第二个提交的哈希值
     * @return 差异字符串，当前为占位实现
     */
    public String diffCommits(String sha1, String sha2) {
        if (!isInitialized() || sha1 == null || sha1.isBlank() || sha2 == null || sha2.isBlank()) {
            return "";
        }

        try (Git git = Git.open(workspace.toFile())) {
            Repository repo = git.getRepository();
            ObjectId oldId = repo.resolve(sha1);
            ObjectId newId = repo.resolve(sha2);
            if (oldId == null || newId == null) {
                return "";
            }

            try (RevWalk walk = new RevWalk(repo);
                 ObjectReader reader = repo.newObjectReader();
                 ByteArrayOutputStream out = new ByteArrayOutputStream();
                 DiffFormatter formatter = new DiffFormatter(out)) {

                RevCommit oldCommit = walk.parseCommit(oldId);
                RevCommit newCommit = walk.parseCommit(newId);

                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, oldCommit.getTree());
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, newCommit.getTree());

                formatter.setRepository(repo);
                formatter.setDetectRenames(true);

                List<DiffEntry> filtered = filterTrackedDiffs(formatter.scan(oldTree, newTree));
                if (filtered.isEmpty()) {
                    return "";
                }

                for (DiffEntry diff : filtered) {
                    formatter.format(diff);
                }
                return out.toString(StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private List<DiffEntry> filterTrackedDiffs(List<DiffEntry> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return List.of();
        }
        if (trackedFiles == null || trackedFiles.isEmpty()) {
            return diffs;
        }

        Set<String> tracked = new HashSet<>();
        for (String rel : trackedFiles) {
            if (rel != null && !rel.isBlank()) {
                tracked.add(rel.replace("\\", "/"));
            }
        }

        List<DiffEntry> filtered = new ArrayList<>();
        for (DiffEntry diff : diffs) {
            String oldPath = diff.getOldPath() != null ? diff.getOldPath().replace("\\", "/") : "";
            String newPath = diff.getNewPath() != null ? diff.getNewPath().replace("\\", "/") : "";
            if (tracked.contains(oldPath) || tracked.contains(newPath)) {
                filtered.add(diff);
            }
        }
        return filtered;
    }

    /**
     * 根据短哈希查找提交信息。
     * @param shortSha 短哈希值
     * @param maxEntries 最大搜索范围
     * @return 找到的 CommitInfo 对象，如果未找到返回 null
     */
    public CommitInfo findCommit(String shortSha, int maxEntries) {
        // 遍历日志中的提交信息
        for (CommitInfo c : log(maxEntries)) {
            // 如果提交的短哈希以目标短哈希开头
            if (c.sha().startsWith(shortSha)) {
                // 返回该提交信息
                return c;
            }
        }
        // 如果未找到，返回 null
        return null;
    }

    /**
     * 显示指定提交的详细信息和差异。
     * @param shortSha 短哈希值
     * @param maxEntries 最大搜索范围
     * @return ShowCommitDiffResult 对象，包含提交信息和差异内容
     */
    public ShowCommitDiffResult showCommitDiff(String shortSha, int maxEntries) {
        // 获取提交日志
        List<CommitInfo> commits = log(maxEntries);
        // 遍历日志
        for (int i = 0; i < commits.size(); i++) {
            // 获取当前提交信息
            CommitInfo c = commits.get(i);
            // 如果提交的短哈希以目标短哈希开头
            if (c.sha().startsWith(shortSha)) {
                // 计算与前一个提交的差异，如果是第一个提交则差异为空
                String diff = i + 1 < commits.size() ? diffCommits(commits.get(i + 1).sha(), c.sha()) : "";
                // 返回包含提交信息和差异的结果对象
                return new ShowCommitDiffResult(c, diff);
            }
        }
        // 如果未找到，返回 null
        return null;
    }

    /**
     * 回滚到指定的提交。
     * @param commit 目标提交的哈希值
     * @return 如果回滚成功返回新提交的短哈希，否则返回 null
     */
    public String revert(String commit) {
        // 如果仓库未初始化，返回 null
        if (!isInitialized()) {
            return null;
        }

        try (Git git = Git.open(workspace.toFile())) {
            // 仅回滚受管文件，避免污染业务代码工作区
            // 遍历所有需要跟踪的文件
            for (String rel : trackedFiles) {
                // 检出指定提交中的文件版本
                git.checkout()
                        .setStartPoint(commit) // 设置起始点为指定提交
                        .addPath(rel) // 添加文件路径
                        .call();
            }

            // 将回滚后的文件添加到暂存区
            for (String rel : trackedFiles) {
                git.add().addFilepattern(rel).call();
            }

            // 提交回滚更改
            RevCommit reverted = git.commit()
                    .setMessage("revert memory files to " + commit) // 设置提交消息
                    .setAuthor("ricbot", "ricbot@dream") // 设置作者信息
                    .call();
            // 返回新提交的短哈希
            return reverted.getId().name().substring(0, 8);
        } catch (Exception e) {
            // 如果发生异常，返回 null
            return null;
        }
    }

    /**
     * 构建 .gitignore 文件内容。
     * @return .gitignore 文件的内容字符串
     */
    private String buildGitignore() {
        // 初始化目录列表
        List<String> dirs = new ArrayList<>();
        // 遍历所有需要跟踪的文件
        for (String f : trackedFiles) {
            // 获取文件的父目录
            Path parent = Path.of(f).getParent();
            // 如果父目录存在且不是当前目录
            if (parent != null && !parent.toString().equals(".")) {
                // 将父目录路径添加到列表，并统一使用正斜杠
                dirs.add(parent.toString().replace("\\", "/"));
            }
        }

        // 初始化 StringBuilder，首先忽略所有文件
        StringBuilder sb = new StringBuilder("/*\n");
        // 添加需要保留的目录，去重并排序
        dirs.stream().distinct().sorted().forEach(d -> sb.append("!").append(d).append("/\n"));
        // 添加需要保留的具体文件
        for (String f : trackedFiles) {
            sb.append("!").append(f.replace("\\", "/")).append("\n");
        }
        // 确保 .gitignore 文件本身不被忽略
        sb.append("!.gitignore\n");
        // 返回构建好的字符串
        return sb.toString();
    }

    /**
     * ShowCommitDiffResult 记录类，用于封装提交信息和差异内容。
     * @param commit 提交信息
     * @param diff 差异内容
     */
    public record ShowCommitDiffResult(CommitInfo commit, String diff) {}
}
