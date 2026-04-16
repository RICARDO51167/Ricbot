package ricbot.infra.heartbeat;


import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.integration.llm.api.ToolCallRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EvaluateResponseHelper {

    // 私有构造函数，防止实例化此类
    private EvaluateResponseHelper() {
    }

    /**
     * 评估LLM的响应，判断是否应该通知用户。
     *
     * @param response LLM生成的响应内容
     * @param taskContext 任务上下文信息
     * @param provider LLM提供者接口
     * @param model 使用的模型名称
     * @return 如果应该通知用户则返回true，否则返回false
     */
    public static boolean evaluateResponse(
            String response,
            String taskContext,
            LLMProvider provider,
            String model
    ) {
        try {
            // 创建工具定义，用于让LLM调用函数来判断是否需要通知
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function"); // 指定工具类型为函数
            tool.put("function", Map.of(
                    "name", "evaluate_notification", // 函数名称
                    "description", "判断是否应该就后台任务结果通知用户。", // 函数描述
                    "parameters", Map.of(
                            "type", "object", // 参数类型为对象
                            "properties", Map.of(
                                    "should_notify", Map.of("type", "boolean"), // 是否应该通知的参数定义
                                    "reason", Map.of("type", "string") // 原因的参数定义
                            ),
                            "required", List.of("should_notify") // 必填参数列表
                    )
            ));

            // 构建消息列表，包含系统提示和用户输入
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "system", "content", "你负责决定后台任务的结果是否需要通知用户。"), // 系统角色提示
                    Map.of("role", "user", "content", "任务上下文：\n" + taskContext + "\n\n响应内容：\n" + response) // 用户角色输入，包含任务上下文和响应内容
            );

            // 调用LLM进行聊天，传入消息、工具、模型等参数
            LLMResponse llmResponse = provider.chat(
                    messages,
                    List.of(tool),
                    model,
                    256, // 最大令牌数
                    0.0, // 温度系数，控制随机性
                    null,
                    null
            );

            // 如果LLM响应中没有工具调用，默认返回true（需要通知）
            if (!llmResponse.hasToolCalls()) {
                return true;
            }

            // 获取第一个工具调用请求
            ToolCallRequest tc = llmResponse.getToolCalls().get(0);
            // 从工具调用参数中获取should_notify的值
            Object shouldNotify = tc.getArguments().get("should_notify");
            // 如果should_notify是布尔类型，则返回该值
            if (shouldNotify instanceof Boolean b) {
                return b;
            }
            // 默认返回true（需要通知）
            return true;
        } catch (Exception e) {
            // 发生异常时，默认返回true（需要通知）
            return true;
        }
    }
}