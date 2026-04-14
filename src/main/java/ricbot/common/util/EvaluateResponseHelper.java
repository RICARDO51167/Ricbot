package ricbot.common.util;


import ricbot.llm.api.LLMProvider;
import ricbot.llm.api.LLMResponse;
import ricbot.llm.api.ToolCallRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                    "description", "Decide whether the user should be notified about this background task result.",
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
                    Map.of("role", "system", "content", "You decide whether a background task result should notify the user."),
                    Map.of("role", "user", "content", "Task context:\n" + taskContext + "\n\nResponse:\n" + response)
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