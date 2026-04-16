package ricbot.tool.filesystem;



import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.nio.file.Path;
import java.util.List;

/**
 * 对应 Python: WriteFileTool
 *
 * 主要目标：
 * 1. 写入文件（覆盖写）
 * 2. 自动创建父目录
 * 3. 写完后更新 read state
 */
public class WriteFileTool extends Tool {

    /**
     * 工作空间根路径
     */
    private final Path workspace;

    /**
     * 允许访问的目录路径，用于安全校验
     */
    private final Path allowedDir;

    /**
     * 构造函数
     *
     * @param workspace 工作空间根路径
     * @param allowedDir 允许访问的目录路径，若为 null 则不进行特定目录限制
     */
    public WriteFileTool(Path workspace, Path allowedDir) {
        this.workspace = workspace;
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
    }

    /**
     * 获取工具名称
     *
     * @return 工具名称 "write_file"
     */
    @Override
    public String getName() {
        return "write_file";
    }

    /**
     * 获取工具描述
     *
     * @return 工具功能描述
     */
    @Override
    public String getDescription() {
        return "将内容写入文件（覆盖已有内容）。";
    }

    /**
     * 获取工具参数定义
     *
     * @return 参数列表，包含文件路径和文件内容
     */
    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("path", "string", "要写入的文件路径", true),
                ToolParam.of("content", "string", "文件内容", true)
        );
    }

    /**
     * 执行文件写入操作
     *
     * @param path 目标文件路径
     * @param content 要写入的文件内容
     * @return 操作结果消息，成功时返回写入路径，失败时返回错误信息
     */
    public String execute(String path, String content) {
        try {
            // 解析并规范化目标路径
            Path target = FileToolSupport.resolvePath(workspace, path);
            // 校验路径是否在允许范围内
            FileToolSupport.ensureAllowed(target, allowedDir, List.of());

            // 写入文件内容
            FileToolSupport.writeText(target, content);
            // 记录写入状态，以便后续读取操作能感知到变更
            FileReadState.recordWrite(target);

            return "文件已写入：" + target;
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}
