package ricbot.tool.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuiltinToolRegistrarTest {

    @Test
    void coreToolsAreRegistered(@TempDir Path workspace) {
        ToolRegistry core = new ToolRegistry();
        BuiltinToolRegistrar.registerCoreFileAndSearchTools(core, workspace, workspace, null);

        assertEquals(
                Set.of("read_file", "list_dir", "write_file", "edit_file", "glob", "grep"),
                Set.copyOf(core.toolNames())
        );
    }

    @Test
    void compatibilityRegistrarComposesCorePack(@TempDir Path workspace) {
        ToolRegistry registry = new ToolRegistry();
        BuiltinToolRegistrar.registerFileAndSearchTools(registry, workspace, workspace);

        assertEquals(
                Set.of("read_file", "list_dir", "write_file", "edit_file", "glob", "grep"),
                Set.copyOf(registry.toolNames())
        );
    }
}
