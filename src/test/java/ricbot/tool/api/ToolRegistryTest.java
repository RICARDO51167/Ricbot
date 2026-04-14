package ricbot.tool.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.tool.filesystem.ReadFileTool;
import ricbot.tool.filesystem.ListDirTool;
import ricbot.tool.process.ExecTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ToolRegistryTest {

    @Test
    void registerLookupAndExecute_withValidation(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello");

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(workspace, workspace, List.of()));
        registry.register(new ListDirTool(workspace, workspace));
        registry.register(new ExecTool(5, workspace.toString(), null, null, true, "", "", List.of()));

        assertTrue(registry.has("list_dir"));
        assertNotNull(registry.get("read_file"));

        Object bad = registry.execute("read_file", Map.of());
        assertTrue(String.valueOf(bad).startsWith("Error: Invalid parameters"), String.valueOf(bad));

        Object listed = registry.execute("list_dir", Map.of("path", "."));
        assertTrue(String.valueOf(listed).contains("a.txt"), String.valueOf(listed));

        Object read = registry.execute("read_file", Map.of("path", "a.txt", "offset", 1, "limit", 20));
        assertTrue(String.valueOf(read).contains("hello"), String.valueOf(read));

        Object execBad = registry.execute("exec", Map.of());
        assertTrue(String.valueOf(execBad).startsWith("Error: Invalid parameters"), String.valueOf(execBad));
    }

    @Test
    void unknownTool_returnsHelpfulMessage() {
        ToolRegistry registry = new ToolRegistry();
        Object out = registry.execute("missing_tool", Map.of());
        assertTrue(String.valueOf(out).startsWith("Error: Tool 'missing_tool' not found."), String.valueOf(out));
    }
}
