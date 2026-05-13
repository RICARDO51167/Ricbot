package ricbot.tool.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertEquals("harness report complete\n", Files.readString(workspace.resolve("reports").resolve("summary.txt")));
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
}
