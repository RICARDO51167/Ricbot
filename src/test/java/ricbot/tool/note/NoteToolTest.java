package ricbot.tool.note;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.tool.api.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NoteToolTest {

    @Test
    void noteTool_createsSearchesPromotesAndArchivesNotes(@TempDir Path workspace) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new NoteTool(workspace));

        Object created = registry.execute("note", Map.of(
                "action", "create",
                "title", "Context upgrade decision",
                "category", "temporary",
                "type", "decision",
                "content", "Use GSSC context pipeline and quality report.",
                "tags", List.of("context", "v2")
        ));

        String createdText = String.valueOf(created);
        assertTrue(createdText.contains("note created"), createdText);
        assertTrue(Files.exists(workspace.resolve("notes").resolve("index.json")));

        Object searched = registry.execute("note", Map.of(
                "action", "search",
                "query", "GSSC quality",
                "limit", 5
        ));
        assertTrue(String.valueOf(searched).contains("Context upgrade decision"), String.valueOf(searched));

        String id = createdText.substring(createdText.indexOf("id=") + 3, createdText.indexOf(", title="));
        Object promoted = registry.execute("note", Map.of(
                "action", "promote",
                "id", id,
                "category", "project"
        ));
        assertTrue(String.valueOf(promoted).contains("project"), String.valueOf(promoted));

        Object archived = registry.execute("note", Map.of("action", "archive", "id", id));
        assertTrue(String.valueOf(archived).contains("archived=true"), String.valueOf(archived));
    }
}
