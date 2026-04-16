package ricbot.tool.filesystem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * NotebookEditTool: 专门用于编辑 Jupyter Notebook (.ipynb) 文件的工具类。
 */
public class NotebookEditTool extends FsTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    private static final Set<String> VALID_CELL_TYPES = Set.of("code", "markdown");
    
    private static final Set<String> VALID_EDIT_MODES = Set.of("replace", "insert", "delete");

    public NotebookEditTool(Path workspace, Path allowedDir, List<Path> extraAllowedDirs) {
        super(workspace, allowedDir, extraAllowedDirs);
    }

    @Override
    public String getName() {
        return "notebook_edit";
    }

    @Override
    public String getDescription() {
        return "编辑 Jupyter Notebook（.ipynb）中的单元格。";
    }

    @Override
    public Object execute(Map<String, Object> kwargs) throws Exception {
        String path = str(kwargs.get("path"));
        int cellIndex = intValue(kwargs.getOrDefault("cell_index", 0));
        String newSource = str(kwargs.getOrDefault("new_source", ""));
        String cellType = str(kwargs.getOrDefault("cell_type", "code"));
        String editMode = str(kwargs.getOrDefault("edit_mode", "replace"));

        if (path.isBlank()) return "错误：必须提供 path";
        if (!path.endsWith(".ipynb")) {
            return "错误：notebook_edit 仅支持 .ipynb 文件。其它文件请使用 edit_file。";
        }
        if (!VALID_EDIT_MODES.contains(editMode)) {
            return "错误：edit_mode 无效：'" + editMode + "'。可选值：replace、insert、delete。";
        }
        if (!VALID_CELL_TYPES.contains(cellType)) {
            return "错误：cell_type 无效：'" + cellType + "'。可选值：code、markdown。";
        }

        Path fp = resolve(path);

        if (!Files.exists(fp)) {
            if (!"insert".equals(editMode)) {
                return "错误：文件不存在：" + path;
            }
            Map<String, Object> nb = makeEmptyNotebook();
            List<Map<String, Object>> cells = castCells(nb.get("cells"));
            cells.add(newCell(newSource, cellType, true));
            nb.put("cells", cells);
            Files.createDirectories(fp.getParent());
            Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
            return "创建成功：" + fp + "（包含 1 个单元格）";
        }

        Map<String, Object> nb;
        try {
            nb = MAPPER.readValue(Files.readString(fp), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return "错误：解析 Notebook 失败：" + e.getMessage();
        }

        List<Map<String, Object>> cells = castCells(nb.getOrDefault("cells", new ArrayList<>()));
        int nbformat = intValue(nb.getOrDefault("nbformat", 0));
        int nbformatMinor = intValue(nb.getOrDefault("nbformat_minor", 0));
        boolean generateId = nbformat >= 4 && nbformatMinor >= 5;

        if ("delete".equals(editMode)) {
            if (cellIndex < 0 || cellIndex >= cells.size()) {
                return "错误：cell_index " + cellIndex + " 超出范围（Notebook 共有 " + cells.size() + " 个单元格）";
            }
            cells.remove(cellIndex);
            nb.put("cells", cells);
            Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
            return "删除成功：已从 " + fp + " 删除单元格 " + cellIndex;
        }

        if ("insert".equals(editMode)) {
            int insertAt = Math.min(cellIndex + 1, cells.size());
            cells.add(insertAt, newCell(newSource, cellType, generateId));
            nb.put("cells", cells);
            Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
            return "插入成功：已在 " + fp + " 的索引 " + insertAt + " 插入单元格";
        }

        if (cellIndex < 0 || cellIndex >= cells.size()) {
            return "错误：cell_index " + cellIndex + " 超出范围（Notebook 共有 " + cells.size() + " 个单元格）";
        }

        Map<String, Object> cell = cells.get(cellIndex);
        cell.put("source", newSource);

        String oldType = String.valueOf(cell.getOrDefault("cell_type", ""));
        if (!oldType.equals(cellType)) {
            cell.put("cell_type", cellType);
            if ("code".equals(cellType)) {
                cell.putIfAbsent("outputs", new ArrayList<>());
                cell.putIfAbsent("execution_count", null);
            } else {
                cell.remove("outputs");
                cell.remove("execution_count");
            }
        }

        nb.put("cells", cells);
        Files.writeString(fp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nb));
        return "编辑成功：已在 " + fp + " 中更新单元格 " + cellIndex;
    }

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
            cell.put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        }
        return cell;
    }

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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castCells(Object cellsObj) {
        return (List<Map<String, Object>>) cellsObj;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intValue(Object o) {
        return o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o));
    }
}
