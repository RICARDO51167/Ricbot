package ricbot.domain.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话类，用于管理用户与机器人之间的交互历史、元数据及状态。
 */
public class Session {

    public static final String KEY_ROLE = "role";
    public static final String KEY_CONTENT = "content";
    public static final String KEY_TIMESTAMP = "timestamp";
    public static final String KEY_TOOL_CALLS = "tool_calls";
    public static final String KEY_TOOL_CALL_ID = "tool_call_id";
    public static final String KEY_NAME = "name";
    public static final String KEY_ID = "id";

    private String key;
    private List<Map<String, Object>> messages = new ArrayList<>();
    private int lastConsolidated = 0;
    private Map<String, Object> metadata = new HashMap<>();
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Session() {
    }

    public Session(String key) {
        this.key = key;
    }

    public Session(String key, List<Map<String, Object>> messages, Instant createdAt, Instant updatedAt, Map<String, Object> metadata, int lastConsolidated) {
        this.key = key;
        this.messages = messages != null ? messages : new ArrayList<>();
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
        this.metadata = metadata != null ? metadata : new HashMap<>();
        this.lastConsolidated = lastConsolidated;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Session setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    public String getKey() {
        return key;
    }

    public Session setKey(String key) {
        this.key = key;
        return this;
    }

    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    public Session setMessages(List<Map<String, Object>> messages) {
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        return this;
    }

    public int getLastConsolidated() {
        return lastConsolidated;
    }

    public Session setLastConsolidated(int lastConsolidated) {
        this.lastConsolidated = lastConsolidated;
        return this;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Session setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        return this;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Session setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
        return this;
    }

    public void addMessage(String role, Object content) {
        addMessage(SessionMessage.of(role, content).toMap());
    }

    public void addMessage(Map<String, Object> message) {
        if (message == null) {
            return;
        }
        messages.add(SessionMessage.fromMap(message).toMap());
        updatedAt = Instant.now();
    }

    public void addToolMessage(String toolCallId, String name, Object content) {
        addMessage(SessionMessage.tool(toolCallId, name, content).toMap());
    }

    public void addAssistantMessage(String content, List<Map<String, Object>> toolCalls) {
        addMessage(SessionMessage.assistant(content, toolCalls).toMap());
    }

    public List<Map<String, Object>> getHistory(int maxMessages) {
        if (maxMessages == 0) {
            return new ArrayList<>();
        }
        if (maxMessages < 0 || messages.size() <= maxMessages) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
    }

    public void clear() {
        messages.clear();
        lastConsolidated = 0;
        metadata.clear();
        updatedAt = Instant.now();
    }

    public List<Map<String, Object>> getMessagesFromLastConsolidated() {
        if (lastConsolidated >= messages.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(messages.subList(lastConsolidated, messages.size()));
    }

    public void retainRecentLegalSuffix(int keepRecentMessages) {
        if (keepRecentMessages <= 0) {
            messages.clear();
            updatedAt = Instant.now();
            return;
        }

        int baseCut = Math.max(0, messages.size() - keepRecentMessages);
        List<Map<String, Object>> suffix = new ArrayList<>(messages.subList(baseCut, messages.size()));
        int legalStart = findLegalMessageStart(suffix);
        int removed = baseCut + legalStart;
        if (legalStart > 0) {
            suffix = new ArrayList<>(suffix.subList(legalStart, suffix.size()));
        }

        messages = suffix;
        lastConsolidated = Math.max(0, lastConsolidated - removed);
        if (lastConsolidated > messages.size()) {
            lastConsolidated = messages.size();
        }
        updatedAt = Instant.now();
    }

    private static int findLegalMessageStart(List<Map<String, Object>> messages) {
        Set<String> declared = new HashSet<>();
        int start = 0;

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (msg == null) {
                continue;
            }
            SessionMessage typed = SessionMessage.fromMap(msg);
            String role = typed.role();

            if (SessionMessage.ROLE_ASSISTANT.equals(role)) {
                declared.addAll(typed.toolCallIds());
            } else if (SessionMessage.ROLE_TOOL.equals(role)) {
                String tid = typed.toolCallId();
                if (!tid.isBlank() && !declared.contains(tid)) {
                    start = i + 1;
                    declared.clear();
                }
            }
        }

        return start;
    }
}
