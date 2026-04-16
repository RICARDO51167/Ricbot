package ricbot.tool.filesystem;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件编辑工具类
 */
public class EditFileTool extends Tool {

    private final Path workspace;

    private final Path allowedDir;

    public EditFileTool(Path workspace, Path allowedDir) {
        this.workspace = workspace;
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
    }

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "通过用 new_text 替换 old_text 来编辑文本文件。编辑前必须先读取文件。";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("path", "string", "要编辑的文件路径", true),
                ToolParam.of("old_text", "string", "要被替换的文本", true),
                ToolParam.of("new_text", "string", "替换后的文本", true),
                ToolParam.of("replace_all", "boolean", "是否替换所有匹配项", false).setDefaultValue(false)
        );
    }

    public String execute(String path, String oldText, String newText, Boolean replaceAll) {
        try {
            Path target = FileToolSupport.resolvePath(workspace, path);
            FileToolSupport.ensureAllowed(target, allowedDir, List.of());

            if (!Files.exists(target)) {
                return "错误：文件不存在：" + target;
            }
            if (Files.isDirectory(target)) {
                return "错误：该路径是目录而非文件：" + target;
            }
            if (FileToolSupport.isBinary(target)) {
                return "错误：该文件疑似为二进制文件，无法按文本编辑。";
            }

            String warning = FileReadState.checkRead(target);
            if (warning != null) {
                return warning;
            }

            String content = FileToolSupport.readText(target);
            if (oldText == null || oldText.isEmpty()) {
                return "错误：待替换文本不能为空。";
            }

            boolean replaceAllFlag = replaceAll != null && replaceAll;
            String updated;

            if (!content.contains(oldText)) {
                return "错误：在文件中未找到待替换文本。";
            }

            if (replaceAllFlag) {
                updated = content.replace(oldText, newText != null ? newText : "");
            } else {
                updated = content.replaceFirst(
                        java.util.regex.Pattern.quote(oldText),
                        java.util.regex.Matcher.quoteReplacement(newText != null ? newText : "")
                );
            }

            FileToolSupport.writeText(target, updated);
            FileReadState.recordWrite(target);

            return "Success: edited file " + target;
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}
