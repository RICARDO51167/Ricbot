package ricbot.tool.search;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.tool.filesystem.FileToolSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grep 工具：在目录下递归搜索文本内容，支持正则、文件过滤及二进制跳过。
 */
public class GrepTool extends Tool {

    private final Path workspace;
    private final Path allowedDir;

    public GrepTool(Path workspace, Path allowedDir) {
        this.workspace = workspace;
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
    }

    @Override
    public String getName() {
        return "grep";
    }

    @Override
    public String getDescription() {
        return "在目录下的文件中搜索文本或正则表达式。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("pattern", "string", "要搜索的正则表达式", true),
                ToolParam.of("base_dir", "string", "搜索起始目录", false).setDefaultValue("."),
                ToolParam.of("file_glob", "string", "可选：按文件名通配符过滤，例如 '**/*.java'", false),
                ToolParam.of("ignore_case", "boolean", "是否忽略大小写", false).setDefaultValue(false),
                ToolParam.of("max_results", "integer", "最多返回的匹配条数", false).setDefaultValue(100)
        );
    }

    public String execute(
            String pattern,
            String baseDir,
            String fileGlob,
            Boolean ignoreCase,
            Integer maxResults
    ) {
        try {
            if (pattern == null || pattern.isBlank()) {
                return "错误：必须提供搜索模式（pattern）。";
            }

            String rootStr = (baseDir == null || baseDir.isBlank()) ? "." : baseDir;
            Path root = FileToolSupport.resolvePath(workspace, rootStr);
            FileToolSupport.ensureAllowed(root, allowedDir, List.of());

            if (!Files.exists(root)) {
                return "错误：搜索目录不存在：" + root;
            }
            if (!Files.isDirectory(root)) {
                return "错误：搜索路径不是目录：" + root;
            }

            int flags = Pattern.MULTILINE;
            if (ignoreCase != null && ignoreCase) {
                flags |= Pattern.CASE_INSENSITIVE;
            }

            Pattern regex;
            try {
                regex = Pattern.compile(pattern, flags);
            } catch (Exception e) {
                return "错误：正则表达式无效：" + e.getMessage();
            }

            PathMatcher fileMatcher = null;
            if (fileGlob != null && !fileGlob.isBlank()) {
                fileMatcher = FileSystems.getDefault().getPathMatcher("glob:" + fileGlob);
            }

            int limit = maxResults != null && maxResults > 0 ? maxResults : 100;
            List<String> matches = new ArrayList<>();

            try (var stream = Files.walk(root)) {
                for (Path path : stream.toList()) {
                    if (matches.size() >= limit) {
                        break;
                    }

                    try {
                        Path normalized = path.toAbsolutePath().normalize();

                        if (!Files.isRegularFile(normalized)) {
                            continue;
                        }

                        if (allowedDir != null) {
                            Path base = allowedDir.toAbsolutePath().normalize();
                            if (!normalized.equals(base) && !normalized.startsWith(base)) {
                                continue;
                            }
                        }

                        if (FileToolSupport.isBinary(normalized)) {
                            continue;
                        }

                        if (fileMatcher != null) {
                            Path relative = root.relativize(normalized);
                            boolean ok = fileMatcher.matches(relative)
                                    || fileMatcher.matches(normalized)
                                    || fileMatcher.matches(Path.of(relative.toString().replace("\\", "/")));
                            if (!ok) {
                                continue;
                            }
                        }

                        List<String> lines = Files.readAllLines(normalized, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            if (matches.size() >= limit) {
                                break;
                            }

                            String line = lines.get(i);
                            Matcher m = regex.matcher(line);
                            if (m.find()) {
                                matches.add(normalized + ":" + (i + 1) + ": " + line);
                            }
                        }

                    } catch (Exception ignored) {
                    }
                }
            }

            if (matches.isEmpty()) {
                return "未找到匹配项：" + pattern;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("匹配结果（搜索模式：").append(pattern).append("）\n\n");
            for (String match : matches) {
                sb.append(match).append("\n");
            }

            if (matches.size() >= limit) {
                sb.append("\n...（结果过多，已截断至 ").append(limit).append(" 条）");
            }

            return sb.toString().trim();

        } catch (IOException e) {
            return "错误：" + e.getMessage();
        }
    }
}
