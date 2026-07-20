package ricbot.domain.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryWritePolicyTest {

    @Test
    void blankMessage_doesNotCreateCandidate() {
        MemoryWritePolicy policy = new MemoryWritePolicy();

        assertTrue(policy.createCandidates("").isEmpty());
        assertTrue(policy.createCandidates("   ").isEmpty());
        assertFalse(policy.shouldGenerateCandidates(""));
    }

    @Test
    void shortOrMeaninglessMessage_doesNotCreateCandidate() {
        MemoryWritePolicy policy = new MemoryWritePolicy();

        assertTrue(policy.createCandidates("hello").isEmpty());
        assertTrue(policy.createCandidates("今天天气不错，继续吧").isEmpty());
    }

    @Test
    void usefulUserPreference_createsCandidate() {
        MemoryWritePolicy policy = new MemoryWritePolicy();

        List<MemoryEntry> candidates = policy.createCandidates("我偏好简短回答");

        assertEquals(1, candidates.size());
        MemoryEntry candidate = candidates.get(0);
        assertEquals(MemoryEntry.TYPE_PREFERENCE, candidate.getType());
        assertEquals(MemoryEntry.SCOPE_LONG_TERM, candidate.getScope());
        assertEquals("我偏好简短回答", candidate.getSummary());
        assertEquals("即时候选：来自用户明确偏好或记忆请求", candidate.getDetails());
        assertEquals(0.75d, candidate.getImportance());
        assertEquals(0.75d, candidate.getConfidence());
        assertEquals("candidate", candidate.getSource());
        assertEquals(List.of("user"), candidate.getTags());
    }

    @Test
    void existingBehavior_isPreserved() {
        MemoryWritePolicy policy = new MemoryWritePolicy();

        MemoryEntry project = policy.createCandidates("项目使用 Java 17，代码库需要遵守现有规范").get(0);
        assertEquals(MemoryEntry.TYPE_PROJECT, project.getType());
        assertEquals(MemoryEntry.SCOPE_LONG_TERM, project.getScope());
        assertEquals("即时候选：来自用户描述的项目事实", project.getDetails());
        assertEquals(0.70d, project.getImportance());
        assertEquals(0.70d, project.getConfidence());
        assertEquals(List.of("project"), project.getTags());

        MemoryEntry workflow = policy.createCandidates("以后流程需要先写测试再实现").get(0);
        assertEquals(MemoryEntry.TYPE_WORKFLOW, workflow.getType());
        assertEquals(MemoryEntry.SCOPE_LONG_TERM, workflow.getScope());
        assertEquals("即时候选：来自用户描述的流程或约定", workflow.getDetails());
        assertEquals(0.68d, workflow.getImportance());
        assertEquals(0.68d, workflow.getConfidence());
        assertEquals(List.of("workflow"), workflow.getTags());
    }
}
