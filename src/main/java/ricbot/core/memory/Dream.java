package ricbot.core.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.template.PromptTemplates;
import ricbot.llm.api.LLMProvider;
import ricbot.llm.api.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 对应 Python: Dream
 *
 * 长期记忆整合器。
 * 1. 读取最近的会话历史。
 * 2. 通过 LLM 提取关键事实、用户偏好、机器人性格设定。
 * 3. 更新 MEMORY.md, USER.md, SOUL.md。
 */
public class Dream {

    private static final Logger log = LoggerFactory.getLogger(Dream.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workspace;
    private final LLMProvider provider;
    private final String model;
    private final MemoryStore store;

    public Dream(Path workspace, LLMProvider provider, String model, MemoryStore store) {
        this.workspace = workspace;
        this.provider = provider;
        this.model = model;
        this.store = store;
    }

    /**
     * 执行一次 Dream 整合任务。
     *
     * @return 如果有新内容处理并更新了记忆，返回 true。
     */
    public boolean run() {
        log.info("Dream: Starting memory consolidation...");

        try {
            // 1. 获取最近尚未被 Dream 处理的会话片段
            List<Map<String, Object>> newHistory = store.getUnprocessedHistory();
            if (newHistory == null || newHistory.isEmpty()) {
                log.info("Dream: No new history to process.");
                return false;
            }

            // 2. 读取现有记忆文件内容
            String memoryMd = store.getMemoryMd();
            String userMd = store.getUserMd();
            String soulMd = store.getSoulMd();

            // 3. 构建 Prompt
            Map<String, Object> kwargs = new HashMap<>();
            kwargs.put("history", formatHistory(newHistory));
            kwargs.put("memory_md", memoryMd);
            kwargs.put("user_md", userMd);
            kwargs.put("soul_md", soulMd);

            String prompt = PromptTemplates.renderTemplate("agent/dream.md", true, kwargs);

            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", prompt);
            messages.add(systemMsg);

            // 4. 调用 LLM
            LLMResponse response = provider.chat(messages, List.of(), model, null, null, null, null);
            String content = response.getContent();

            if (content == null || content.isBlank() || content.contains("(nothing)")) {
                log.info("Dream: LLM returned no updates.");
                // 虽然没有更新文件，但这段历史已经处理过了
                store.markHistoryAsProcessed(newHistory.size());
                return false;
            }

            // 5. 解析并保存更新后的记忆
            parseAndSaveUpdates(content);

            // 6. 标记这些历史记录已被 Dream 处理
            store.markHistoryAsProcessed(newHistory.size());

            log.info("Dream: Memory updated successfully.");
            return true;

        } catch (Exception e) {
            log.error("Dream: Error during memory consolidation", e);
            return false;
        }
    }

    private String formatHistory(List<Map<String, Object>> history) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : history) {
            sb.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
        }
        return sb.toString();
    }

    private void parseAndSaveUpdates(String content) throws IOException {
        // 假设 LLM 以特定格式返回（如 XML 或 Markdown 块）
        // 这里采用简单的 Markdown 块提取
        String memory = extractBlock(content, "MEMORY.md");
        String user = extractBlock(content, "USER.md");
        String soul = extractBlock(content, "SOUL.md");

        if (memory != null) store.updateMemoryMd(memory);
        if (user != null) store.updateUserMd(user);
        if (soul != null) store.updateSoulMd(soul);
    }

    private String extractBlock(String text, String filename) {
        String marker = "### " + filename;
        int start = text.indexOf(marker);
        if (start == -1) return null;

        start += marker.length();
        int end = text.indexOf("### ", start);
        String block = (end == -1) ? text.substring(start) : text.substring(start, end);

        return block.trim();
    }
}