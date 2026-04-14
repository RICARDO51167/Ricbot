package ricbot.tool.search;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.tool.filesystem.FileToolSupport;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 对应 Python: GlobTool
 *
 * 主要目标：
 * 1. 按 glob 模式查找文件
 * 2. 支持相对 workspace 的递归匹配
 * 3. 受 allowedDir 限制
 */
public class GlobTool extends Tool {

    private final Path workspace;
    private final Path allowedDir;

    public GlobTool(Path workspace, Path allowedDir) {
        this.workspace = workspace;
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
    }

    @Override
    public String getName() {
        return "glob";
    }

    @Override
    public String getDescription() {
        return "Find files by glob pattern, for example '**/*.java' or 'src/**/*.md'.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("pattern", "string", "Glob pattern to match", true),
                ToolParam.of("base_dir", "string", "Optional base directory to search from", false).setDefaultValue(".")
        );
    }

    public String execute(String pattern, String baseDir) {
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

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> matches = new ArrayList<>();

            try (var stream = Files.walk(root)) {
                stream.forEach(path -> {
                    try {
                        Path normalized = path.toAbsolutePath().normalize();

                        // 再做一次防御性限制
                        if (allowedDir != null) {
                            Path base = allowedDir.toAbsolutePath().normalize();
                            if (!normalized.equals(base) && !normalized.startsWith(base)) {
                                return;
                            }
                        }

                        Path relative = root.relativize(normalized);
                        if (matcher.matches(relative) || matcher.matches(normalized) || matcher.matches(Path.of(relative.toString().replace("\\", "/")))) {
                            matches.add(normalized.toString());
                        }
                    } catch (Exception ignored) {
                    }
                });
            }

            if (matches.isEmpty()) {
                return "No files matched pattern: " + pattern;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Matches for pattern: ").append(pattern).append("\n\n");
            for (String match : matches) {
                sb.append(match).append("\n");
            }
            return sb.toString().trim();

        } catch (IllegalArgumentException e) {
            return "Error: invalid glob pattern: " + e.getMessage();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }
}