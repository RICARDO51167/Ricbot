package ricbot.transport.channel;

import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slack 渠道实现。
 *
 * 对应 Python: slack.py
 *
 * 主要职责：
 * 1. 通过 Socket Mode 接收入站事件
 * 2. 支持 DM / group / channel 权限控制
 * 3. 支持 mention 检测
 * 4. 支持 thread reply
 * 5. 支持 reaction 更新
 * 6. 支持 Markdown -> Slack mrkdwn 转换
 */
public class SlackChannel extends BaseChannel {

    // =========================================================
    // Config
    // =========================================================

    public static class SlackDMConfig {
        private boolean enabled = true;
        private String policy = "open"; // open / allowlist
        private List<String> allowFrom = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPolicy() { return policy; }
        public void setPolicy(String policy) { this.policy = policy; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
    }

    public static class SlackConfig {
        private boolean enabled = false;
        private String mode = "socket";
        private String webhookPath = "/slack/events";
        private String botToken = "";
        private String appToken = "";
        private boolean userTokenReadOnly = true;
        private boolean replyInThread = true;
        private String reactEmoji = "eyes";
        private String doneEmoji = "white_check_mark";
        private List<String> allowFrom = new ArrayList<>();
        private String groupPolicy = "mention"; // open / mention / allowlist
        private List<String> groupAllowFrom = new ArrayList<>();
        private SlackDMConfig dm = new SlackDMConfig();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getWebhookPath() { return webhookPath; }
        public void setWebhookPath(String webhookPath) { this.webhookPath = webhookPath; }
        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
        public String getAppToken() { return appToken; }
        public void setAppToken(String appToken) { this.appToken = appToken; }
        public boolean isUserTokenReadOnly() { return userTokenReadOnly; }
        public void setUserTokenReadOnly(boolean userTokenReadOnly) { this.userTokenReadOnly = userTokenReadOnly; }
        public boolean isReplyInThread() { return replyInThread; }
        public void setReplyInThread(boolean replyInThread) { this.replyInThread = replyInThread; }
        public String getReactEmoji() { return reactEmoji; }
        public void setReactEmoji(String reactEmoji) { this.reactEmoji = reactEmoji; }
        public String getDoneEmoji() { return doneEmoji; }
        public void setDoneEmoji(String doneEmoji) { this.doneEmoji = doneEmoji; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
        public String getGroupPolicy() { return groupPolicy; }
        public void setGroupPolicy(String groupPolicy) { this.groupPolicy = groupPolicy; }
        public List<String> getGroupAllowFrom() { return groupAllowFrom; }
        public void setGroupAllowFrom(List<String> groupAllowFrom) { this.groupAllowFrom = groupAllowFrom; }
        public SlackDMConfig getDm() { return dm; }
        public void setDm(SlackDMConfig dm) { this.dm = dm; }
    }

    // =========================================================
    // Adapter interfaces
    // =========================================================

    /**
     * 适配真正的 Slack SDK。
     */
    public interface SlackWebClient {
        Map<String, Object> authTest() throws Exception;
        void chatPostMessage(String channel, String text, String threadTs) throws Exception;
        void filesUpload(String channel, String filePath, String threadTs) throws Exception;
        void reactionsAdd(String channel, String emoji, String timestamp) throws Exception;
        void reactionsRemove(String channel, String emoji, String timestamp) throws Exception;
    }

    public interface SlackSocketClient {
        void connect(SlackSocketListener listener) throws Exception;
        void close() throws Exception;
        void ack(String envelopeId) throws Exception;
    }

    public interface SlackSocketListener {
        void onRequest(SlackSocketRequest request);
    }

    public static class SlackSocketRequest {
        private String type;
        private String envelopeId;
        private Map<String, Object> payload;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getEnvelopeId() { return envelopeId; }
        public void setEnvelopeId(String envelopeId) { this.envelopeId = envelopeId; }
        public Map<String, Object> getPayload() { return payload; }
        public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    }

    // =========================================================
    // Channel fields
    // =========================================================

    private final SlackConfig config;
    private SlackWebClient webClient;
    private SlackSocketClient socketClient;
    private String botUserId;

    public SlackChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "slack";
        this.displayName = "Slack";
        this.config = (config instanceof SlackConfig c) ? c : new SlackConfig();
    }

    public void setWebClient(SlackWebClient webClient) {
        this.webClient = webClient;
    }

    public void setSocketClient(SlackSocketClient socketClient) {
        this.socketClient = socketClient;
    }

    @Override
    public void start() throws Exception {
        if (config.getBotToken() == null || config.getBotToken().isBlank()
                || config.getAppToken() == null || config.getAppToken().isBlank()) {
            throw new IllegalStateException("Slack bot/app token not configured");
        }
        if (!"socket".equalsIgnoreCase(config.getMode())) {
            throw new IllegalStateException("Unsupported Slack mode: " + config.getMode());
        }
        if (webClient == null || socketClient == null) {
            throw new IllegalStateException("Slack adapters not injected");
        }

        running = true;

        try {
            Map<String, Object> auth = webClient.authTest();
            Object uid = auth.get("user_id");
            if (uid != null) {
                botUserId = String.valueOf(uid);
            }
            System.out.println("Slack bot connected as " + botUserId);
        } catch (Exception e) {
            System.err.println("Slack auth_test failed: " + e.getMessage());
        }

        socketClient.connect(this::onSocketRequest);
    }

    @Override
    public void stop() throws Exception {
        running = false;
        if (socketClient != null) {
            socketClient.close();
        }
    }

    public void send(OutboundMessage msg) throws Exception {
        if (webClient == null) {
            return;
        }

        Map<String, Object> slackMeta = extractSlackMeta(msg.getMetadata());
        String threadTs = stringValue(slackMeta.get("thread_ts"));
        String channelType = stringValue(slackMeta.get("channel_type"));
        String threadTsParam = (!threadTs.isBlank() && !"im".equals(channelType)) ? threadTs : null;

        if ((msg.getContent() != null && !msg.getContent().isBlank()) || (msg.getMedia() == null || msg.getMedia().isEmpty())) {
            webClient.chatPostMessage(
                    msg.getChatId(),
                    msg.getContent() != null && !msg.getContent().isBlank() ? toMrkdwn(msg.getContent()) : " ",
                    threadTsParam
            );
        }

        if (msg.getMedia() != null) {
            for (String mediaPath : msg.getMedia()) {
                try {
                    webClient.filesUpload(msg.getChatId(), mediaPath, threadTsParam);
                } catch (Exception e) {
                    System.err.println("Failed to upload Slack file " + mediaPath + ": " + e.getMessage());
                }
            }
        }

        if (!Boolean.TRUE.equals(safeMeta(msg).get("_progress"))) {
            Map<String, Object> event = safeMap(slackMeta.get("event"));
            updateReactEmoji(msg.getChatId(), stringValue(event.get("ts")));
        }
    }

    // =========================================================
    // Inbound handling
    // =========================================================

    private void onSocketRequest(SlackSocketRequest req) {
        try {
            if (!"events_api".equals(req.getType())) {
                return;
            }

            socketClient.ack(req.getEnvelopeId());

            Map<String, Object> payload = req.getPayload() != null ? req.getPayload() : Collections.emptyMap();
            Map<String, Object> event = safeMap(payload.get("event"));
            String eventType = stringValue(event.get("type"));

            if (!"message".equals(eventType) && !"app_mention".equals(eventType)) {
                return;
            }

            String senderId = stringValue(event.get("user"));
            String chatId = stringValue(event.get("channel"));

            if (event.get("subtype") != null) {
                return;
            }
            if (botUserId != null && botUserId.equals(senderId)) {
                return;
            }

            String text = stringValue(event.get("text"));

            // 避免 message 和 app_mention 重复处理
            if ("message".equals(eventType) && botUserId != null && text.contains("<@" + botUserId + ">")) {
                return;
            }

            if (senderId.isBlank() || chatId.isBlank()) {
                return;
            }

            String channelType = stringValue(event.get("channel_type"));
            if (!isAllowed(senderId, chatId, channelType)) {
                return;
            }

            if (!"im".equals(channelType) && !shouldRespondInChannel(eventType, text, chatId)) {
                return;
            }

            text = stripBotMention(text);

            String threadTs = stringValue(event.get("thread_ts"));
            if (config.isReplyInThread() && threadTs.isBlank()) {
                threadTs = stringValue(event.get("ts"));
            }

            try {
                if (webClient != null && event.get("ts") != null) {
                    webClient.reactionsAdd(chatId, config.getReactEmoji(), stringValue(event.get("ts")));
                }
            } catch (Exception e) {
                System.err.println("Slack reactions_add failed: " + e.getMessage());
            }

            String sessionKey = (!threadTs.isBlank() && !"im".equals(channelType))
                    ? "slack:" + chatId + ":" + threadTs
                    : null;

            Map<String, Object> metadata = new HashMap<>();
            Map<String, Object> slack = new HashMap<>();
            slack.put("event", event);
            slack.put("thread_ts", threadTs);
            slack.put("channel_type", channelType);
            metadata.put("slack", slack);

            handleMessage(senderId, chatId, text, new ArrayList<>(), metadata, sessionKey);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateReactEmoji(String chatId, String ts) {
        if (webClient == null || ts == null || ts.isBlank()) {
            return;
        }
        try {
            webClient.reactionsRemove(chatId, config.getReactEmoji(), ts);
        } catch (Exception e) {
            System.err.println("Slack reactions_remove failed: " + e.getMessage());
        }
        if (config.getDoneEmoji() != null && !config.getDoneEmoji().isBlank()) {
            try {
                webClient.reactionsAdd(chatId, config.getDoneEmoji(), ts);
            } catch (Exception e) {
                System.err.println("Slack done reaction failed: " + e.getMessage());
            }
        }
    }

    private boolean isAllowed(String senderId, String chatId, String channelType) {
        if ("im".equals(channelType)) {
            if (!config.getDm().isEnabled()) {
                return false;
            }
            if ("allowlist".equalsIgnoreCase(config.getDm().getPolicy())) {
                return config.getDm().getAllowFrom().contains(senderId);
            }
            return true;
        }

        if ("allowlist".equalsIgnoreCase(config.getGroupPolicy())) {
            return config.getGroupAllowFrom().contains(chatId);
        }
        return true;
    }

    private boolean shouldRespondInChannel(String eventType, String text, String chatId) {
        if ("open".equalsIgnoreCase(config.getGroupPolicy())) {
            return true;
        }
        if ("mention".equalsIgnoreCase(config.getGroupPolicy())) {
            if ("app_mention".equals(eventType)) {
                return true;
            }
            return botUserId != null && text != null && text.contains("<@" + botUserId + ">");
        }
        if ("allowlist".equalsIgnoreCase(config.getGroupPolicy())) {
            return config.getGroupAllowFrom().contains(chatId);
        }
        return false;
    }

    private String stripBotMention(String text) {
        if (text == null || botUserId == null) {
            return text == null ? "" : text;
        }
        return text.replaceAll("<@" + Pattern.quote(botUserId) + ">\\s*", "").trim();
    }

    // =========================================================
    // Markdown -> mrkdwn
    // =========================================================

    private static final Pattern TABLE_RE = Pattern.compile("(?m)^\\|.*\\|$(?:\\n\\|[\\s:|\\-]*\\|$)(?:\\n\\|.*\\|$)*");
    private static final Pattern LEFTOVER_BOLD_RE = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern LEFTOVER_HEADER_RE = Pattern.compile("^#{1,6}\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern BARE_URL_RE = Pattern.compile("(?<![|<])(https?://\\S+)");

    public static String toMrkdwn(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        text = convertTables(text);
        text = text.replaceAll("```([\\s\\S]*?)```", "```$1```");
        text = LEFTOVER_BOLD_RE.matcher(text).replaceAll("*$1*");
        text = LEFTOVER_HEADER_RE.matcher(text).replaceAll("*$1*");
        text = BARE_URL_RE.matcher(text).replaceAll("<$1>");
        return text;
    }

    private static String convertTables(String text) {
        Matcher m = TABLE_RE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String table = m.group();
            String converted = tableToCodeBlock(table);
            m.appendReplacement(sb, Matcher.quoteReplacement(converted));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String tableToCodeBlock(String table) {
        String[] lines = table.split("\\R");
        List<List<String>> rows = new ArrayList<>();
        boolean hasSep = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
            if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);

            String[] cells = trimmed.split("\\|", -1);
            List<String> row = new ArrayList<>();
            boolean sep = true;

            for (String cell : cells) {
                String c = stripMd(cell.trim());
                row.add(c);
                if (!c.matches(":?-+:?")) {
                    sep = false;
                }
            }
            if (sep) {
                hasSep = true;
                continue;
            }
            rows.add(row);
        }

        if (rows.isEmpty() || !hasSep) {
            return table;
        }

        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        for (List<String> row : rows) {
            while (row.size() < cols) row.add("");
        }

        int[] widths = new int[cols];
        for (List<String> row : rows) {
            for (int i = 0; i < cols; i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }

        List<String> out = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < cols; i++) {
                cells.add(String.format("%-" + widths[i] + "s", row.get(i)));
            }
            out.add(String.join("  ", cells));
            if (r == 0) {
                List<String> sep = new ArrayList<>();
                for (int w : widths) sep.add("-".repeat(Math.max(1, w)));
                out.add(String.join("  ", sep));
            }
        }

        return "```" + String.join("\n", out) + "```";
    }

    private static String stripMd(String s) {
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("__(.+?)__", "$1")
                .replaceAll("~~(.+?)~~", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .trim();
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }

    private static Map<String, Object> safeMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        }
        return new HashMap<>();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> safeMeta(OutboundMessage msg) {
        return msg.getMetadata() != null ? msg.getMetadata() : Collections.emptyMap();
    }

    private static Map<String, Object> extractSlackMeta(Map<String, Object> metadata) {
        if (metadata == null) return Collections.emptyMap();
        Object value = metadata.get("slack");
        return safeMap(value);
    }
}