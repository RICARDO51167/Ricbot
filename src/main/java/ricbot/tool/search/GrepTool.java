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

    // 工作空间根路径
    private final Path workspace;
    // 允许访问的目录路径，用于安全校验
    private final Path allowedDir;

    /**
     * 构造函数
     *
     * @param workspace 工作空间根路径
     * @param allowedDir 允许访问的目录路径，若为 null 则不限制（但通常会有默认限制）
     */
    public GrepTool(Path workspace, Path allowedDir) {
        this.workspace = workspace;
        // 如果 allowedDir 不为空，则将其转换为绝对路径并规范化；否则保持为 null
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
    }

    /**
     * 获取工具名称
     *
     * @return 工具名称 "grep"
     */
    @Override
    public String getName() {
        return "grep";
    }

    /**
     * 获取工具描述
     *
     * @return 工具功能描述
     */
    @Override
    public String getDescription() {
        return "在目录下的文件中搜索文本或正则表达式。";
    }

    /**
     * 判断工具是否为只读操作
     *
     * @return true，因为 grep 只是搜索，不修改文件
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 获取工具参数定义
     *
     * @return 参数列表，包括 pattern, base_dir, file_glob, ignore_case, max_results
     */
    @Override
    public List<ToolParam> getParams() {
        return List.of(
                // 必选参数：要搜索的正则表达式
                ToolParam.of("pattern", "string", "要搜索的正则表达式", true),
                // 可选参数：搜索起始目录，默认为当前目录 "."
                ToolParam.of("base_dir", "string", "搜索起始目录", false).setDefaultValue("."),
                // 可选参数：文件名通配符过滤，如 '**/*.java'
                ToolParam.of("file_glob", "string", "可选：按文件名通配符过滤，例如 '**/*.java'", false),
                // 可选参数：是否忽略大小写，默认 false
                ToolParam.of("ignore_case", "boolean", "是否忽略大小写", false).setDefaultValue(false),
                // 可选参数：最大返回结果数，默认 100
                ToolParam.of("max_results", "integer", "最多返回的匹配条数", false).setDefaultValue(100)
        );
    }

    /**
     * 执行 grep 搜索逻辑
     *
     * @param pattern    搜索正则表达式
     * @param baseDir    搜索起始目录
     * @param fileGlob   文件通配符过滤
     * @param ignoreCase 是否忽略大小写
     * @param maxResults 最大结果数量
     * @return 搜索结果字符串或错误信息
     */
    public String execute(
            String pattern,
            String baseDir,
            String fileGlob,
            Boolean ignoreCase,
            Integer maxResults
    ) {
        try {
            // 校验 pattern 是否为空
            if (pattern == null || pattern.isBlank()) {
                return "错误：必须提供搜索模式（pattern）。";
            }

            // 确定搜索根目录，若 baseDir 为空则默认为 "."
            String rootStr = (baseDir == null || baseDir.isBlank()) ? "." : baseDir;
            // 解析完整路径
            Path root = FileToolSupport.resolvePath(workspace, rootStr);
            // 确保路径在允许访问的范围内
            FileToolSupport.ensureAllowed(root, allowedDir, List.of());

            // 检查目录是否存在
            if (!Files.exists(root)) {
                return "错误：搜索目录不存在：" + root;
            }
            // 检查路径是否为目录
            if (!Files.isDirectory(root)) {
                return "错误：搜索路径不是目录：" + root;
            }

            // 设置正则表达式标志，默认多行模式
            int flags = Pattern.MULTILINE;
            // 如果忽略大小写，添加 CASE_INSENSITIVE 标志
            if (ignoreCase != null && ignoreCase) {
                flags |= Pattern.CASE_INSENSITIVE;
            }

            // 编译正则表达式
            Pattern regex;
            try {
                regex = Pattern.compile(pattern, flags);
            } catch (Exception e) {
                // 如果正则表达式无效，返回错误信息
                return "错误：正则表达式无效：" + e.getMessage();
            }

            // 初始化文件通配符匹配器
            PathMatcher fileMatcher = null;
            if (fileGlob != null && !fileGlob.isBlank()) {
                // 创建 glob 模式的 PathMatcher
                fileMatcher = FileSystems.getDefault().getPathMatcher("glob:" + fileGlob);
            }

            // 确定最大结果限制，默认 100
            int limit = maxResults != null && maxResults > 0 ? maxResults : 100;
            // 存储匹配结果的列表
            List<String> matches = new ArrayList<>();

            // 遍历目录树
            try (var stream = Files.walk(root)) {
                for (Path path : stream.toList()) {
                    // 如果已达到最大结果数，提前退出循环
                    if (matches.size() >= limit) {
                        break;
                    }

                    try {
                        // 规范化当前文件路径
                        Path normalized = path.toAbsolutePath().normalize();

                        // 如果不是普通文件（如是目录或符号链接等），跳过
                        if (!Files.isRegularFile(normalized)) {
                            continue;
                        }

                        // 安全检查：确保文件在允许的目录范围内
                        if (allowedDir != null) {
                            Path base = allowedDir.toAbsolutePath().normalize();
                            // 如果文件不在 allowedDir 下，跳过
                            if (!normalized.equals(base) && !normalized.startsWith(base)) {
                                continue;
                            }
                        }

                        // 跳过二进制文件
                        if (FileToolSupport.isBinary(normalized)) {
                            continue;
                        }

                        // 如果设置了文件通配符过滤，检查文件名是否匹配
                        if (fileMatcher != null) {
                            // 获取相对于根目录的路径
                            Path relative = root.relativize(normalized);
                            // 尝试多种匹配方式以兼容不同操作系统的路径分隔符
                            boolean ok = fileMatcher.matches(relative)
                                    || fileMatcher.matches(normalized)
                                    || fileMatcher.matches(Path.of(relative.toString().replace("\\", "/")));
                            // 如果不匹配，跳过该文件
                            if (!ok) {
                                continue;
                            }
                        }

                        // 读取文件所有行
                        List<String> lines = Files.readAllLines(normalized, StandardCharsets.UTF_8);
                        // 逐行匹配正则表达式
                        for (int i = 0; i < lines.size(); i++) {
                            // 再次检查是否达到最大结果数
                            if (matches.size() >= limit) {
                                break;
                            }

                            String line = lines.get(i);
                            Matcher m = regex.matcher(line);
                            // 如果找到匹配项
                            if (m.find()) {
                                // 格式：文件路径:行号: 内容
                                matches.add(normalized + ":" + (i + 1) + ": " + line);
                            }
                        }

                    } catch (Exception ignored) {
                        // 忽略单个文件处理中的异常（如权限不足、编码错误等）
                    }
                }
            }

            // 如果没有找到任何匹配项
            if (matches.isEmpty()) {
                return "未找到匹配项：" + pattern;
            }

            // 构建结果字符串
            StringBuilder sb = new StringBuilder();
            sb.append("匹配结果（搜索模式：").append(pattern).append("）\n\n");
            for (String match : matches) {
                sb.append(match).append("\n");
            }

            // 如果结果被截断，添加提示信息
            if (matches.size() >= limit) {
                sb.append("\n...（结果过多，已截断至 ").append(limit).append(" 条）");
            }

            // 返回去除首尾空白后的结果字符串
            return sb.toString().trim();

        } catch (IOException e) {
            // 捕获 IO 异常并返回错误信息
            return "错误：" + e.getMessage();
        }
    }
}
