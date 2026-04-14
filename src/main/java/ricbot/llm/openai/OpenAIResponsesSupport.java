package ricbot.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.llm.api.LLMProvider;
import ricbot.llm.api.LLMResponse;

import java.io.InputStream;
import java.util.*;

/**
 * OpenAI Responses API 相关公共辅助类
 *
 * 主要目标：
 * 1. convert_messages
 * 2. convert_tools
 * 3. parse_response_output
 * 4. consume_sse / consume_sdk_stream
 */
public final class OpenAIResponsesSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAIResponsesSupport() {
    }

    public static Map<String, Object> convertMessages(List<Map<String, Object>> messages) {
        ResponsesConverters.ConvertedMessages converted = ResponsesConverters.convertMessages(messages);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instructions", converted.systemPrompt());
        result.put("input", converted.inputItems());
        return result;
    }

    public static List<Map<String, Object>> convertTools(List<Map<String, Object>> tools) {
        return ResponsesConverters.convertTools(tools);
    }

    public static LLMResponse parseResponseOutput(Object response) {
        return ResponsesParsing.parseResponseOutput(response);
    }

    public static LLMResponse consumeSse(
            InputStream inputStream,
            LLMProvider.StreamDeltaHandler onDelta
    ) throws Exception {
        ResponsesParsing.SseConsumeResult result = ResponsesParsing.consumeSse(
                inputStream,
                onDelta::onDelta
        );

        return new LLMResponse()
                .setContent(result.content())
                .setToolCalls(result.toolCalls())
                .setFinishReason(result.finishReason());
    }

    /**
     * 对应 Python: consume_sdk_stream(...)
     *
     * 这里先做一个兼容壳，底层如果已经是 InputStream 或 iterator 都能自己扩展。
     */
    public static LLMResponse consumeSdkStream(
            Object stream,
            LLMProvider.StreamDeltaHandler onDelta,
            LLMProvider.StreamEndHandler onEnd
    ) throws Exception {
        if (stream instanceof InputStream is) {
            LLMResponse response = consumeSse(is, onDelta);
            if (onEnd != null) {
                onEnd.onEnd(false);
            }
            return response;
        }

        // TODO:
        // 如果后面接真正 SDK stream 对象，这里再细化。
        if (onEnd != null) {
            onEnd.onEnd(false);
        }
        return new LLMResponse().setContent("");
    }
}