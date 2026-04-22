package ricbot.tool.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.process.ExecTool;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ToolRegistryTest {

    @Test
    // 测试注册、查找和执行工具，并验证参数校验逻辑
    void registerLookupAndExecute_withValidation(@TempDir Path workspace) throws Exception {
        // 在临时工作区创建测试文件 a.txt，内容为 "hello"
        Files.writeString(workspace.resolve("a.txt"), "hello");
        // 创建 src 目录
        Files.createDirectories(workspace.resolve("src"));
        // 在 src 目录下创建 A.java 文件，包含一个简单的类定义
        Files.writeString(workspace.resolve("src").resolve("A.java"), "public class A { }\n");

        // 初始化工具注册表
        ToolRegistry registry = new ToolRegistry();
        // 注册读取文件工具
        registry.register(new ReadFileTool(workspace, workspace, List.of()));
        // 注册列出目录工具
        registry.register(new ListDirTool(workspace, workspace));
        // 注册写入文件工具
        registry.register(new WriteFileTool(workspace, workspace));
        // 注册编辑文件工具
        registry.register(new EditFileTool(workspace, workspace));
        // 注册全局匹配工具
        registry.register(new GlobTool(workspace, workspace));
        // 注册搜索工具
        registry.register(new GrepTool(workspace, workspace));
        // 注册执行命令工具
        registry.register(new ExecTool(5, workspace.toString(), null, null, true, "", "", List.of()));

        // 断言可以获取到 "read_file" 工具实例
        assertNotNull(registry.get("read_file"));

        // 尝试执行 "read_file" 但不提供必要参数，预期返回错误信息
        Object bad = registry.execute("read_file", Map.of());
        assertTrue(String.valueOf(bad).startsWith("Error: Invalid parameters"), String.valueOf(bad));

        // 尝试执行 "grep" 但传入非 Map 类型的参数，预期返回错误信息
        Object notMap = registry.execute("grep", "hello");
        assertTrue(String.valueOf(notMap).startsWith("Error: Tool 'grep' parameters must be a JSON object"), String.valueOf(notMap));

        // 执行 "list_dir" 列出当前目录，预期结果包含 "a.txt"
        Object listed = registry.execute("list_dir", Map.of("path", "."));
        assertTrue(String.valueOf(listed).contains("a.txt"), String.valueOf(listed));

        // 执行 "read_file" 读取 a.txt，预期结果包含 "hello"
        Object read = registry.execute("read_file", Map.of("path", "a.txt", "offset", 1, "limit", 20));
        assertTrue(String.valueOf(read).contains("hello"), String.valueOf(read));

        // 执行 "glob" 查找所有 Java 文件，预期结果包含 "A.java"
        Object glob = registry.execute("glob", Map.of("pattern", "**/*.java", "base_dir", "."));
        assertTrue(String.valueOf(glob).contains("A.java"), String.valueOf(glob));

        // 执行 "grep" 搜索类定义，预期结果包含 "A.java"
        Object grep = registry.execute("grep", Map.of(
                "pattern", "class\\s+A",
                "base_dir", ".",
                "file_glob", "**/*.java",
                "ignore_case", false,
                "max_results", 20
        ));
        assertTrue(String.valueOf(grep).contains("A.java"), String.valueOf(grep));

        // 执行 "write_file" 写入新文件 b.txt，预期结果包含文件名
        Object write = registry.execute("write_file", Map.of("path", "b.txt", "content", "hello world"));
        assertTrue(String.valueOf(write).contains("b.txt"), String.valueOf(write));

        // 再次读取 b.txt 以确认内容（虽然这里没有断言，但是为了后续编辑做准备）
        registry.execute("read_file", Map.of("path", "b.txt", "offset", 1, "limit", 20));
        // 执行 "edit_file" 将 b.txt 中的 "world" 替换为 "ricbot"
        Object edit = registry.execute("edit_file", Map.of(
                "path", "b.txt",
                "old_text", "world",
                "new_text", "ricbot",
                "replace_all", false
        ));
        // 断言编辑操作成功
        assertTrue(String.valueOf(edit).toLowerCase().contains("success"), String.valueOf(edit));

        // 再次读取 b.txt，预期内容变为 "hello ricbot"
        Object read2 = registry.execute("read_file", Map.of("path", "b.txt", "offset", 1, "limit", 20));
        assertTrue(String.valueOf(read2).contains("hello ricbot"), String.valueOf(read2));

        // 尝试执行 "exec" 但不提供必要参数，预期返回错误信息
        Object execBad = registry.execute("exec", Map.of());
        assertTrue(String.valueOf(execBad).startsWith("Error: Invalid parameters"), String.valueOf(execBad));
    }

    @Test
    // 测试当请求不存在的工具时，返回友好的错误消息
    void unknownTool_returnsHelpfulMessage() {
        // 初始化工具注册表
        ToolRegistry registry = new ToolRegistry();
        // 尝试执行一个不存在的工具 "missing_tool"
        Object out = registry.execute("missing_tool", Map.of());
        // 断言返回的错误消息指出工具未找到
        assertTrue(String.valueOf(out).startsWith("Error: Tool 'missing_tool' not found."), String.valueOf(out));
    }

    @Test
    void toolNames_areStableAndSorted() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(namedTool("zeta"));
        registry.register(namedTool("alpha"));
        registry.register(namedTool("mcp_demo_echo"));

        assertEquals(List.of("alpha", "mcp_demo_echo", "zeta"), registry.toolNames());
        List<Map<String, Object>> definitions = registry.getDefinitions();
        assertEquals("alpha", schemaName(definitions.get(0)));
        assertEquals("zeta", schemaName(definitions.get(1)));
        assertEquals("mcp_demo_echo", schemaName(definitions.get(2)));
    }

    @Test
    void filesystemTools_rejectSymlinkEscapes(@TempDir Path workspace) throws Exception {
        Path outsideDir = workspace.resolveSibling("outside");
        Files.createDirectories(outsideDir);
        Path outsideFile = outsideDir.resolve("secret.txt");
        Files.writeString(outsideFile, "top-secret");

        Path readLink = workspace.resolve("read-link.txt");
        Path writeLinkDir = workspace.resolve("write-link-dir");
        try {
            Files.createSymbolicLink(readLink, outsideFile);
            Files.createSymbolicLink(writeLinkDir, outsideDir);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            Assumptions.assumeTrue(false, "当前环境不支持创建符号链接: " + e.getMessage());
            return;
        }

        ReadFileTool readTool = new ReadFileTool(workspace, workspace, List.of());
        WriteFileTool writeTool = new WriteFileTool(workspace, workspace);
        EditFileTool editTool = new EditFileTool(workspace, workspace);

        String readResult = readTool.execute("read-link.txt", 1, 20);
        assertTrue(readResult.startsWith("错误："), readResult);

        String writeResult = writeTool.execute("write-link-dir/new.txt", "escaped");
        assertTrue(writeResult.startsWith("错误："), writeResult);
        assertFalse(Files.exists(outsideDir.resolve("new.txt")));

        String editResult = editTool.execute("read-link.txt", "top-secret", "changed", false);
        assertTrue(editResult.startsWith("错误："), editResult);
        assertEquals("top-secret", Files.readString(outsideFile));
    }

    private static Tool namedTool(String name) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "test";
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static String schemaName(Map<String, Object> schema) {
        Object function = schema.get("function");
        if (function instanceof Map<?, ?> fn) {
            Object name = ((Map<String, Object>) fn).get("name");
            if (name instanceof String s) {
                return s;
            }
        }
        return "";
    }
}
