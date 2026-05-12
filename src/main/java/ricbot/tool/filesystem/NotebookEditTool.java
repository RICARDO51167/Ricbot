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
        // 调用父类 FsTool 的构造函数，初始化工作空间和权限目录
        super(workspace, allowedDir, extraAllowedDirs);
    }

    /**
     * 获取工具名称
     *
     * @return 工具名称标识符
     */
    @Override
    public String getName() {
        // 返回工具的唯一标识名称
        return "notebook_edit";
    }

    /**
     * 获取工具描述
     *
     * @return 工具的简要功能描述
     */
    @Override
    public String getDescription() {
        // 返回工具的功能描述，用于向用户或 AI 解释用途
        return "编辑 Jupyter Notebook（.ipynb）中的单元格。";
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
        // 从参数字典中提取路径，若为 null 则转为空字符串
        String path = str(kwargs.get("path"));
        // 提取单元格索引，默认为 0
        int cellIndex = intValue(kwargs.getOrDefault("cell_index", 0));
        // 提取新的源代码内容，默认为空字符串
        String newSource = str(kwargs.getOrDefault("new_source", ""));
        // 提取单元格类型，默认为 code
        String cellType = str(kwargs.getOrDefault("cell_type", "code"));
        // 提取编辑模式，默认为 replace
        String editMode = str(kwargs.getOrDefault("edit_mode", "replace"));

        // 参数校验：检查路径是否为空
        if (path.isBlank()) return "错误：必须提供 path";
        // 参数校验：检查文件扩展名是否为 .ipynb
        if (!path.endsWith(".ipynb")) {
            return "错误：notebook_edit 仅支持 .ipynb 文件。其它文件请使用 edit_file。";
        }
        // 参数校验：检查编辑模式是否合法
        if (!VALID_EDIT_MODES.contains(editMode)) {
            return "错误：edit_mode 无效：'" + editMode + "'。可选值：replace、insert、delete。";
        }
        // 参数校验：检查单元格类型是否合法
        if (!VALID_CELL_TYPES.contains(cellType)) {
            return "错误：cell_type 无效：'" + cellType + "'。可选值：code、markdown。";
        }

        // 解析文件路径，结合工作空间根路径
        Path fp = resolve(path);

        // 文件不存在时，仅 insert 模式允许自动创建新 Notebook
        if (!Files.exists(fp)) {
            // 如果不是插入模式，则报错
            if (!"insert".equals(editMode)) {
                return "错误：文件不存在：" + path;
            }
            // 创建一个空的 Notebook 结构
            Map<String, Object> nb = makeEmptyNotebook();
            // 获取 cells 列表并强制转换类型
            List<Map<String, Object>> cells = castCells(nb.get("cells"));
            // 创建新单元格并添加到列表中
            cells.add(newCell(newSource, cellType, true));
            // 更新 Notebook 中的 cells
            nb.put("cells", cells);
            // 创建父目录（如果不存在）
            Files.createDirectories(fp.getParent());
            // 将 Notebook 对象序列化为 JSON 并写入文件
            Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
            // 返回成功消息
            return "创建成功：" + fp + "（包含 1 个单元格）";
        }

        // 读取并解析现有 Notebook 文件
        Map<String, Object> nb;
        try {
            // 读取文件内容并反序列化为 Map 对象
            nb = MAPPER.readValue(Files.readString(fp), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // 捕获解析异常并返回错误信息
            return "错误：解析 Notebook 失败：" + e.getMessage();
        }

        // 获取 cells 列表，若不存在则初始化为空列表
        List<Map<String, Object>> cells = castCells(nb.getOrDefault("cells", new ArrayList<>()));
        // 获取 nbformat 主版本号
        int nbformat = intValue(nb.getOrDefault("nbformat", 0));
        // 获取 nbformat 次版本号
        int nbformatMinor = intValue(nb.getOrDefault("nbformat_minor", 0));
        // 判断是否需要生成单元格 ID (Jupyter Notebook format >= 4.5)
        boolean generateId = nbformat >= 4 && nbformatMinor >= 5;

        // 处理删除模式
        if ("delete".equals(editMode)) {
            // 检查单元格索引是否越界
            if (cellIndex < 0 || cellIndex >= cells.size()) {
                return "错误：cell_index " + cellIndex + " 超出范围（Notebook 共有 " + cells.size() + " 个单元格）";
            }
            // 从列表中移除指定索引的单元格
            cells.remove(cellIndex);
            // 更新 Notebook 中的 cells
            nb.put("cells", cells);
            // 将修改后的 Notebook 写回文件
            Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
            // 返回成功消息
            return "删除成功：已从 " + fp + " 删除单元格 " + cellIndex;
        }

        // 处理插入模式
        if ("insert".equals(editMode)) {
            // 计算插入位置，确保不超过列表大小
            int insertAt = Math.min(cellIndex + 1, cells.size());
            // 创建新单元格并插入到指定位置
            cells.add(insertAt, newCell(newSource, cellType, generateId));
            // 更新 Notebook 中的 cells
            nb.put("cells", cells);
            // 将修改后的 Notebook 写回文件
            Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
            // 返回成功消息
            return "插入成功：已在 " + fp + " 的索引 " + insertAt + " 插入单元格";
        }

        // 处理替换模式 (默认)
        // 检查单元格索引是否越界
        if (cellIndex < 0 || cellIndex >= cells.size()) {
            return "错误：cell_index " + cellIndex + " 超出范围（Notebook 共有 " + cells.size() + " 个单元格）";
        }

        // 获取指定索引的单元格对象
        Map<String, Object> cell = cells.get(cellIndex);
        // 更新单元格的源代码
        cell.put("source", newSource);

        // 如果单元格类型发生变化，调整相关字段
        String oldType = String.valueOf(cell.getOrDefault("cell_type", ""));
        if (!oldType.equals(cellType)) {
            // 更新单元格类型
            cell.put("cell_type", cellType);
            if ("code".equals(cellType)) {
                // 如果是代码单元格，确保存在 outputs 和 execution_count 字段
                cell.putIfAbsent("outputs", new ArrayList<>());
                cell.putIfAbsent("execution_count", null);
            } else {
                // 如果是 Markdown 单元格，移除 outputs 和 execution_count 字段
                cell.remove("outputs");
                cell.remove("execution_count");
            }
        }

        // 更新 Notebook 中的 cells
        nb.put("cells", cells);
        // 将修改后的 Notebook 写回文件
        Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
        // 返回成功消息
        return "编辑成功：已在 " + fp + " 中更新单元格 " + cellIndex;
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
        // 创建 LinkedHashMap 以保持键的顺序
        Map<String, Object> cell = new LinkedHashMap<>();
        // 设置单元格类型
        cell.put("cell_type", cellType);
        // 设置源代码内容
        cell.put("source", source);
        // 初始化空的 metadata 对象
        cell.put("metadata", new LinkedHashMap<>());
        if ("code".equals(cellType)) {
            // 代码单元格需要初始化 outputs 列表
            cell.put("outputs", new ArrayList<>());
            // 代码单元格需要初始化 execution_count 为 null
            cell.put("execution_count", null);
        }
        if (generateId) {
            // 生成简短的唯一 ID (UUID 去横杠后取前8位)
            cell.put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        }
        // 返回构建好的单元格对象
        return cell;
    }

    /**
     * 创建一个空的 Jupyter Notebook 结构
     *
     * @return 表示空 Notebook 的 Map 对象
     */
    private static Map<String, Object> makeEmptyNotebook() {
        // 创建 LinkedHashMap 以保持键的顺序
        Map<String, Object> nb = new LinkedHashMap<>();
        // 设置 nbformat 主版本号为 4
        nb.put("nbformat", 4);
        // 设置 nbformat 次版本号为 5
        nb.put("nbformat_minor", 5);
        // 设置 metadata，包括内核规范和语言信息
        nb.put("metadata", Map.of(
                "kernelspec", Map.of("display_name", "Python 3", "language", "python", "name", "python3"),
                "language_info", Map.of("name", "python")
        ));
        // 初始化空的 cells 列表
        nb.put("cells", new ArrayList<>());
        // 返回构建好的 Notebook 对象
        return nb;
    }

    /**
     * 安全地将对象转换为单元格列表
     *
     * @param cellsObj 原始对象
     * @return 单元格列表
     */
    private static List<Map<String, Object>> castCells(Object cellsObj) {
        List<Map<String, Object>> cells = new ArrayList<>();
        if (!(cellsObj instanceof List<?> list)) {
            return cells;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                cells.add(copyObjectMap(raw));
            }
        }
        return cells;
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    /**
     * 将对象转换为字符串，null 值返回空字符串
     *
     * @param o 输入对象
     * @return 字符串表示
     */
    private static String str(Object o) {
        // 如果对象为 null 返回空字符串，否则返回其字符串表示
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 将对象转换为整数
     *
     * @param o 输入对象 (Number 或可解析为整数的字符串)
     * @return 整数值
     */
    private static int intValue(Object o) {
        // 如果是 Number 类型直接转换，否则尝试解析字符串
        return o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o));
    }
}
