package ricbot.tool.filesystem;


import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件读取工具类，对应 Python 端的 ReadFileTool。
 * <p>
 * 主要功能：
 * 1. 安全地读取工作区内的文本文件内容。
 * 2. 支持基于行号的切片读取（分页），通过 offset（起始行，从1开始）和 limit（最大行数）控制。
 * 3. 自动检测并拒绝读取二进制文件，防止乱码或性能问题。
 * 4. 维护读取状态（Read State），如果文件未发生变化且请求范围相同，则提示未变更以节省 token。
 */
public class ReadFileTool extends Tool {

    /**
     * 工作区根路径
     */
    private final Path workspace;

    /**
     * 允许访问的主目录路径（已标准化）
     */
    private final Path allowedDir;

    /**
     * 额外允许访问的目录列表（已标准化）
     */
    private final List<Path> extraAllowedDirs;

    /**
     * 构造函数
     *
     * @param workspace       工作区根路径
     * @param allowedDir      允许访问的主目录，若为 null 则仅依赖 workspace
     * @param extraAllowedDirs 额外允许访问的目录列表
     */
    public ReadFileTool(Path workspace, Path allowedDir, List<Path> extraAllowedDirs) {
        this.workspace = workspace;
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
        this.extraAllowedDirs = FileToolSupport.normalizeExtraDirs(extraAllowedDirs);
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "读取文本文件。支持按行切片读取：offset（从 1 开始）与可选的 limit。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("path", "string", "文件路径", true),
                ToolParam.of("offset", "integer", "起始行号（从 1 开始）", false).setDefaultValue(1),
                ToolParam.of("limit", "integer", "可选：最大读取行数", false)
        );
    }

    /**
     * 执行文件读取操作。
     * <p>
     * 流程：
     * 1. 解析并校验目标路径是否在允许范围内。
     * 2. 检查文件是否存在、是否为目录、是否为二进制文件。
     * 3. 检查读取状态，若文件未变且请求参数一致，返回未变更提示。
     * 4. 读取文件内容并按指定行范围切片返回。
     * 5. 记录本次读取状态。
     *
     * @param path   文件相对路径
     * @param offset 起始行号（从1开始），默认为 1
     * @param limit  最大读取行数，可选
     * @return 读取到的文件内容片段，或错误信息
     */
    public String execute(String path, Integer offset, Integer limit) {
        try {
            // 解析路径并进行安全校验
            Path target = FileToolSupport.resolvePath(workspace, path);
            FileToolSupport.ensureAllowed(target, allowedDir, extraAllowedDirs);

            // 基础文件状态检查
            if (!Files.exists(target)) {
                return "错误：文件不存在：" + target;
            }
            if (Files.isDirectory(target)) {
                return "错误：该路径是目录而非文件：" + target;
            }
            if (FileToolSupport.isBinary(target)) {
                return "错误：该文件疑似为二进制文件，无法按文本读取。";
            }

            // 处理默认偏移量
            int off = offset != null ? offset : 1;

            // 检查文件是否自上次读取以来未发生变化，以优化 Token 使用
            if (FileReadState.isUnchanged(target, off, limit)) {
                return "文件自上次读取后未发生变化。\n\n" +
                        FileToolSupport.sliceLines(target, off, limit);
            }

            // 按行流式读取切片并记录状态
            String content = FileToolSupport.sliceLines(target, off, limit);
            FileReadState.recordRead(target, off, limit);

            return content;
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}
