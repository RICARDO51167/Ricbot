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
 * 对应 Python: GrepTool
 *
 * 主要目标：
 * 1. 在目录下递归搜索文本内容
 * 2. 支持正则 pattern
 * 3. 支持 file_glob 过滤
 * 4. 跳过二进制文件
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
        return "Search for text or regex in files under a directory.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("pattern", "string", "Regex pattern to search for", true),
                ToolParam.of("base_dir", "string", "Directory to search from", false).setDefaultValue("."),
                ToolParam.of("file_glob", "string", "Optional glob filter for file names, e.g. '**/*.java'", false),
                ToolParam.of("ignore_case", "boolean", "Whether regex match should ignore case", false).setDefaultValue(false),
                ToolParam.of("max_results", "integer", "Maximum number of matches to return", false).setDefaultValue(100)
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
                return "Error: pattern is required.";
            }

            String rootStr = (baseDir == null || baseDir.isBlank()) ? "." : baseDir;
            Path root = FileToolSupport.resolvePath(workspace, rootStr);
            FileToolSupport.ensureAllowed(root, allowedDir, List.of());

            if (!Files.exists(root)) {
                return "Error: base_dir does not exist: " + root;
            }
            if (!Files.isDirectory(root)) {
                return "Error: base_dir is not a directory: " + root;
            }

            int flags = Pattern.MULTILINE;
            if (ignoreCase != null && ignoreCase) {
                flags |= Pattern.CASE_INSENSITIVE;
            }

            Pattern regex;
            try {
                regex = Pattern.compile(pattern, flags);
            } catch (Exception e) {
                return "Error: invalid regex pattern: " + e.getMessage();
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
                return "No matches for pattern: " + pattern;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Matches for pattern: ").append(pattern).append("\n\n");
            for (String match : matches) {
                sb.append(match).append("\n");
            }

            if (matches.size() >= limit) {
                sb.append("\n... (truncated at ").append(limit).append(" results)");
            }

            return sb.toString().trim();

        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }
}