package ricbot.tool.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskAnalyzer;
import ricbot.domain.security.PendingToolCall;
import ricbot.tool.filesystem.EditFileTool;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.filesystem.WriteFileTool;
import ricbot.tool.process.ExecTool;
import ricbot.tool.api.Tool.ToolExecutionContext;
import ricbot.tool.search.GlobTool;
import ricbot.tool.search.GrepTool;
import ricbot.domain.skill.SkillsLoader;
import ricbot.tool.skill.ReadSkillTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void registryDoesNotExposeMissingGoalUpdateToolByDefault() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(namedTool("read_file"));

        String missingTool = missingGoalUpdateToolName();

        assertNull(registry.get(missingTool));
        assertFalse(registry.toolNames().contains(missingTool));
        assertTrue(String.valueOf(registry.execute(missingTool, Map.of())).startsWith("Error: Tool '" + missingTool + "' not found."));
    }

    private static String missingGoalUpdateToolName() {
        return new String(new char[]{'u', 'p', 'd', 'a', 't', 'e', '_', 'g', 'o', 'a', 'l'});
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
    void genericMapExecuteTool_usesDefaultToolContract() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String getName() {
                return "echo_map";
            }

            @Override
            public String getDescription() {
                return "echo";
            }

            @Override
            public Object execute(Map<String, Object> params) {
                return "value=" + params.get("value");
            }
        });

        Object out = registry.execute("echo_map", Map.of("value", "ok"));
        assertEquals("value=ok", out);
    }

    @Test
    void executionContextSeparatesApprovalFromBusinessParams() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String getName() {
                return "context_probe";
            }

            @Override
            public String getDescription() {
                return "context";
            }

            @Override
            public Object execute(Map<String, Object> params) {
                return "approved=" + ToolExecutionContext.current().approved()
                        + ",approvalId=" + ToolExecutionContext.current().approvalId()
                        + ",hasBypass=" + params.containsKey("__approval_bypass");
            }
        });

        Object normal = registry.execute("context_probe", Map.of("__approval_bypass", true));
        assertEquals("approved=false,approvalId=,hasBypass=false", normal);

        Object approved = registry.executeApproved("context_probe", Map.of("__approval_bypass", true), "approval_test");
        assertEquals("approved=true,approvalId=approval_test,hasBypass=false", approved);
    }

    @Test
    void approvalBypassParamCannotBypassRiskGateButApprovedContextCan(@TempDir Path workspace) throws Exception {
        ApprovalService approvalService = new ApprovalService();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WriteFileTool(workspace, workspace, new CommandRiskAnalyzer(workspace), approvalService));

        Object malicious = registry.execute("write_file", Map.of(
                "path", "reports/malicious.txt",
                "content", "no\n",
                "__approval_bypass", true
        ));

        assertTrue(String.valueOf(malicious).contains("需要审批后才能执行"), String.valueOf(malicious));
        assertFalse(Files.exists(workspace.resolve("reports").resolve("malicious.txt")));

        String requestId = requestId(String.valueOf(malicious));
        approvalService.approve(requestId);
        PendingToolCall call = approvalService.consumeApprovedToolCall(requestId);
        Object approved = registry.executeApproved(call.toolName(), call.arguments(), requestId);

        assertFalse(String.valueOf(approved).contains("需要审批后才能执行"), String.valueOf(approved));
        assertEquals("no\n", Files.readString(workspace.resolve("reports").resolve("malicious.txt")));
    }

    @Test
    void directToolExecuteUsesContextForApprovalInsteadOfBypassParam(@TempDir Path workspace) throws Exception {
        ApprovalService approvalService = new ApprovalService();
        WriteFileTool write = new WriteFileTool(workspace, workspace, new CommandRiskAnalyzer(workspace), approvalService);

        Object normal = write.execute(Map.of(
                "path", "reports/direct.txt",
                "content", "blocked\n",
                "__approval_bypass", true
        ), ToolExecutionContext.normal());

        assertTrue(String.valueOf(normal).contains("需要审批后才能执行"), String.valueOf(normal));
        assertFalse(Files.exists(workspace.resolve("reports").resolve("direct.txt")));

        Object approved = write.execute(Map.of(
                "path", "reports/direct.txt",
                "content", "approved\n",
                "__approval_bypass", true
        ), ToolExecutionContext.approved("approval_direct_write"));

        assertFalse(String.valueOf(approved).contains("需要审批后才能执行"), String.valueOf(approved));
        assertEquals("approved\n", Files.readString(workspace.resolve("reports").resolve("direct.txt")));
    }

    @Test
    void editFileAndExecUseApprovedContext(@TempDir Path workspace) throws Exception {
        Path notes = workspace.resolve("notes.txt");
        Files.writeString(notes, "hello world\n");
        ricbot.tool.filesystem.FileReadState.recordRead(notes, 1, 10);

        ApprovalService approvalService = new ApprovalService();
        EditFileTool edit = new EditFileTool(workspace, workspace, new CommandRiskAnalyzer(workspace), approvalService);
        Object editResult = edit.execute(Map.of(
                "path", "notes.txt",
                "old_text", "world",
                "new_text", "ricbot",
                "replace_all", false
        ), ToolExecutionContext.approved("approval_direct_edit"));

        assertFalse(String.valueOf(editResult).contains("需要审批后才能执行"), String.valueOf(editResult));
        assertEquals("hello ricbot\n", Files.readString(notes));

        ExecTool exec = new ExecTool(
                5,
                workspace.toString(),
                List.of(),
                null,
                true,
                "",
                "",
                List.of(),
                new CommandRiskAnalyzer(workspace),
                approvalService
        );
        Object execResult = exec.execute(Map.of(
                "command", "touch direct-exec.txt",
                "__approval_bypass", true
        ), ToolExecutionContext.approved("approval_direct_exec"));

        assertFalse(String.valueOf(execResult).contains("需要审批后才能执行"), String.valueOf(execResult));
        assertTrue(Files.exists(workspace.resolve("direct-exec.txt")));
    }

    @Test
    void readSkillTool_returnsFullSkillDocument(@TempDir Path workspace) throws Exception {
        Path skillDir = workspace.resolve("skills").resolve("demo");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: Demo skill
                version: 1.2.3
                permissions: read, write
                tools: read_file, write_file
                ---
                Demo body.
                """);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadSkillTool(new SkillsLoader(workspace, null, Set.of())));

        Object out = registry.execute("read_skill", Map.of("name", "demo"));

        assertTrue(String.valueOf(out).contains("# Skill: demo"), String.valueOf(out));
        assertTrue(String.valueOf(out).contains("version: 1.2.3"), String.valueOf(out));
        assertTrue(String.valueOf(out).contains("risk: elevated"), String.valueOf(out));
        assertTrue(String.valueOf(out).contains("permissions: read, write"), String.valueOf(out));
        assertTrue(String.valueOf(out).contains("Demo body."), String.valueOf(out));
    }

    @Test
    void readSkillTool_supportsSectionAndChunkReads(@TempDir Path workspace) throws Exception {
        Path skillDir = workspace.resolve("skills").resolve("demo");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: Demo skill
                ---
                # Demo

                Intro.

                ## Usage

                First line.
                Second line.

                ## Examples

                Example body.
                """);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadSkillTool(new SkillsLoader(workspace, null, Set.of())));

        Object out = registry.execute("read_skill", Map.of("name", "demo", "section", "Usage", "max_chars", 18));
        String text = String.valueOf(out);

        assertTrue(text.contains("section: Usage"), text);
        assertTrue(text.contains("truncated: true"), text);
        assertTrue(text.contains("## Usage"), text);
        assertFalse(text.contains("## Examples"), text);
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

    private static String requestId(String text) {
        for (String line : text.split("\\R")) {
            if (line.startsWith("requestId:")) {
                return line.substring("requestId:".length()).trim();
            }
        }
        throw new AssertionError("requestId not found in: " + text);
    }

    private static String schemaName(Map<String, Object> schema) {
        Object function = schema.get("function");
        if (function instanceof Map<?, ?> fn) {
            Object name = fn.get("name");
            if (name instanceof String s) {
                return s;
            }
        }
        return "";
    }
}
