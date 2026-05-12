package ricbot.integration.llm.openai;

import org.junit.jupiter.api.Test;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import static org.junit.jupiter.api.Assertions.*;

class OpenAICompatProviderTest {

    @Test
    void parseChatCompletion_handlesToolCallsInvalidArgsAndStringUsage() throws Exception {
        String json = """
                {
                  "choices": [
                    {
                      "finish_reason": "tool_calls",
                      "message": {
                        "content": [
                          {"type": "text", "text": "hello"},
                          {"type": "image_url", "image_url": {"url": "ignored"}},
                          {"type": "text", "text": "world"}
                        ],
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": {
                              "name": "lookup",
                              "arguments": "not-json"
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": "7",
                    "completion_tokens": 3,
                    "total_tokens": "10"
                  }
                }
                """;

        LLMResponse response = OpenAICompatProvider.parseChatCompletion(json);

        assertEquals("hello\nworld", response.getContent());
        assertEquals("tool_calls", response.getFinishReason());
        assertEquals(7, response.getUsage().get("prompt_tokens"));
        assertEquals(3, response.getUsage().get("completion_tokens"));
        assertEquals(10, response.getUsage().get("total_tokens"));
        assertEquals(1, response.getToolCalls().size());
        ToolCallRequest call = response.getToolCalls().get(0);
        assertEquals("call_1", call.getId());
        assertEquals("lookup", call.getName());
        assertTrue(call.getArguments().isEmpty());
    }
}
