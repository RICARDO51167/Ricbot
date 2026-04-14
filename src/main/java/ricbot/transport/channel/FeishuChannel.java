package ricbot.transport.channel;

import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 飞书 / Lark 渠道实现。
 *
 * 主要目标：
 * 1. 通过长连接接收入站消息
 * 2. 支持普通文本、post、interactive card
 * 3. 支持 streaming card 更新
 * 4. 解析复杂消息内容（share card / post / interactive）
 */
public class FeishuChannel extends BaseChannel {

    public static class FeishuConfig {
        private boolean enabled = false;
        private String appId = "";
        private String appSecret = "";
        private String encryptKey = "";
        private String verificationToken = "";
        private List<String> allowFrom = new ArrayList<>();
        private String reactEmoji = "THUMBSUP";
        private String doneEmoji;
        private String toolHintPrefix = "🔧";
        private String groupPolicy = "mention";
        private boolean replyToMessage = false;
        private boolean streaming = true;
        private String domain = "feishu";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }

        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }

        public String getEncryptKey() { return encryptKey; }
        public void setEncryptKey(String encryptKey) { this.encryptKey = encryptKey; }

        public String getVerificationToken() { return verificationToken; }
        public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }

        public String getReactEmoji() { return reactEmoji; }
        public void setReactEmoji(String reactEmoji) { this.reactEmoji = reactEmoji; }

        public String getDoneEmoji() { return doneEmoji; }
        public void setDoneEmoji(String doneEmoji) { this.doneEmoji = doneEmoji; }

        public String getToolHintPrefix() { return toolHintPrefix; }
        public void setToolHintPrefix(String toolHintPrefix) { this.toolHintPrefix = toolHintPrefix; }

        public String getGroupPolicy() { return groupPolicy; }
        public void setGroupPolicy(String groupPolicy) { this.groupPolicy = groupPolicy; }

        public boolean isReplyToMessage() { return replyToMessage; }
        public void setReplyToMessage(boolean replyToMessage) { this.replyToMessage = replyToMessage; }

        public boolean isStreaming() { return streaming; }
        public void setStreaming(boolean streaming) { this.streaming = streaming; }

        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
    }

    public static class FeishuStreamBuf {
        private String text = "";
        private String cardId;
        private int sequence = 0;
        private long lastEditMillis = 0;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public String getCardId() { return cardId; }
        public void setCardId(String cardId) { this.cardId = cardId; }

        public int getSequence() { return sequence; }
        public void setSequence(int sequence) { this.sequence = sequence; }

        public long getLastEditMillis() { return lastEditMillis; }
        public void setLastEditMillis(long lastEditMillis) { this.lastEditMillis = lastEditMillis; }
    }

    private static final long STREAM_EDIT_INTERVAL_MS = 500;

    private static final Pattern COMPLEX_MD_RE = Pattern.compile("```|^#{1,6}\\s+|^\\|.+\\|.*\\n\\s*\\|[-:\\s|]+\\|", Pattern.MULTILINE);
    private static final Pattern SIMPLE_MD_RE = Pattern.compile("\\*\\*.+?\\*\\*|__.+?__|~~.+?~~", Pattern.DOTALL);
    private static final Pattern MD_LINK_RE = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^\\)]+)\\)");
    private static final Pattern LIST_RE = Pattern.compile("^[\\s]*[-*+]\\s+", Pattern.MULTILINE);
    private static final Pattern OLIST_RE = Pattern.compile("^[\\s]*\\d+\\.\\s+", Pattern.MULTILINE);

    private static final int TEXT_MAX_LEN = 200;
    private static final int POST_MAX_LEN = 2000;

    private final FeishuConfig config;
    private Object client;
    private Object wsClient;
    private String botOpenId;

    private final Map<String, FeishuStreamBuf> streamBufs = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Void> processedMessageIds = new LinkedHashMap<>();

    public FeishuChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "feishu";
        this.displayName = "Feishu";
        this.config = (config instanceof FeishuConfig c) ? c : new FeishuConfig();
    }

    @Override
    public void start() throws Exception {
        if (config.getAppId() == null || config.getAppId().isBlank()
                || config.getAppSecret() == null || config.getAppSecret().isBlank()) {
            System.err.println("Feishu app_id and app_secret not configured");
            return;
        }

        running = true;

        // TODO:
        // 1. 初始化飞书 Client
        // 2. 初始化 WebSocket 长连接
        // 3. 注册消息事件回调
        // 4. 启动接收循环

        System.out.println("Feishu bot started");
    }

    @Override
    public void stop() throws Exception {
        running = false;

        // TODO: 关闭 ws client / sdk client
        streamBufs.clear();
    }

    public void send(OutboundMessage msg) throws Exception {
        String content = msg.getContent() != null ? msg.getContent() : "";
        String format = detectMsgFormat(content);

        // TODO:
        // 1. text -> 纯文本消息
        // 2. post -> 富文本 post
        // 3. interactive -> card
        // 4. 如果 msg.replyTo 需要引用消息，则补 reply 上下文

        System.out.println("Feishu send [" + format + "] -> " + msg.getChatId() + ": " + content);
    }

    @Override
    public void sendDelta(String chatId, String delta, Map<String, Object> metadata) throws Exception {
        FeishuStreamBuf buf = streamBufs.computeIfAbsent(chatId, k -> new FeishuStreamBuf());
        buf.setText(buf.getText() + (delta != null ? delta : ""));

        if (buf.getText().isBlank()) {
            return;
        }

        boolean streamEnd = metadata != null && Boolean.TRUE.equals(metadata.get("_stream_end"));
        long now = System.currentTimeMillis();

        if (streamEnd) {
            finalizeStream(chatId, buf);
            return;
        }

        if (buf.getCardId() == null) {
            // TODO: 首次发送 interactive card，保存 cardId
            buf.setCardId("TODO_CARD_ID");
            buf.setLastEditMillis(now);
            return;
        }

        if ((now - buf.getLastEditMillis()) < STREAM_EDIT_INTERVAL_MS) {
            return;
        }

        // TODO: 调 CardKit / 消息更新接口做 streaming update
        buf.setSequence(buf.getSequence() + 1);
        buf.setLastEditMillis(now);
    }

    private void finalizeStream(String chatId, FeishuStreamBuf buf) {
        // TODO: 提交最终 streaming card 内容
        streamBufs.remove(chatId);
    }

    /**
     * 智能判断使用 text / post / interactive 哪种消息格式。
     */
    public String detectMsgFormat(String content) {
        String stripped = content == null ? "" : content.strip();

        if (COMPLEX_MD_RE.matcher(stripped).find()) {
            return "interactive";
        }
        if (stripped.length() > POST_MAX_LEN) {
            return "interactive";
        }
        if (SIMPLE_MD_RE.matcher(stripped).find()) {
            return "interactive";
        }
        if (LIST_RE.matcher(stripped).find() || OLIST_RE.matcher(stripped).find()) {
            return "interactive";
        }
        if (MD_LINK_RE.matcher(stripped).find()) {
            return "post";
        }
        if (stripped.length() <= TEXT_MAX_LEN) {
            return "text";
        }
        return "post";
    }

    /**
     * Markdown -> post message JSON 的简化转换。
     */
    public String markdownToPost(String content) {
        // TODO: 把 markdown 转成飞书 post 结构 JSON
        return content;
    }

    /**
     * 处理飞书入站消息。
     */
    public void onInboundMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata
    ) throws Exception {
        handleMessage(senderId, chatId, content, media, metadata);
    }

    /**
     * 从 interactive/share/post 卡片中提取可读文本。
     */
    public String extractStructuredContent(Map<String, Object> contentJson, String msgType) {
        // TODO:
        // 1. interactive -> 提取文本、按钮、链接
        // 2. post -> 提取段落、图片 key
        // 3. share_chat / share_user / merge_forward 等做可读降级
        return "[" + msgType + "]";
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }
}