package ricbot.core.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Session {

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
        this.messages = messages != null ? messages : new ArrayList<>();
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
        this.metadata = metadata != null ? metadata : new HashMap<>();
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
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        msg.put("timestamp", Instant.now().toString());
        messages.add(msg);
        updatedAt = Instant.now();
    }

    public List<Map<String, Object>> getHistory(int maxMessages) {
        if (maxMessages <= 0 || messages.size() <= maxMessages) {
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

    /**
     * 对齐你前面 heartbeat 场景里保留少量历史的需求。
     */
    public void retainRecentLegalSuffix(int keepRecentMessages) {
        if (keepRecentMessages <= 0) {
            messages.clear();
            updatedAt = Instant.now();
            return;
        }
        if (messages.size() > keepRecentMessages) {
            messages = new ArrayList<>(messages.subList(messages.size() - keepRecentMessages, messages.size()));
            if (lastConsolidated > messages.size()) {
                lastConsolidated = messages.size();
            }
            updatedAt = Instant.now();
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Session setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        metadata.put("created_at", this.createdAt);
        return this;
    }
}
