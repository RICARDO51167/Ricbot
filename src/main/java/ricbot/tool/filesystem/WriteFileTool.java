package ricbot.tool.filesystem;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件写入工具
 */
public class WriteFileTool extends Tool {

    private final Path workspace;

    private final Path allowedDir;

    public WriteFileTool(Path workspace, Path allowedDir) {
        this.workspace = workspace;
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "将内容写入文件（覆盖已有内容）。";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("path", "string", "要写入的文件路径", true),
                ToolParam.of("content", "string", "文件内容", true)
        );
    }

    public String execute(String path, String content) {
        try {
            Path target = FileToolSupport.resolvePath(workspace, path);
            FileToolSupport.ensureAllowed(target, allowedDir, List.of());

            FileToolSupport.writeText(target, content);
            FileReadState.recordWrite(target);

            return "文件已写入：" + target;
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}
