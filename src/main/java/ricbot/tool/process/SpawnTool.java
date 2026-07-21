package ricbot.tool.process;

import ricbot.domain.agent.SpawnWorkerService;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;
import ricbot.tool.api.Tool.ToolExecutionContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 启动后台子代理的工具类
 */
public class SpawnTool extends Tool {

    private final SpawnWorkerService workers;

    private String channel = "cli";
    private String chatId = "direct";
    private String messageId = "";

    public SpawnTool(SpawnWorkerService workers) {
        this.workers = workers;
    }

    @Override
    public String getName() {
        return "spawn";
    }

    @Override
    public String getDescription() {
        return "启动一个后台子代理，以异步方式执行任务。";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("task", "string", "子代理要执行的任务描述", true),
                ToolParam.of("label", "string", "可选：后台任务的简短标签", false),
                ToolParam.of("session_key", "string", "可选：用于分组/取消的会话密钥", false),
                ToolParam.of("idempotency_key", "string", "可选：调用方提供的稳定幂等键", false)
        );
    }

    public void setContext(String channel, String chatId, String messageId) {
        if (channel != null && !channel.isBlank()) {
            this.channel = channel;
        }
        if (chatId != null && !chatId.isBlank()) {
            this.chatId = chatId;
        }
        this.messageId = messageId != null ? messageId.trim() : "";
    }

    @Override
    public Object execute(Map<String, Object> params, ToolExecutionContext context) {
        if (workers == null) {
            return Map.of("error", "Worker Runtime 不可用。");
        }
        Map<String, Object> args = params != null ? new LinkedHashMap<>(params) : Map.of();
        String sessionKey = text(args.get("session_key"));
        if (sessionKey.isBlank()) sessionKey = channel + ":" + chatId;
        String idempotencyKey = text(args.get("idempotency_key"));
        if (idempotencyKey.isBlank() && context != null) idempotencyKey = context.idempotencyKey();
        if (idempotencyKey.isBlank() && !messageId.isBlank()) {
            idempotencyKey = messageId + ":spawn:" + text(args.get("task")).hashCode();
        }
        SpawnWorkerService.SpawnReceipt receipt = workers.spawn(
                text(args.get("task")),
                text(args.get("label")),
                channel,
                chatId,
                sessionKey,
                idempotencyKey
        );
        return receipt.toMap();
    }

    public String execute(String task, String label, String sessionKey) {
        Map<String, Object> result = castMap(execute(Map.of(
                "task", task != null ? task : "",
                "label", label != null ? label : "",
                "session_key", sessionKey != null ? sessionKey : "",
                "idempotency_key", UUID.randomUUID().toString()
        ), ToolExecutionContext.normal()));
        return "Worker [" + result.getOrDefault("label", "") + "] 已启动（id："
                + result.getOrDefault("worker_id", "") + "）。结果将写入 Mailbox。";
    }

    private static String text(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private static Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
