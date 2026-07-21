package ricbot.domain.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreStructuredTest {

    @Test
    void constructorPreservesLegacyDreamData(@TempDir Path workspace) throws Exception {
        Path memoryDir = Files.createDirectories(workspace.resolve("memory"));
        Path legacyCursor = memoryDir.resolve(".dream_cursor");
        Path legacyAudit = memoryDir.resolve("dream_audit.jsonl");
        Files.writeString(legacyCursor, "17");
        Files.writeString(legacyAudit, "{\"legacy\":true}\n");

        new MemoryStore(workspace);

        assertEquals("17", Files.readString(legacyCursor));
        assertEquals("{\"legacy\":true}\n", Files.readString(legacyAudit));
    }

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
        assertTrue(entries.stream().allMatch(entry -> entry.getMemoryType() == MemoryType.SEMANTIC));
        assertTrue(store.readUser().contains("用户喜欢简洁回答"));
        assertTrue(store.readMemory().contains("项目使用 Java 17"));
        assertTrue(Files.exists(workspace.resolve("memory").resolve("memory_entries.jsonl")));
    }

    @Test
    void recallArchivedHistory_rendersReadableRawArchiveSummary(@TempDir Path workspace) {
        MemoryStore store = new MemoryStore(workspace);

        store.rawArchive(List.of(
                Map.of("role", "user", "content", "请继续修复 mcp 超时问题"),
                Map.of("role", "assistant", "content", "我会先检查 MCPAdapters 的调用链"),
                Map.of("role", "tool", "content", "call result")
        ));

        List<String> recalled = store.recallArchivedHistory("mcp 超时", 3);

        assertEquals(1, recalled.size());
        assertTrue(recalled.get(0).contains("archived session"), recalled.get(0));
        assertTrue(recalled.get(0).contains("user: 请继续修复 mcp 超时问题"), recalled.get(0));
        assertFalse(recalled.get(0).contains("messages_count"), recalled.get(0));
    }

    @Test
    void sessionSummariesAreTypedAndStillRecallable(@TempDir Path workspace) {
        MemoryStore store = new MemoryStore(workspace);

        store.appendSessionSummary("用户讨论过 MCP streamable HTTP 超时");

        List<String> recalled = store.recallArchivedHistory("MCP 超时", 3);
        assertEquals(1, recalled.size());
        assertTrue(recalled.get(0).contains("session summary"), recalled.get(0));
        assertTrue(recalled.get(0).contains("MCP streamable HTTP 超时"), recalled.get(0));
    }

    @Test
    void recallMemories_usesChineseNgramsAndFieldWeights(@TempDir Path workspace) {
        MemoryStore store = new MemoryStore(workspace);

        MemoryEntry target = new MemoryEntry()
                .setType(MemoryEntry.TYPE_PROJECT)
                .setScope(MemoryEntry.SCOPE_LONG_TERM)
                .setSummary("项目使用飞书审批流程")
                .setDetails("相关实现位于渠道集成模块")
                .setImportance(0.7d)
                .setConfidence(0.9d)
                .setTags(List.of("confirmed"));
        MemoryEntry unrelated = new MemoryEntry()
                .setType(MemoryEntry.TYPE_PROJECT)
                .setScope(MemoryEntry.SCOPE_LONG_TERM)
                .setSummary("项目使用 Java 17")
                .setDetails("构建基于 Maven")
                .setImportance(0.9d)
                .setConfidence(0.9d);
        store.mergeMemoryEntries(List.of(unrelated, target));

        List<MemoryEntry> recalled = store.recallMemories("继续做飞书审批", "", 1);

        assertEquals(1, recalled.size());
        assertEquals("项目使用飞书审批流程", recalled.get(0).getSummary());
        assertEquals(MemoryType.SEMANTIC, recalled.get(0).getMemoryType());
        assertEquals(1, store.readMemoryEntries().stream()
                .filter(entry -> "项目使用飞书审批流程".equals(entry.getSummary()))
                .findFirst()
                .orElseThrow()
                .getAccessCount());
    }

    @Test
    void appendMemoryCandidates_dedupesPendingCandidates(@TempDir Path workspace) {
        MemoryStore store = new MemoryStore(workspace);

        store.appendMemoryCandidates(List.of(
                new MemoryEntry()
                        .setType(MemoryEntry.TYPE_PREFERENCE)
                        .setSummary("用户偏好简短回答")
                        .setDetails("first")
                        .setImportance(0.6d),
                new MemoryEntry()
                        .setType(MemoryEntry.TYPE_PREFERENCE)
                        .setSummary("用户偏好简短回答")
                        .setDetails("second")
                        .setImportance(0.9d)
        ));

        List<MemoryEntry> candidates = store.readMemoryCandidates();
        assertEquals(1, candidates.size());
        assertEquals("用户偏好简短回答", candidates.get(0).getSummary());
        assertEquals(0.9d, candidates.get(0).getImportance(), 0.001d);
    }

    @Test
    void sensitiveMemoryCandidatesRequireApprovalBeforePromotion(@TempDir Path workspace) {
        MemoryStore store = new MemoryStore(workspace);

        store.appendMemoryCandidates(List.of(
                new MemoryEntry()
                        .setType(MemoryEntry.TYPE_FACT)
                        .setSummary("api key 是 secret-value")
                        .setDetails("contains token")
                        .setImportance(0.9d)
        ));

        List<MemoryEntry> candidates = store.readMemoryCandidates();
        assertEquals(1, candidates.size());
        MemoryEntry pending = candidates.get(0);
        assertEquals(MemoryEntry.SENSITIVITY_SENSITIVE, pending.getSensitivity());
        assertEquals(MemoryEntry.APPROVAL_PENDING, pending.getApprovalStatus());
        assertTrue(store.readMemoryEntries().isEmpty());

        assertTrue(store.approveMemoryCandidate(pending.getId()));
        assertTrue(store.readMemoryCandidates().isEmpty());
        List<MemoryEntry> promoted = store.readMemoryEntries();
        assertEquals(1, promoted.size());
        assertEquals(MemoryEntry.APPROVAL_APPROVED, promoted.get(0).getApprovalStatus());
        assertTrue(store.readMemoryAudit().stream().anyMatch(event ->
                "approved_and_promoted".equals(event.get("action"))
                        && pending.getId().equals(event.get("memoryId"))));
    }

    @Test
    void expiredMemoriesAreNotRecalled(@TempDir Path workspace) {
        MemoryStore store = new MemoryStore(workspace);

        store.mergeMemoryEntries(List.of(
                new MemoryEntry()
                        .setType(MemoryEntry.TYPE_PROJECT)
                        .setScope(MemoryEntry.SCOPE_LONG_TERM)
                        .setSummary("项目使用临时数据库")
                        .setImportance(1.0d)
                        .setConfidence(1.0d)
                        .setExpiresAt(java.time.Instant.now().minusSeconds(60).toString())
        ));

        assertTrue(store.recallMemories("临时数据库", "", 3).isEmpty());
    }

    @Test
    void recallScoredMemories_exposesRankingFactors(@TempDir Path workspace) {
        MemoryStore store = new MemoryStore(workspace);

        store.mergeMemoryEntries(List.of(
                new MemoryEntry()
                        .setType(MemoryEntry.TYPE_WORKFLOW)
                        .setMemoryType(MemoryType.EPISODIC)
                        .setScope(MemoryEntry.SCOPE_LONG_TERM)
                        .setSummary("上次修复 MCP 超时需要先看 MCPAdapters")
                        .setImportance(0.75d)
                        .setConfidence(0.8d)
                        .setAccessCount(3),
                new MemoryEntry()
                        .setType(MemoryEntry.TYPE_PROJECT)
                        .setMemoryType(MemoryType.SEMANTIC)
                        .setScope(MemoryEntry.SCOPE_LONG_TERM)
                        .setSummary("项目使用 Java 17")
                        .setImportance(0.95d)
                        .setConfidence(0.95d)
        ));

        var scored = store.recallScoredMemories("继续修复 MCP 超时", "", 2);

        assertEquals(2, scored.size());
        assertEquals("上次修复 MCP 超时需要先看 MCPAdapters", scored.get(0).entry().getSummary());
        assertTrue(scored.get(0).relevanceScore() > scored.get(1).relevanceScore());
        assertTrue(scored.get(0).accessScore() > 0d);
    }

    @Test
    void rebuildMarkdownViewsIfNeeded_rebuildsWhenStructuredEntriesAreNewer(@TempDir Path workspace) throws Exception {
        MemoryStore store = new MemoryStore(workspace);

        store.mergeMemoryEntries(List.of(
                new MemoryEntry()
                        .setType(MemoryEntry.TYPE_PROJECT)
                        .setScope(MemoryEntry.SCOPE_LONG_TERM)
                        .setSummary("项目使用 Java 21")
                        .setDetails("需要新的 Markdown 视图")
        ));
        Path entriesFile = workspace.resolve("memory").resolve("memory_entries.jsonl");
        Path memoryFile = workspace.resolve("memory").resolve("MEMORY.md");
        Files.writeString(memoryFile, "# MEMORY\n\nstale\n");
        FileTime futureTime = FileTime.fromMillis(System.currentTimeMillis() + 2_000);
        Files.setLastModifiedTime(entriesFile, futureTime);
        Files.setLastModifiedTime(memoryFile, FileTime.fromMillis(System.currentTimeMillis()));

        store.rebuildMarkdownViewsIfNeeded();

        assertTrue(store.readMemory().contains("项目使用 Java 21"));
        assertFalse(store.readMemory().contains("stale"));
    }
}
