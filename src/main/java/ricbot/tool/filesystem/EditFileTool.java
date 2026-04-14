package ricbot.tool.filesystem;

import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件编辑工具类 (EditFileTool)
 * <p>
 * 对应 Python 实现: EditFileTool
 * <p>
 * 主要功能：
 * 1. 基于指定的 old_text 和 new_text 对目标文件进行内容替换编辑。
 * 2. 支持单次替换（默认）或全局替换所有匹配项。
 * 3. 强制执行“先读后写”（read-before-edit）的安全检查机制，确保在编辑前文件已被读取，防止意外覆盖。
 * 4. 包含路径安全校验、二进制文件检测及存在性检查。
 */
public class EditFileTool extends Tool {

    /**
     * 工作空间根路径
     */
    private final Path workspace;

    /**
     * 允许访问的目录路径（用于安全限制）
     */
    private final Path allowedDir;

    /**
     * 构造函数
     *
     * @param workspace   工作空间根路径
     * @param allowedDir  允许访问的目录路径，若为 null 则不限制特定子目录（但仍受 workspace 限制）
     */
    public EditFileTool(Path workspace, Path allowedDir) {
        this.workspace = workspace;
        this.allowedDir = allowedDir != null ? allowedDir.toAbsolutePath().normalize() : null;
    }

    /**
     * 获取工具名称
     *
     * @return 工具的唯一标识名 "edit_file"
     */
    @Override
    public String getName() {
        return "edit_file";
    }

    /**
     * 获取工具描述
     *
     * @return 工具的简短描述，说明其用途及使用前提
     */
    @Override
    public String getDescription() {
        return "Edit a text file by replacing old_text with new_text. Read the file first before editing.";
    }

    /**
     * 获取工具参数定义列表
     *
     * @return 参数列表，包含 path, old_text, new_text, replace_all
     */
    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("path", "string", "Path of file to edit", true),
                ToolParam.of("old_text", "string", "Text to replace", true),
                ToolParam.of("new_text", "string", "Replacement text", true),
                ToolParam.of("replace_all", "boolean", "Whether to replace all matches", false).setDefaultValue(false)
        );
    }

    /**
     * 执行文件编辑操作
     * <p>
     * 流程：
     * 1. 解析并校验目标文件路径的安全性。
     * 2. 检查文件是否存在、是否为目录、是否为二进制文件。
     * 3. 检查是否满足“先读后写”条件。
     * 4. 读取文件内容，查找 old_text。
     * 5. 根据 replaceAll 标志执行替换（单次或全部）。
     * 6. 写入新内容并记录写入状态。
     *
     * @param path       要编辑的文件路径（相对于 workspace）
     * @param oldText    需要被替换的原始文本
     * @param newText    替换后的新文本
     * @param replaceAll 是否替换所有匹配项，true 为全部替换，false 为仅替换第一个匹配项
     * @return 操作结果消息，成功返回成功信息，失败返回错误信息
     */
    public String execute(String path, String oldText, String newText, Boolean replaceAll) {
        try {
            // 1. 解析路径并进行安全校验
            Path target = FileToolSupport.resolvePath(workspace, path);
            FileToolSupport.ensureAllowed(target, allowedDir, List.of());

            // 2. 基础文件状态检查
            if (!Files.exists(target)) {
                return "Error: file does not exist: " + target;
            }
            if (Files.isDirectory(target)) {
                return "Error: path is a directory, not a file: " + target;
            }
            if (FileToolSupport.isBinary(target)) {
                return "Error: file appears to be binary and cannot be edited as text.";
            }

            // 3. 检查 read-before-edit 约束
            String warning = FileReadState.checkRead(target);
            if (warning != null) {
                return warning;
            }

            // 4. 读取文件内容
            String content = FileToolSupport.readText(target);
            if (oldText == null || oldText.isEmpty()) {
                return "Error: old_text must not be empty.";
            }

            // 5. 准备替换逻辑
            boolean replaceAllFlag = replaceAll != null && replaceAll;
            String updated;

            // 检查旧文本是否存在
            if (!content.contains(oldText)) {
                return "Error: old_text not found in file.";
            }

            // 执行替换
            if (replaceAllFlag) {
                // 全部替换
                updated = content.replace(oldText, newText != null ? newText : "");
            } else {
                // 单次替换（使用 quote 避免正则特殊字符问题）
                updated = content.replaceFirst(
                        java.util.regex.Pattern.quote(oldText),
                        java.util.regex.Matcher.quoteReplacement(newText != null ? newText : "")
                );
            }

            // 6. 写入文件并记录状态
            FileToolSupport.writeText(target, updated);
            FileReadState.recordWrite(target);

            return "File edited successfully: " + target;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}