package ricbot.llm.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OpenAICompatProviderParsingTest {

    @Test
    void parseChatCompletion_extractsContent() throws Exception {
        // 定义一个模拟的 JSON 响应字符串，包含基本的聊天完成信息
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

        // 调用被测试方法解析 JSON 字符串
        LLMResponse res = OpenAICompatProvider.parseChatCompletion(json);
        // 断言解析后的内容是否为 "hello"
        assertEquals("hello", res.getContent());
        // 断言结束原因是否为 "stop"
        assertEquals("stop", res.getFinishReason());
        // 断言总 token 数是否为 3
        assertEquals(3, res.getUsage().get("total_tokens"));
        // 断言是否没有工具调用
        assertFalse(res.hasToolCalls());
    }

    @Test
    void parseChatCompletion_extractsToolCalls() throws Exception {
        // 定义一个模拟的 JSON 响应字符串，包含工具调用信息
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

        // 调用被测试方法解析 JSON 字符串
        LLMResponse res = OpenAICompatProvider.parseChatCompletion(json);
        // 断言结束原因是否为 "tool_calls"
        assertEquals("tool_calls", res.getFinishReason());
        // 断言是否存在工具调用
        assertTrue(res.hasToolCalls());
        // 断言第一个工具调用的名称是否为 "read_file"
        assertEquals("read_file", res.getToolCalls().get(0).getName());
        // 断言第一个工具调用的参数中 "path" 的值是否为 "a.txt"
        assertEquals("a.txt", String.valueOf(res.getToolCalls().get(0).getArguments().get("path")));
    }
}

