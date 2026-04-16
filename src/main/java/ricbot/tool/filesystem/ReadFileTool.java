package ricbot.tool.filesystem;


import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件读取工具类
 */
public class ReadFileTool extends Tool {

    private final Path workspace;

    private final Path allowedDir;

    private final List<Path> extraAllowedDirs;

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

    public String execute(String path, Integer offset, Integer limit) {
        try {
            Path target = FileToolSupport.resolvePath(workspace, path);
            FileToolSupport.ensureAllowed(target, allowedDir, extraAllowedDirs);

            if (!Files.exists(target)) {
                return "错误：文件不存在：" + target;
            }
            if (Files.isDirectory(target)) {
                return "错误：该路径是目录而非文件：" + target;
            }
            if (FileToolSupport.isBinary(target)) {
                return "错误：该文件疑似为二进制文件，无法按文本读取。";
            }

            int off = offset != null ? offset : 1;

            if (FileReadState.isUnchanged(target, off, limit)) {
                return "文件自上次读取后未发生变化。\n\n" +
                        FileToolSupport.sliceLines(FileToolSupport.readText(target), off, limit);
            }

            String content = FileToolSupport.readText(target);
            FileReadState.recordRead(target, off, limit);

            return FileToolSupport.sliceLines(content, off, limit);
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}
