package ricbot.tool.search;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.tool.filesystem.FileToolSupport;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Glob 文件搜索工具
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
        return "按 glob 模式查找文件，例如 '**/*.java' 或 'src/**/*.md'。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("pattern", "string", "要匹配的 glob 模式", true),
                ToolParam.of("base_dir", "string", "可选：搜索起始目录", false).setDefaultValue(".")
        );
    }

    public String execute(String pattern, String baseDir) {
        try {
            if (pattern == null || pattern.isBlank()) {
                return "错误：必须提供匹配模式（pattern）。";
            }

            String rootStr = (baseDir == null || baseDir.isBlank()) ? "." : baseDir;
            Path root = FileToolSupport.resolvePath(workspace, rootStr);
            FileToolSupport.ensureAllowed(root, allowedDir, List.of());

            if (!Files.exists(root)) {
                return "错误：起始目录（base_dir）不存在：" + root;
            }
            if (!Files.isDirectory(root)) {
                return "错误：起始目录（base_dir）不是目录：" + root;
            }

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> matches = new ArrayList<>();

            try (var stream = Files.walk(root)) {
                stream.forEach(path -> {
                    try {
                        Path normalized = path.toAbsolutePath().normalize();

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
                return "未找到匹配模式的文件：" + pattern;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("匹配结果（模式：").append(pattern).append("）\n\n");
            for (String match : matches) {
                sb.append(match).append("\n");
            }
            return sb.toString().trim();

        } catch (IllegalArgumentException e) {
            return "错误：无效的 glob 模式：" + e.getMessage();
        } catch (IOException e) {
            return "错误：" + e.getMessage();
        }
    }
}
