package ricbot.domain.eval;

import org.junit.jupiter.api.Test;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.LLMFailureException;
import ricbot.integration.llm.api.LLMFailureKind;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvalReplayProviderTest {
    @Test
    void embeddedToolJsonIsComparedCanonically() throws Exception {
        Map<String, Object> recorded = Map.of(
                "model", "model", "messages", List.of(
                        Map.of("role", "system", "content", "你的工作区路径：/old/workspace"),
                        Map.of("role", "tool", "name", "write_file", "tool_call_id", "call-1",
                                "content", "{\"ok\":true,\"result\":\"/old/workspace/file.txt\"}")),
                "tools", List.of(), "status", "ok",
                "response", Map.of("content", "done", "finish_reason", "stop", "tool_calls", List.of()));
        EvalReplayProvider provider = new EvalReplayProvider(List.of(recorded));

        provider.chat(List.of(
                        Map.of("role", "system", "content", "你的工作区路径：/new/workspace"),
                        Map.of("role", "tool", "name", "write_file", "tool_call_id", "call-1",
                                "content", "{\"result\":\"/new/workspace/file.txt\",\"ok\":true}")),
                List.of(), "model", null, null, null, null);

        assertTrue(provider.requestMismatches().isEmpty(), provider.requestMismatches().toString());
    }

    @Test
    void typedRecordedFailurePreservesRetryClassification() {
        Map<String, Object> recorded = Map.of(
                "model", "model", "messages", List.of(), "tools", List.of(), "status", "error",
                "error", Map.of("class", LLMFailureException.class.getName(), "message", "overloaded",
                        "kind", "TRANSIENT", "status_code", 503));
        EvalReplayProvider provider = new EvalReplayProvider(List.of(recorded));

        LLMFailureException failure = assertThrows(LLMFailureException.class,
                () -> provider.chat(List.of(), List.of(), "model", null, null, null, null));

        assertEquals(LLMFailureKind.TRANSIENT, failure.kind());
        assertEquals(503, failure.statusCode());
    }
}
