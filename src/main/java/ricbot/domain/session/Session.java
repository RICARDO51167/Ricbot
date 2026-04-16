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

    // 会话的唯一标识键
    private String key;
    // 存储消息历史的列表，每个消息是一个包含角色、内容等信息的 Map
    private List<Map<String, Object>> messages = new ArrayList<>();
    // 上次合并（consolidated）的消息索引，用于优化上下文处理
    private int lastConsolidated = 0;
    // 会话的元数据，用于存储额外的键值对信息
    private Map<String, Object> metadata = new HashMap<>();
    // 会话创建的时间戳
    private Instant createdAt = Instant.now();
    // 会话最后更新的时间戳
    private Instant updatedAt = Instant.now();

    /**
     * 默认构造函数
     */
    public Session() {
    }

    /**
     * 使用指定键创建会话
     *
     * @param key 会话的唯一标识键
     */
    public Session(String key) {
        this.key = key;
    }

    /**
     * 全参数构造函数，用于初始化会话的所有字段
     *
     * @param key             会话的唯一标识键
     * @param messages        初始消息列表
     * @param createdAt       创建时间
     * @param updatedAt       更新时间
     * @param metadata        元数据
     * @param lastConsolidated 上次合并的消息索引
     */
    public Session(String key, List<Map<String, Object>> messages, Instant createdAt, Instant updatedAt, Map<String, Object> metadata, int lastConsolidated) {
        this.key = key;
        // 如果传入的消息列表为 null，则初始化为空列表
        this.messages = messages != null ? messages : new ArrayList<>();
        // 如果传入的创建时间为 null，则使用当前时间
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        // 如果传入的更新时间为 null，则使用当前时间
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
        // 如果传入的元数据为 null，则初始化为空 Map
        this.metadata = metadata != null ? metadata : new HashMap<>();
        this.lastConsolidated = lastConsolidated;
    }

    /**
     * 获取会话创建时间
     *
     * @return 创建时间 Instant 对象
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * 设置会话创建时间
     *
     * @param createdAt 创建时间
     * @return 当前 Session 对象，支持链式调用
     */
    public Session setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    /**
     * 获取会话的唯一标识键
     *
     * @return 会话键
     */
    public String getKey() {
        return key;
    }

    /**
     * 设置会话的唯一标识键
     *
     * @param key 会话键
     * @return 当前 Session 对象，支持链式调用
     */
    public Session setKey(String key) {
        this.key = key;
        return this;
    }

    /**
     * 获取消息历史列表
     *
     * @return 消息列表
     */
    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    /**
     * 设置消息历史列表
     *
     * @param messages 消息列表
     * @return 当前 Session 对象，支持链式调用
     */
    public Session setMessages(List<Map<String, Object>> messages) {
        // 如果传入的消息列表为 null，则初始化为空列表
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        return this;
    }

    /**
     * 获取上次合并的消息索引
     *
     * @return 上次合并的索引
     */
    public int getLastConsolidated() {
        return lastConsolidated;
    }

    /**
     * 设置上次合并的消息索引
     *
     * @param lastConsolidated 上次合并的索引
     * @return 当前 Session 对象，支持链式调用
     */
    public Session setLastConsolidated(int lastConsolidated) {
        this.lastConsolidated = lastConsolidated;
        return this;
    }

    /**
     * 获取会话元数据
     *
     * @return 元数据 Map
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 设置会话元数据
     *
     * @param metadata 元数据 Map
     * @return 当前 Session 对象，支持链式调用
     */
    public Session setMetadata(Map<String, Object> metadata) {
        // 如果传入的元数据为 null，则初始化为空 Map
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        return this;
    }

    /**
     * 获取会话最后更新时间
     *
     * @return 更新时间 Instant 对象
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 设置会话最后更新时间
     *
     * @param updatedAt 更新时间
     * @return 当前 Session 对象，支持链式调用
     */
    public Session setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
        return this;
    }

    /**
     * 添加一条新消息到会话历史中
     *
     * @param role    消息角色（如 user, assistant, system）
     * @param content 消息内容
     */
    public void addMessage(String role, Object content) {
        Map<String, Object> msg = new HashMap<>();
        msg.put(KEY_ROLE, role);
        msg.put(KEY_CONTENT, content);
        addMessage(msg);
    }

    public void addMessage(Map<String, Object> message) {
        if (message == null) {
            return;
        }
        Map<String, Object> msg = new HashMap<>(message);
        msg.putIfAbsent(KEY_TIMESTAMP, Instant.now().toString());
        messages.add(msg);
        updatedAt = Instant.now();
    }

    public void addToolMessage(String toolCallId, String name, Object content) {
        Map<String, Object> msg = new HashMap<>();
        msg.put(KEY_ROLE, "tool");
        msg.put(KEY_TOOL_CALL_ID, toolCallId);
        msg.put(KEY_NAME, name);
        msg.put(KEY_CONTENT, content);
        addMessage(msg);
    }

    public void addAssistantMessage(String content, List<Map<String, Object>> toolCalls) {
        Map<String, Object> msg = new HashMap<>();
        msg.put(KEY_ROLE, "assistant");
        msg.put(KEY_CONTENT, content);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            msg.put(KEY_TOOL_CALLS, toolCalls);
        }
        addMessage(msg);
    }

    /**
     * 获取最近的历史消息，限制最大数量
     *
     * @param maxMessages 最大返回的消息数量
     * @return 最近的消息列表副本
     */
    public List<Map<String, Object>> getHistory(int maxMessages) {
        if (maxMessages == 0) {
            return new ArrayList<>();
        }
        if (maxMessages < 0 || messages.size() <= maxMessages) {
            return new ArrayList<>(messages);
        }
        // 否则返回最后 maxMessages 条消息的副本
        return new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
    }

    /**
     * 清空会话中的所有消息、元数据，并重置合并索引
     */
    public void clear() {
        // 清空消息列表
        messages.clear();
        // 重置合并索引
        lastConsolidated = 0;
        // 清空元数据
        metadata.clear();
        // 更新最后修改时间
        updatedAt = Instant.now();
    }

    /**
     * 获取从上次合并点之后的所有消息
     *
     * @return 未合并的消息列表副本
     */
    public List<Map<String, Object>> getMessagesFromLastConsolidated() {
        // 如果合并索引大于或等于消息总数，说明没有未合并的消息，返回空列表
        if (lastConsolidated >= messages.size()) {
            return new ArrayList<>();
        }
        // 返回从 lastConsolidated 索引开始到末尾的消息副本
        return new ArrayList<>(messages.subList(lastConsolidated, messages.size()));
    }

    /**
     * 保留最近的若干条消息，删除较早的消息。
     * 用于对齐 heartbeat 场景里保留少量历史的需求。
     *
     * @param keepRecentMessages 需要保留的最近消息数量
     */
    public void retainRecentLegalSuffix(int keepRecentMessages) {
        // 如果保留数量小于等于0，则清空所有消息
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
            String role = msg.get(KEY_ROLE) != null ? String.valueOf(msg.get(KEY_ROLE)) : "";

            if ("assistant".equals(role)) {
                Object toolCallsObj = msg.get(KEY_TOOL_CALLS);
                if (toolCallsObj instanceof List<?> toolCalls) {
                    for (Object tcObj : toolCalls) {
                        if (tcObj instanceof Map<?, ?> tc) {
                            Object id = tc.get(KEY_ID);
                            if (id != null) {
                                declared.add(String.valueOf(id));
                            }
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                Object tid = msg.get(KEY_TOOL_CALL_ID);
                if (tid != null && !declared.contains(String.valueOf(tid))) {
                    start = i + 1;
                    declared.clear();
                }
            }
        }

        return start;
    }
}
