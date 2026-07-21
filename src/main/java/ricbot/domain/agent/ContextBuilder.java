package ricbot.domain.agent;

import ricbot.infra.template.PromptTemplates;
import ricbot.integration.llm.api.LLMProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 对应 Python: ContextBuilder
 *
 * 主要目标：
 * 1. 组装给 LLM 的 messages
 * 2. 注入 runtime context
 * 3. 提供 add_assistant_message / add_tool_result 这类辅助能力
 */
public class ContextBuilder {

    // 定义运行时上下文的开始标签
    public static final String RUNTIME_CONTEXT_TAG = "[RUNTIME_CONTEXT]";
    // 定义运行时上下文的结束标签
    public static final String RUNTIME_CONTEXT_END = "[/RUNTIME_CONTEXT]";

    private static final int MAX_INLINE_IMAGE_BYTES = 2_000_000;

    // 工作空间路径
    private final Path workspace;
    // 时区字符串
    private final String timezone;

    /**
     * 构造函数，仅指定工作空间
     * @param workspace 工作空间路径
     */
    public ContextBuilder(Path workspace) {
        this(workspace, null);
    }

    /**
     * 全参构造函数
     * @param workspace 工作空间路径
     * @param timezone 时区
     */
    public ContextBuilder(Path workspace, String timezone) {
        this.workspace = workspace;
        this.timezone = timezone;
    }

    /**
     * 获取时区
     * @return 时区字符串
     */
    public String getTimezone() {
        return timezone;
    }

    /**
     * 构建消息列表（完整版）
     * 补全版，兼容你前面 AgentLoop 用法
     *
     * @param history 历史消息列表
     * @param currentMessage 当前用户消息
     * @param media 媒体文件列表（当前未使用，保留接口兼容性）
     * @param channel 渠道信息
     * @param chatId 聊天ID
     * @param sessionSummary 会话摘要
     * @param currentRole 当前消息的角色
     * @return 组装好的消息列表
     */
    public List<Map<String, Object>> buildMessages(
            List<Map<String, Object>> history,
            String currentMessage,
            List<String> media,
            String channel,
            String chatId,
            String sessionSummary,
            String currentRole
    ) {
        return buildMessages(history, currentMessage, media, channel, chatId, sessionSummary, currentRole, null);
    }

    /**
     * 构建消息列表（完整版）
     * 包含历史消息、系统提示、当前用户消息以及运行时上下文
     *
     * @param history 历史消息列表
     * @param currentMessage 当前用户消息内容
     * @param media 媒体文件路径或URL列表
     * @param channel 渠道信息（如微信、钉钉等）
     * @param chatId 聊天会话ID
     * @param sessionSummary 会话摘要信息
     * @param currentRole 当前消息发送者的角色，默认为 "user"
     * @param promptContext 提示词上下文 bundle，用于注入结构化上下文
     * @return 组装好的符合 LLM 接口规范的消息列表
     */
    public List<Map<String, Object>> buildMessages(
            List<Map<String, Object>> history,
            String currentMessage,
            List<String> media,
            String channel,
            String chatId,
            String sessionSummary,
            String currentRole,
            PromptContextBundle promptContext
    ) {
        // 初始化消息列表，用于存储最终发送给 LLM 的所有消息
        List<Map<String, Object>> messages = new ArrayList<>();

        // 1. 构建运行时上下文（包含时间、时区、渠道、ChatID等信息）
        String runtime = buildRuntimeContext(channel, chatId, timezone);

        // 2. 构建系统提示词，并作为第一条消息加入列表
        // 系统提示词包含了身份定义、运行时上下文、会话摘要及结构化上下文
        messages.add(systemMessage(buildSystemPrompt(sessionSummary, runtime, channel, promptContext)));

        // 3. 处理历史消息
        // 如果历史消息不为空，则进行清洗（去除非法或不完整的消息）后加入列表
        if (history != null && !history.isEmpty()) {
            messages.addAll(sanitizeHistory(history));
        }

        // 4. 处理当前用户消息
        // 如果当前消息内容不为空，则构建对应的消息对象
        if (currentMessage != null) {
            // 创建当前消息的 Map 对象
            Map<String, Object> current = new LinkedHashMap<>();
            // 设置角色，如果未指定则默认为 "user"
            current.put("role", currentRole != null ? currentRole : "user");

            // 构建消息内容，可能包含文本和媒体块
            Object content = buildUserContent(currentMessage, media);
            // 将内容放入消息对象
            current.put("content", content);
            // 将当前消息加入列表
            messages.add(current);
        }

        // 5. 清洗并返回最终的消息列表
        // 确保所有消息的内容字段非空，符合 LLM 提供商的要求
        return LLMProvider.sanitizeEmptyContent(messages);
    }

    /**
     * 构建运行时上下文字符串
     * 对应 Python: _build_runtime_context(...)
     *
     * @param channel 渠道信息
     * @param chatId 聊天ID
     * @param timezone 时区
     * @return 格式化后的运行时上下文字符串
     */
    public static String buildRuntimeContext(String channel, String chatId, String timezone) {
        String tz = timezone != null && !timezone.isBlank() ? timezone : "UTC";

        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
            tz = "UTC";
        }

        String now = ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("now", now);
        payload.put("timezone", tz);
        if (channel != null && !channel.isBlank()) {
            payload.put("channel", channel);
        }
        if (chatId != null && !chatId.isBlank()) {
            payload.put("chat_id", chatId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(RUNTIME_CONTEXT_TAG).append("\n");
        sb.append(payload.toString()).append("\n");
        sb.append(RUNTIME_CONTEXT_END);
        return sb.toString();
    }

    /**
     * 添加助手消息
     * 对应 Python: add_assistant_message(...)
     *
     * @param messages 原始消息列表
     * @param content 消息内容
     * @param toolCalls 工具调用列表
     * @param reasoningContent 推理内容
     * @param thinkingBlocks 思考块列表
     * @return 包含新助手消息的消息列表
     */
    public List<Map<String, Object>> addAssistantMessage(
            List<Map<String, Object>> messages,
            String content,
            List<Map<String, Object>> toolCalls,
            String reasoningContent,
            List<Map<String, Object>> thinkingBlocks
    ) {
        // 创建新的消息列表副本，避免修改原列表
        List<Map<String, Object>> out = new ArrayList<>(messages);

        // 创建助手消息对象
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", content);

        // 如果存在工具调用，则添加
        if (toolCalls != null && !toolCalls.isEmpty()) {
            msg.put("tool_calls", toolCalls);
        }
        // 如果存在推理内容，则添加
        if (reasoningContent != null) {
            msg.put("reasoning_content", reasoningContent);
        }
        // 如果存在思考块，则添加
        if (thinkingBlocks != null && !thinkingBlocks.isEmpty()) {
            msg.put("thinking_blocks", thinkingBlocks);
        }

        // 将新消息添加到列表
        out.add(msg);
        return out;
    }

    /**
     * 添加助手消息（简化版）
     *
     * @param messages 原始消息列表
     * @param content 消息内容
     * @return 包含新助手消息的消息列表
     */
    public List<Map<String, Object>> addAssistantMessage(
            List<Map<String, Object>> messages,
            String content
    ) {
        // 调用全参版本，其他参数设为null
        return addAssistantMessage(messages, content, null, null, null);
    }

    /**
     * 添加工具执行结果
     * 对应 Python: add_tool_result(...)
     *
     * @param messages 原始消息列表
     * @param toolCallId 工具调用ID
     * @param name 工具名称
     * @param result 工具执行结果
     * @return 包含新工具结果消息的消息列表
     */
    public List<Map<String, Object>> addToolResult(
            List<Map<String, Object>> messages,
            String toolCallId,
            String name,
            Object result
    ) {
        // 创建新的消息列表副本
        List<Map<String, Object>> out = new ArrayList<>(messages);

        // 创建工具结果消息对象
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolCallId);
        msg.put("name", name);
        // 将结果转换为字符串，如果结果为null则设为空字符串
        msg.put("content", result != null ? String.valueOf(result) : "");

        // 将新消息添加到列表
        out.add(msg);
        return out;
    }

    /**
     * 创建系统消息对象
     *
     * @param text 系统提示文本
     * @return 系统消息Map
     */
    private Map<String, Object> systemMessage(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "system");
        m.put("content", text);
        return m;
    }

    /**
     * 构建系统提示词
     * 根据会话摘要、运行时上下文、渠道信息和结构化上下文，渲染最终的系统提示词
     *
     * @param sessionSummary 会话摘要信息
     * @param runtimeContext 运行时上下文字符串
     * @param channel 渠道信息
     * @param promptContext 提示词上下文 bundle，用于注入结构化上下文
     * @return 渲染后的系统提示词字符串
     */
    private String buildSystemPrompt(String sessionSummary, String runtimeContext, String channel, PromptContextBundle promptContext) {
        // 初始化模板参数字典
        Map<String, Object> kwargs = new HashMap<>();

        // 获取工作空间的绝对路径并规范化，如果 workspace 为空则设为空字符串
        String workspacePath = workspace != null ? workspace.toAbsolutePath().normalize().toString() : "";
        kwargs.put("workspace_path", workspacePath);

        // 注入运行时上下文，如果为空则设为空字符串
        kwargs.put("runtime", runtimeContext != null ? runtimeContext : "");

        // 注入渠道信息，如果为空则设为空字符串
        kwargs.put("channel", channel != null ? channel : "");

        // 注入会话摘要，如果为 null 则设为空字符串
        kwargs.put("session_summary", (sessionSummary == null) ? "" : sessionSummary);

        // 注入结构化上下文：如果 promptContext 不为空，则调用其 render 方法生成字符串，否则设为空字符串
        kwargs.put("structured_context", promptContext != null ? promptContext.render() : "");

        String system;
        try {
            // 尝试使用 "agent/identity.md" 模板渲染系统提示词
            // 第二个参数 true 表示可能启用某种缓存或特定渲染模式（具体取决于 PromptTemplates 实现）
            system = PromptTemplates.renderTemplate("agent/identity.md", true, kwargs);
        } catch (Exception e) {
            // 如果模板渲染失败，记录异常并使用默认的系统提示词
            system = "You are ricbot.";
        }
        return system;
    }

    private Object buildUserContent(String text, List<String> media) {
        List<String> items = media != null ? media : List.of();
        if (items.isEmpty()) {
            return text;
        }

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "text", "text", text));
        for (String ref : items) {
            Map<String, Object> block = buildImageUrlBlock(ref);
            if (block != null) {
                blocks.add(block);
            } else if (ref != null && !ref.isBlank()) {
                blocks.add(Map.of("type", "text", "text", "[media: " + ref + "]"));
            }
        }
        return blocks;
    }

    private Map<String, Object> buildImageUrlBlock(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:")) {
            return Map.of("type", "image_url", "image_url", Map.of("url", trimmed));
        }

        try {
            Path p = Path.of(trimmed);
            if (!Files.exists(p) || !Files.isRegularFile(p)) {
                return null;
            }

            long size = Files.size(p);
            if (size <= 0 || size > MAX_INLINE_IMAGE_BYTES) {
                return null;
            }

            byte[] raw = Files.readAllBytes(p);
            String mime = detectImageMime(raw);
            if (mime == null) {
                return null;
            }

            String b64 = Base64.getEncoder().encodeToString(raw);
            return Map.of("type", "image_url", "image_url", Map.of("url", "data:" + mime + ";base64," + b64));
        } catch (Exception e) {
            return null;
        }
    }

    private static String detectImageMime(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        if (startsWith(data, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "image/png";
        }
        if (startsWith(data, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(data, "GIF87a".getBytes()) || startsWith(data, "GIF89a".getBytes())) {
            return "image/gif";
        }
        if (startsWith(data, "RIFF".getBytes()) && new String(data, 8, 4).equals("WEBP")) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private List<Map<String, Object>> sanitizeHistory(List<Map<String, Object>> history) {
        int start = findLegalMessageStart(history);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = start; i < history.size(); i++) {
            Map<String, Object> msg = history.get(i);
            if (msg == null) {
                continue;
            }
            Object role = msg.get("role");
            if (!(role instanceof String) || ((String) role).isBlank()) {
                continue;
            }
            if (!msg.containsKey("content") && !"assistant".equals(role)) {
                continue;
            }
            out.add(msg);
        }
        return out;
    }

    private int findLegalMessageStart(List<Map<String, Object>> messages) {
        Set<String> declared = new HashSet<>();
        int start = 0;

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (msg == null) {
                continue;
            }
            String role = String.valueOf(msg.get("role"));

            if ("assistant".equals(role)) {
                Object toolCallsObj = msg.get("tool_calls");
                if (toolCallsObj instanceof List<?> toolCalls) {
                    for (Object tcObj : toolCalls) {
                        if (tcObj instanceof Map<?, ?> tc) {
                            Object id = tc.get("id");
                            if (id != null) {
                                declared.add(String.valueOf(id));
                            }
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                Object tid = msg.get("tool_call_id");
                if (tid != null && !declared.contains(String.valueOf(tid))) {
                    start = i + 1;
                    declared.clear();
                }
            }
        }
        return start;
    }
}
