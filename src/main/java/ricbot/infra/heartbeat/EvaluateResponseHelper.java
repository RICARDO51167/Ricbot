package ricbot.infra.heartbeat;

import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估响应助手类
 */
public final class EvaluateResponseHelper {

    private EvaluateResponseHelper() {
    }

    public static boolean evaluateResponse(
            String response,
            String taskContext,
            LLMProvider provider,
            String model
    ) {
        try {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", Map.of(
                    "name", "evaluate_notification",
                    "description", "判断是否应该就后台任务结果通知用户。",
                    "parameters", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "should_notify", Map.of("type", "boolean"),
                                    "reason", Map.of("type", "string")
                            ),
                            "required", List.of("should_notify")
                    )
            ));

            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "system", "content", "你负责决定后台任务的结果是否需要通知用户。"),
                    Map.of("role", "user", "content", "任务上下文：\n" + taskContext + "\n\n响应内容：\n" + response)
            );

            LLMResponse llmResponse = provider.chat(
                    messages,
                    List.of(tool),
                    model,
                    256,
                    0.0,
                    null,
                    null
            );

            if (!llmResponse.hasToolCalls()) {
                return true;
            }

            ToolCallRequest tc = llmResponse.getToolCalls().get(0);
            Object shouldNotify = tc.getArguments().get("should_notify");
            if (shouldNotify instanceof Boolean b) {
                return b;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }
}