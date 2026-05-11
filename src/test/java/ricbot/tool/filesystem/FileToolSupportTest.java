package ricbot.tool.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
