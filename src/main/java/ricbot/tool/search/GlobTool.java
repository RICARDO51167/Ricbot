package ricbot.tool.search;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.tool.filesystem.FileToolSupport;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对应 Python: GlobTool
 *
 * 主要目标：
 * 1. 按 glob 模式查找文件
 * 2. 支持相对 workspace 的递归匹配
 * 3. 受 allowedDir 限制
 */
public class GlobTool extends Tool {
    @Override public ricbot.tool.api.ToolEffectPolicy effectPolicy() {
        return ricbot.tool.api.ToolEffectPolicy.readOnly(java.time.Duration.ofSeconds(30));
    }

    // 工作空间根路径
    private final Path workspace;
    // 允许访问的目录路径，用于安全限制
    private final Path allowedDir;

    /**
     * 构造函数
     * @param workspace 工作空间根路径
     * @param allowedDir 允许访问的目录路径，若为 null 则不限制
     */
    public GlobTool(Path workspace, Path allowedDir) {
        this.workspace = workspace;
        // 如果 allowedDir 不为 null，则将其转换为绝对路径并规范化，否则设为 null
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
    }

    /**
     * 获取工具名称
     * @return 工具名称 "glob"
     */
    @Override
    public String getName() {
        return "glob";
    }

    /**
     * 获取工具描述
     * @return 工具功能描述
     */
    @Override
    public String getDescription() {
        return "按 glob 模式查找文件，例如 '**/*.java' 或 'src/**/*.md'。";
    }

    /**
     * 获取工具参数定义
     * @return 参数列表，包含 pattern 和 base_dir
     */
    @Override
    public List<ToolParam> getParams() {
        return List.of(
                // 必填参数：glob 匹配模式
                ToolParam.of("pattern", "string", "要匹配的 glob 模式", true),
                // 可选参数：搜索起始目录，默认为当前目录 "."
                ToolParam.of("base_dir", "string", "可选：搜索起始目录", false).setDefaultValue(".")
        );
    }

    /**
     * 执行 glob 搜索逻辑
     * @param pattern glob 匹配模式
     * @param baseDir 搜索起始目录
     * @return 匹配结果字符串或错误信息
     */
    @Override
    public Object execute(Map<String, Object> params) {
        Map<String, Object> safe = params != null ? params : Map.of();
        return glob((String) safe.get("pattern"), (String) safe.get("base_dir"));
    }

    private String glob(String pattern, String baseDir) {
        try {
            // 检查 pattern 是否为空
            if (pattern == null || pattern.isBlank()) {
                return "错误：必须提供匹配模式（pattern）。";
            }

            // 确定根目录字符串，如果 baseDir 为空则使用 "."
            String rootStr = (baseDir == null || baseDir.isBlank()) ? "." : baseDir;
            // 解析根目录路径
            Path root = FileToolSupport.resolvePath(workspace, rootStr);
            // 确保根目录在允许访问的范围内
            FileToolSupport.ensureAllowed(root, allowedDir, List.of());

            // 检查根目录是否存在
            if (!Files.exists(root)) {
                return "错误：起始目录（base_dir）不存在：" + root;
            }
            // 检查根目录是否为目录
            if (!Files.isDirectory(root)) {
                return "错误：起始目录（base_dir）不是目录：" + root;
            }

            // 创建 PathMatcher 用于匹配 glob 模式
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            // 存储匹配到的文件路径
            List<String> matches = new ArrayList<>();

            // 遍历根目录下的所有文件
            try (var stream = Files.walk(root)) {
                stream.forEach(path -> {
                    try {
                        // 将路径转换为绝对路径并规范化
                        Path normalized = path.toAbsolutePath().normalize();
                        FileToolSupport.ensureAllowed(normalized, allowedDir, List.of());

                        // 计算相对于 root 的路径
                        Path relative = root.relativize(normalized);
                        // 尝试多种路径形式进行匹配：相对路径、绝对路径、以及将反斜杠替换为正斜杠的相对路径
                        if (matcher.matches(relative) || matcher.matches(normalized) || matcher.matches(Path.of(relative.toString().replace("\\", "/")))) {
                            // 如果匹配成功，添加绝对路径到结果列表
                            matches.add(normalized.toString());
                        }
                    } catch (Exception ignored) {
                        // 忽略单个文件处理中的异常
                    }
                });
            }

            // 如果没有匹配到任何文件
            if (matches.isEmpty()) {
                return "未找到匹配模式的文件：" + pattern;
            }

            // 构建结果字符串
            StringBuilder sb = new StringBuilder();
            sb.append("匹配结果（模式：").append(pattern).append("）\n\n");
            for (String match : matches) {
                sb.append(match).append("\n");
            }
            // 返回去除末尾空白字符的结果
            return sb.toString().trim();

        } catch (IllegalArgumentException e) {
            // 捕获非法参数异常，通常是无效的 glob 模式
            return "错误：无效的 glob 模式：" + e.getMessage();
        } catch (IOException e) {
            // 捕获 IO 异常
            return "错误：" + e.getMessage();
        }
    }
}
