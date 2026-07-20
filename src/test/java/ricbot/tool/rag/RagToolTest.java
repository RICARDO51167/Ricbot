package ricbot.tool.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagToolTest {

    @Test
    void ragTool_indexesWorkspaceAndSearchesCodeDocsAndSymbols(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/demo"));
        Files.writeString(workspace.resolve("src/main/java/demo/ToolRegistry.java"), """
                package demo;

                public class ToolRegistry {
                    public void registerTool(String name) {
                    }
                }
                """);
        Files.writeString(workspace.resolve("README.md"), "Ricbot uses GSSC context engineering and NoteTool.\n");
        Files.createDirectories(workspace.resolve("notes/project"));
        Files.writeString(workspace.resolve("notes/project/decisions.md"), "Decision: workspace RAG indexes notes and docs.\n");

        ToolRegistry registry = new ToolRegistry();
        registry.register(new RagTool(workspace));

        Object indexed = registry.execute("rag", Map.of("action", "index_workspace"));
        assertTrue(String.valueOf(indexed).contains("rag index updated"), String.valueOf(indexed));
        assertTrue(Files.exists(workspace.resolve(".rag").resolve("code_index").resolve("file_chunks.jsonl")));

        Object code = registry.execute("rag", Map.of(
                "action", "search_code",
                "query", "registerTool",
                "limit", 5
        ));
        assertTrue(String.valueOf(code).contains("ToolRegistry.java"), String.valueOf(code));

        Object docs = registry.execute("rag", Map.of(
                "action", "search_docs",
                "query", "GSSC NoteTool",
                "limit", 5
        ));
        assertTrue(String.valueOf(docs).contains("README.md"), String.valueOf(docs));

        Object symbol = registry.execute("rag", Map.of(
                "action", "explain_symbol",
                "symbol", "ToolRegistry"
        ));
        assertTrue(String.valueOf(symbol).contains("ToolRegistry.java"), String.valueOf(symbol));
    }
}
