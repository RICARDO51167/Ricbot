package ricbot.domain.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreStructuredTest {

    @Test
    void mergeMemoryEntries_dedupesAndRebuildsMarkdownViews(@TempDir Path workspace) throws Exception {
        MemoryStore store = new MemoryStore(workspace);

        store.mergeMemoryEntries(List.of(
                new MemoryEntry()
                        .setType(MemoryEntry.TYPE_PREFERENCE)
                        .setScope(MemoryEntry.SCOPE_LONG_TERM)
                        .setSummary("用户喜欢简洁回答")
                        .setDetails("默认偏好简洁")
                        .setImportance(0.9d)
                        .setConfidence(0.9d),
                new MemoryEntry()
                        .setType(MemoryEntry.TYPE_PREFERENCE)
                        .setScope(MemoryEntry.SCOPE_LONG_TERM)
                        .setSummary("用户喜欢简洁回答")
                        .setDetails("重复条目")
                        .setImportance(0.7d)
                        .setConfidence(0.8d),
                new MemoryEntry()
                        .setType(MemoryEntry.TYPE_PROJECT)
                        .setScope(MemoryEntry.SCOPE_LONG_TERM)
                        .setSummary("项目使用 Java 17")
                        .setDetails("构建基于 Maven")
                        .setImportance(0.8d)
                        .setConfidence(0.9d)
        ));

        List<MemoryEntry> entries = store.readMemoryEntries();
        assertEquals(2, entries.size());
        assertTrue(store.readUser().contains("用户喜欢简洁回答"));
        assertTrue(store.readMemory().contains("项目使用 Java 17"));
        assertTrue(Files.exists(workspace.resolve("memory").resolve("memory_entries.jsonl")));
    }
}
