package ricbot.tool.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.security.ApprovalService;
import ricbot.domain.security.CommandRiskAnalyzer;
import ricbot.domain.security.PendingToolCall;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolSupportTest {

    @Test
    void readFile_rejectsOversizedTextFiles(@TempDir Path workspace) throws Exception {
        Path large = workspace.resolve("large.txt");
        Files.writeString(large, "a".repeat((int) FileToolSupport.MAX_TEXT_FILE_BYTES + 1));

        ReadFileTool tool = new ReadFileTool(workspace, workspace, List.of());
        String result = tool.execute("large.txt", 1, 10);

        assertTrue(result.contains("文件超过文本工具大小限制"), result);
    }

    @Test
    void writeFile_rejectsOversizedContent(@TempDir Path workspace) {
        WriteFileTool tool = new WriteFileTool(workspace, workspace);
        String content = "a".repeat((int) FileToolSupport.MAX_TEXT_FILE_BYTES + 1);

        String result = tool.execute("large.txt", content);

        assertTrue(result.contains("写入内容超过文本工具大小限制"), result);
    }

    @Test
    void writeFile_allowsNewNestedFileInsideWorkspace(@TempDir Path workspace) throws Exception {
        WriteFileTool tool = new WriteFileTool(workspace, workspace);

        String result = tool.execute("reports/summary.txt", "harness report complete\n");

        assertTrue(result.contains("文件已写入"), result);
        assertTrue(result.contains("DiffReview"), result);
        assertTrue(result.contains("summary:"), result);
        assertTrue(result.contains("addedLines"), result);
        assertTrue(result.contains("rollbackHint: rm reports/summary.txt"), result);
        assertEquals("harness report complete\n", Files.readString(workspace.resolve("reports").resolve("summary.txt")));
    }

    @Test
    void writeAndEditCanRequireApprovalWhenRiskGateEnabled(@TempDir Path workspace) throws Exception {
        ApprovalService approvalService = new ApprovalService();
        CommandRiskAnalyzer analyzer = new CommandRiskAnalyzer(workspace);
        WriteFileTool write = new WriteFileTool(workspace, workspace, analyzer, approvalService);

        String gated = write.execute("reports/summary.txt", "hello\n");
        assertTrue(gated.contains("需要审批后才能执行"), gated);
        assertTrue(gated.contains("riskLevel: MEDIUM"), gated);

        WriteFileTool plainWrite = new WriteFileTool(workspace, workspace);
        plainWrite.execute("reports/summary.txt", "hello world\n");
        Path file = workspace.resolve("reports").resolve("summary.txt");
        FileReadState.recordRead(file, 1, 10);
        EditFileTool edit = new EditFileTool(workspace, workspace, analyzer, approvalService);
        String editGated = edit.execute("reports/summary.txt", "world", "ricbot", false);
        assertTrue(editGated.contains("需要审批后才能执行"), editGated);
    }

    @Test
    void writeFileApprovalCanBeRestoredWithDiffReview(@TempDir Path workspace) throws Exception {
        ApprovalService approvalService = new ApprovalService();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WriteFileTool(workspace, workspace, new CommandRiskAnalyzer(workspace), approvalService));

        String gated = String.valueOf(registry.execute("write_file", Map.of("path", "reports/summary.txt", "content", "hello\n")));
        String requestId = requestId(gated);
        assertTrue(Files.notExists(workspace.resolve("reports").resolve("summary.txt")));

        approvalService.approve(requestId);
        PendingToolCall call = approvalService.consumeApprovedToolCall(requestId);
        String result = String.valueOf(registry.executeApproved(call.toolName(), call.arguments()));

        assertTrue(result.contains("DiffReview"), result);
        assertTrue(result.contains("suspiciousChanges"), result);
        assertTrue(result.contains("suggestedTests"), result);
        assertTrue(result.contains("rollbackHint"), result);
        assertEquals("hello\n", Files.readString(workspace.resolve("reports").resolve("summary.txt")));
    }

    @Test
    void editFileApprovalCanBeRestoredWithDiffReview(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("notes.txt");
        Files.writeString(file, "hello world\n");
        FileReadState.recordRead(file, 1, 10);
        ApprovalService approvalService = new ApprovalService();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EditFileTool(workspace, workspace, new CommandRiskAnalyzer(workspace), approvalService));

        String gated = String.valueOf(registry.execute("edit_file", Map.of(
                "path", "notes.txt",
                "old_text", "world",
                "new_text", "ricbot",
                "replace_all", false
        )));
        String requestId = requestId(gated);
        assertEquals("hello world\n", Files.readString(file));

        approvalService.approve(requestId);
        PendingToolCall call = approvalService.consumeApprovedToolCall(requestId);
        String result = String.valueOf(registry.executeApproved(call.toolName(), call.arguments()));

        assertTrue(result.contains("DiffReview"), result);
        assertTrue(result.contains("summary:"), result);
        assertTrue(result.contains("rollbackHint: git checkout -- notes.txt"), result);
        assertEquals("hello ricbot\n", Files.readString(file));
    }

    @Test
    void readFile_readsRequestedSliceAndDeduplicatesWithoutFullTextRead(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("notes.txt");
        Files.writeString(file, "alpha\nbeta\ngamma\ndelta\n");

        ReadFileTool tool = new ReadFileTool(workspace, workspace, List.of());

        assertEquals("2: beta\n3: gamma", tool.execute("notes.txt", 2, 2));
        String second = tool.execute("notes.txt", 2, 2);

        assertTrue(second.startsWith("文件自上次读取后未发生变化。"), second);
        assertTrue(second.endsWith("2: beta\n3: gamma"), second);
    }

    @Test
    void readState_hashesFilesStreamingForEditValidation(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("hash.txt");
        Files.writeString(file, "a".repeat(256 * 1024));

        FileReadState.recordRead(file, 1, 1);

        assertEquals(null, FileReadState.checkRead(file));
    }

    private static String requestId(String text) {
        for (String line : text.split("\\R")) {
            if (line.startsWith("requestId:")) {
                return line.substring("requestId:".length()).trim();
            }
        }
        throw new AssertionError("missing requestId in: " + text);
    }
}
