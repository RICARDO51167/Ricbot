package ricbot.llm.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OpenAICompatProviderParsingTest {

    @Test
    void parseChatCompletion_extractsContent() throws Exception {
        String json = """
                {
                  "id": "chatcmpl_x",
                  "object": "chat.completion",
                  "choices": [
                    {
                      "index": 0,
                      "finish_reason": "stop",
                      "message": {
                        "role": "assistant",
                        "content": "hello"
                      }
                    }
                  ],
                  "usage": { "prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3 }
                }
                """;

        LLMResponse res = OpenAICompatProvider.parseChatCompletion(json);
        assertEquals("hello", res.getContent());
        assertEquals("stop", res.getFinishReason());
        assertEquals(3, res.getUsage().get("total_tokens"));
        assertFalse(res.hasToolCalls());
    }

    @Test
    void parseChatCompletion_extractsToolCalls() throws Exception {
        String json = """
                {
                  "choices": [
                    {
                      "finish_reason": "tool_calls",
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": { "name": "read_file", "arguments": "{\\"path\\":\\"a.txt\\"}" }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        LLMResponse res = OpenAICompatProvider.parseChatCompletion(json);
        assertEquals("tool_calls", res.getFinishReason());
        assertTrue(res.hasToolCalls());
        assertEquals("read_file", res.getToolCalls().get(0).getName());
        assertEquals("a.txt", String.valueOf(res.getToolCalls().get(0).getArguments().get("path")));
    }
}

