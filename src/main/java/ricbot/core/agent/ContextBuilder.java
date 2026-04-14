package ricbot.core.agent;

import ricbot.infra.template.PromptTemplates;
import java.nio.file.Path;
import java.time.ZonedDateTime;
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

    public static final String RUNTIME_CONTEXT_TAG = "[RUNTIME_CONTEXT]";
    public static final String RUNTIME_CONTEXT_END = "[/RUNTIME_CONTEXT]";

    private final Path workspace;
    private final String timezone;
    private final List<String> disabledSkills;

    public ContextBuilder(Path workspace) {
        this(workspace, null, null);
    }

    public ContextBuilder(Path workspace, String timezone, List<String> disabledSkills) {
        this.workspace = workspace;
        this.timezone = timezone;
        this.disabledSkills = disabledSkills != null ? disabledSkills : new ArrayList<>();
    }

    public String getTimezone() {
        return timezone;
    }

    /**
     * 对应 Python: build_messages(...)
     */
    public List<Map<String, Object>> buildMessages(
            List<Map<String, Object>> history,
            String currentMessage,
            String channel,
            String chatId
    ) {
        return buildMessages(history, currentMessage, null, channel, chatId, null, "user");
    }

    /**
     * 补全版，兼容你前面 AgentLoop 用法
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
        List<Map<String, Object>> messages = new ArrayList<>();

        // system prompt
        messages.add(systemMessage(buildSystemPrompt(sessionSummary)));

        // history
        if (history != null) {
            messages.addAll(history);
        }

        // current user/runtime message
        if (currentMessage != null) {
            String runtime = buildRuntimeContext(channel, chatId, timezone);
            String combined = runtime + "\n\n" + currentMessage;

            Map<String, Object> current = new LinkedHashMap<>();
            current.put("role", currentRole != null ? currentRole : "user");
            current.put("content", combined);
            messages.add(current);
        }

        return messages;
    }

    /**
     * 对应 Python: _build_runtime_context(...)
     */
    public static String buildRuntimeContext(String channel, String chatId, String timezone) {
        String tz = timezone != null && !timezone.isBlank() ? timezone : "UTC";
        String now = ZonedDateTime.now().toString();

        StringBuilder sb = new StringBuilder();
        sb.append(RUNTIME_CONTEXT_TAG).append("\n");
        sb.append("Current time: ").append(now).append("\n");
        sb.append("Timezone: ").append(tz).append("\n");

        if (channel != null && !channel.isBlank()) {
            sb.append("Channel: ").append(channel).append("\n");
        }
        if (chatId != null && !chatId.isBlank()) {
            sb.append("Chat ID: ").append(chatId).append("\n");
        }

        sb.append(RUNTIME_CONTEXT_END);
        return sb.toString();
    }

    /**
     * 对应 Python: add_assistant_message(...)
     */
    public List<Map<String, Object>> addAssistantMessage(
            List<Map<String, Object>> messages,
            String content,
            List<Map<String, Object>> toolCalls,
            String reasoningContent,
            List<Map<String, Object>> thinkingBlocks
    ) {
        List<Map<String, Object>> out = new ArrayList<>(messages);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", content);

        if (toolCalls != null && !toolCalls.isEmpty()) {
            msg.put("tool_calls", toolCalls);
        }
        if (reasoningContent != null) {
            msg.put("reasoning_content", reasoningContent);
        }
        if (thinkingBlocks != null && !thinkingBlocks.isEmpty()) {
            msg.put("thinking_blocks", thinkingBlocks);
        }

        out.add(msg);
        return out;
    }

    public List<Map<String, Object>> addAssistantMessage(
            List<Map<String, Object>> messages,
            String content
    ) {
        return addAssistantMessage(messages, content, null, null, null);
    }

    /**
     * 对应 Python: add_tool_result(...)
     */
    public List<Map<String, Object>> addToolResult(
            List<Map<String, Object>> messages,
            String toolCallId,
            String name,
            Object result
    ) {
        List<Map<String, Object>> out = new ArrayList<>(messages);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolCallId);
        msg.put("name", name);
        msg.put("content", result != null ? String.valueOf(result) : "");

        out.add(msg);
        return out;
    }

    private Map<String, Object> systemMessage(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "system");
        m.put("content", text);
        return m;
    }

    private String buildSystemPrompt(String sessionSummary) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("workspace", workspace != null ? workspace.toAbsolutePath().normalize().toString() : "");
        kwargs.put("disabled_skills", (disabledSkills == null || disabledSkills.isEmpty()) ? "" : String.join(", ", disabledSkills));
        kwargs.put("session_summary", (sessionSummary == null) ? "" : sessionSummary);

        return PromptTemplates.renderTemplate("agent/identity.md", true, kwargs);
    }
}
