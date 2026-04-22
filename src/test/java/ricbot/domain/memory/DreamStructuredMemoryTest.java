package ricbot.domain.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DreamStructuredMemoryTest {

    @Test
    void runDetailed_mergesStructuredMemoryEntries(@TempDir Path workspace) {
        MemoryStore store = new MemoryStore(workspace);
        store.appendHistory("user: 我偏好简短回答");

        LLMProvider provider = new LLMProvider("k", "http://localhost") {
            @Override
            public LLMResponse chat(
                    List<Map<String, Object>> messages,
                    List<Map<String, Object>> tools,
                    String model,
                    Integer maxTokens,
                    Double temperature,
                    String reasoningEffort,
                    Object toolChoice
            ) {
                return new LLMResponse().setContent("""
                        {"entries":[
                          {"type":"preference","scope":"long_term","summary":"用户偏好简短回答","details":"回答尽量简洁","importance":0.9,"confidence":0.95,"source":"dream","status":"active","aliases":[],"tags":["user"]},
                          {"type":"preference","scope":"long_term","summary":"用户偏好简短回答","details":"重复条目","importance":0.8,"confidence":0.9,"source":"dream","status":"active","aliases":[],"tags":["user"]}
                        ]}
                        """).setFinishReason("stop");
            }
        };

        Dream dream = new Dream(provider, "test-model", store);
        Dream.DreamRunResult result = dream.runDetailed();

        assertTrue(result.updated());
        assertEquals(1, store.readMemoryEntries().stream()
                .filter(entry -> "用户偏好简短回答".equals(entry.getSummary()))
                .count());
        assertTrue(store.readUser().contains("用户偏好简短回答"));
    }
}
