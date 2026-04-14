package ricbot.tool.filesystem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * NotebookEditTool:
 * 专门用于编辑 Jupyter Notebook (.ipynb) 文件的工具类。
 * 支持对 Notebook 中的单元格（Cell）进行替换、插入和删除操作。
 */
public class NotebookEditTool extends FsTool {

    /** JSON 对象映射器，用于序列化和反序列化 Notebook 文件 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    /** 有效的单元格类型集合 */
    private static final Set<String> VALID_CELL_TYPES = Set.of("code", "markdown");
    
    /** 有效的编辑模式集合：replace(替换), insert(插入), delete(删除) */
    private static final Set<String> VALID_EDIT_MODES = Set.of("replace", "insert", "delete");

    /**
     * 构造函数
     *
     * @param workspace       工作空间根路径
     * @param allowedDir      允许访问的目录
     * @param extraAllowedDirs 额外允许访问的目录列表
     */
    public NotebookEditTool(Path workspace, Path allowedDir, List<Path> extraAllowedDirs) {
        super(workspace, allowedDir, extraAllowedDirs);
    }

    /**
     * 获取工具名称
     *
     * @return 工具名称标识符
     */
    @Override
    public String getName() {
        return "notebook_edit";
    }

    /**
     * 获取工具描述
     *
     * @return 工具的简要功能描述
     */
    @Override
    public String getDescription() {
        return "Edit a Jupyter notebook (.ipynb) cell.";
    }

    /**
     * 获取工具参数定义，用于生成函数调用 schema
     *
     * @return 包含参数类型、属性定义及必填项的 Map
     */
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string"),
                        "cell_index", Map.of("type", "integer", "minimum", 0),
                        "new_source", Map.of("type", "string"),
                        "cell_type", Map.of("type", "string", "enum", List.of("code", "markdown")),
                        "edit_mode", Map.of("type", "string", "enum", List.of("replace", "insert", "delete"))
                ),
                "required", List.of("path", "cell_index")
        );
    }

    /**
     * 执行 Notebook 编辑操作
     *
     * @param kwargs 参数字典，包含 path, cell_index, new_source, cell_type, edit_mode 等
     * @return 操作结果字符串，成功或错误信息
     * @throws Exception 当发生不可预知的异常时抛出
     */
    @Override
    public Object execute(Map<String, Object> kwargs) throws Exception {
        String path = str(kwargs.get("path"));
        int cellIndex = intValue(kwargs.getOrDefault("cell_index", 0));
        String newSource = str(kwargs.getOrDefault("new_source", ""));
        String cellType = str(kwargs.getOrDefault("cell_type", "code"));
        String editMode = str(kwargs.getOrDefault("edit_mode", "replace"));

        // 参数校验
        if (path.isBlank()) return "Error: path is required";
        if (!path.endsWith(".ipynb")) {
            return "Error: notebook_edit only works on .ipynb files. Use edit_file for other files.";
        }
        if (!VALID_EDIT_MODES.contains(editMode)) {
            return "Error: Invalid edit_mode '" + editMode + "'. Use one of: replace, insert, delete.";
        }
        if (!VALID_CELL_TYPES.contains(cellType)) {
            return "Error: Invalid cell_type '" + cellType + "'. Use one of: code, markdown.";
        }

        Path fp = resolve(path);

        // 文件不存在时，仅 insert 模式允许自动创建新 Notebook
        if (!Files.exists(fp)) {
            if (!"insert".equals(editMode)) {
                return "Error: File not found: " + path;
            }
            Map<String, Object> nb = makeEmptyNotebook();
            List<Map<String, Object>> cells = castCells(nb.get("cells"));
            cells.add(newCell(newSource, cellType, true));
            nb.put("cells", cells);
            Files.createDirectories(fp.getParent());
            Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
            return "Successfully created " + fp + " with 1 cell";
        }

        // 读取并解析现有 Notebook 文件
        Map<String, Object> nb;
        try {
            nb = MAPPER.readValue(Files.readString(fp), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return "Error: Failed to parse notebook: " + e.getMessage();
        }

        List<Map<String, Object>> cells = castCells(nb.getOrDefault("cells", new ArrayList<>()));
        int nbformat = intValue(nb.getOrDefault("nbformat", 0));
        int nbformatMinor = intValue(nb.getOrDefault("nbformat_minor", 0));
        // Jupyter Notebook format >= 4.5 需要生成单元格 ID
        boolean generateId = nbformat >= 4 && nbformatMinor >= 5;

        // 处理删除模式
        if ("delete".equals(editMode)) {
            if (cellIndex < 0 || cellIndex >= cells.size()) {
                return "Error: cell_index " + cellIndex + " out of range (notebook has " + cells.size() + " cells)";
            }
            cells.remove(cellIndex);
            nb.put("cells", cells);
            Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
            return "Successfully deleted cell " + cellIndex + " from " + fp;
        }

        // 处理插入模式
        if ("insert".equals(editMode)) {
            int insertAt = Math.min(cellIndex + 1, cells.size());
            cells.add(insertAt, newCell(newSource, cellType, generateId));
            nb.put("cells", cells);
            Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
            return "Successfully inserted cell at index " + insertAt + " in " + fp;
        }

        // 处理替换模式 (默认)
        if (cellIndex < 0 || cellIndex >= cells.size()) {
            return "Error: cell_index " + cellIndex + " out of range (notebook has " + cells.size() + " cells)";
        }

        Map<String, Object> cell = cells.get(cellIndex);
        cell.put("source", newSource);

        // 如果单元格类型发生变化，调整相关字段
        String oldType = String.valueOf(cell.getOrDefault("cell_type", ""));
        if (!oldType.equals(cellType)) {
            cell.put("cell_type", cellType);
            if ("code".equals(cellType)) {
                // 代码单元格需要 outputs 和 execution_count
                cell.putIfAbsent("outputs", new ArrayList<>());
                cell.putIfAbsent("execution_count", null);
            } else {
                // Markdown 单元格不需要 outputs 和 execution_count
                cell.remove("outputs");
                cell.remove("execution_count");
            }
        }

        nb.put("cells", cells);
        Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
        return "Successfully edited cell " + cellIndex + " in " + fp;
    }

    /**
     * 创建一个新的 Notebook 单元格对象
     *
     * @param source     单元格源代码内容
     * @param cellType   单元格类型 (code 或 markdown)
     * @param generateId 是否生成唯一 ID (适用于 nbformat >= 4.5)
     * @return 表示单元格的 Map 对象
     */
    private static Map<String, Object> newCell(String source, String cellType, boolean generateId) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("cell_type", cellType);
        cell.put("source", source);
        cell.put("metadata", new LinkedHashMap<>());
        if ("code".equals(cellType)) {
            cell.put("outputs", new ArrayList<>());
            cell.put("execution_count", null);
        }
        if (generateId) {
            // 生成简短的唯一 ID
            cell.put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        }
        return cell;
    }

    /**
     * 创建一个空的 Jupyter Notebook 结构
     *
     * @return 表示空 Notebook 的 Map 对象
     */
    private static Map<String, Object> makeEmptyNotebook() {
        Map<String, Object> nb = new LinkedHashMap<>();
        nb.put("nbformat", 4);
        nb.put("nbformat_minor", 5);
        nb.put("metadata", Map.of(
                "kernelspec", Map.of("display_name", "Python 3", "language", "python", "name", "python3"),
                "language_info", Map.of("name", "python")
        ));
        nb.put("cells", new ArrayList<>());
        return nb;
    }

    /**
     * 安全地将对象转换为单元格列表
     *
     * @param cellsObj 原始对象
     * @return 单元格列表
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castCells(Object cellsObj) {
        return (List<Map<String, Object>>) cellsObj;
    }

    /**
     * 将对象转换为字符串，null 值返回空字符串
     *
     * @param o 输入对象
     * @return 字符串表示
     */
    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 将对象转换为整数
     *
     * @param o 输入对象 (Number 或可解析为整数的字符串)
     * @return 整数值
     */
    private static int intValue(Object o) {
        return o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o));
    }
}