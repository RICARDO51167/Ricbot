package ricbot.transport.channel;

import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.util.*;

/**
 * Email 渠道实现。
 *
 * 主要目标：
 * 1. 通过 IMAP 轮询收取未读邮件
 * 2. 把邮件解析成统一入站消息
 * 3. 通过 SMTP 回复邮件
 * 4. 支持附件、认证校验、自动回复控制
 */
public class EmailChannel extends BaseChannel {

    public static class EmailConfig {
        private boolean enabled = false;
        private boolean consentGranted = false;

        private String imapHost = "";
        private int imapPort = 993;
        private String imapUsername = "";
        private String imapPassword = "";
        private String imapMailbox = "INBOX";
        private boolean imapUseSsl = true;

        private String smtpHost = "";
        private int smtpPort = 587;
        private String smtpUsername = "";
        private String smtpPassword = "";
        private boolean smtpUseTls = true;
        private boolean smtpUseSsl = false;
        private String fromAddress = "";

        private boolean autoReplyEnabled = true;
        private int pollIntervalSeconds = 30;
        private boolean markSeen = true;
        private int maxBodyChars = 12000;
        private String subjectPrefix = "Re: ";
        private List<String> allowFrom = new ArrayList<>();

        private boolean verifyDkim = true;
        private boolean verifySpf = true;

        private List<String> allowedAttachmentTypes = new ArrayList<>();
        private int maxAttachmentSize = 2_000_000;
        private int maxAttachmentsPerEmail = 5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isConsentGranted() { return consentGranted; }
        public void setConsentGranted(boolean consentGranted) { this.consentGranted = consentGranted; }

        public String getImapHost() { return imapHost; }
        public void setImapHost(String imapHost) { this.imapHost = imapHost; }

        public int getImapPort() { return imapPort; }
        public void setImapPort(int imapPort) { this.imapPort = imapPort; }

        public String getImapUsername() { return imapUsername; }
        public void setImapUsername(String imapUsername) { this.imapUsername = imapUsername; }

        public String getImapPassword() { return imapPassword; }
        public void setImapPassword(String imapPassword) { this.imapPassword = imapPassword; }

        public String getImapMailbox() { return imapMailbox; }
        public void setImapMailbox(String imapMailbox) { this.imapMailbox = imapMailbox; }

        public boolean isImapUseSsl() { return imapUseSsl; }
        public void setImapUseSsl(boolean imapUseSsl) { this.imapUseSsl = imapUseSsl; }

        public String getSmtpHost() { return smtpHost; }
        public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

        public int getSmtpPort() { return smtpPort; }
        public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }

        public String getSmtpUsername() { return smtpUsername; }
        public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }

        public String getSmtpPassword() { return smtpPassword; }
        public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }

        public boolean isSmtpUseTls() { return smtpUseTls; }
        public void setSmtpUseTls(boolean smtpUseTls) { this.smtpUseTls = smtpUseTls; }

        public boolean isSmtpUseSsl() { return smtpUseSsl; }
        public void setSmtpUseSsl(boolean smtpUseSsl) { this.smtpUseSsl = smtpUseSsl; }

        public String getFromAddress() { return fromAddress; }
        public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }

        public boolean isAutoReplyEnabled() { return autoReplyEnabled; }
        public void setAutoReplyEnabled(boolean autoReplyEnabled) { this.autoReplyEnabled = autoReplyEnabled; }

        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }

        public boolean isMarkSeen() { return markSeen; }
        public void setMarkSeen(boolean markSeen) { this.markSeen = markSeen; }

        public int getMaxBodyChars() { return maxBodyChars; }
        public void setMaxBodyChars(int maxBodyChars) { this.maxBodyChars = maxBodyChars; }

        public String getSubjectPrefix() { return subjectPrefix; }
        public void setSubjectPrefix(String subjectPrefix) { this.subjectPrefix = subjectPrefix; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }

        public boolean isVerifyDkim() { return verifyDkim; }
        public void setVerifyDkim(boolean verifyDkim) { this.verifyDkim = verifyDkim; }

        public boolean isVerifySpf() { return verifySpf; }
        public void setVerifySpf(boolean verifySpf) { this.verifySpf = verifySpf; }

        public List<String> getAllowedAttachmentTypes() { return allowedAttachmentTypes; }
        public void setAllowedAttachmentTypes(List<String> allowedAttachmentTypes) { this.allowedAttachmentTypes = allowedAttachmentTypes; }

        public int getMaxAttachmentSize() { return maxAttachmentSize; }
        public void setMaxAttachmentSize(int maxAttachmentSize) { this.maxAttachmentSize = maxAttachmentSize; }

        public int getMaxAttachmentsPerEmail() { return maxAttachmentsPerEmail; }
        public void setMaxAttachmentsPerEmail(int maxAttachmentsPerEmail) { this.maxAttachmentsPerEmail = maxAttachmentsPerEmail; }
    }

    private final EmailConfig config;

    /**
     * 最近一次收到的邮件 subject，按 chat（邮箱地址）记。
     */
    private final Map<String, String> lastSubjectByChat = new HashMap<>();

    /**
     * 最近一次收到的 message-id，按 chat 记。
     */
    private final Map<String, String> lastMessageIdByChat = new HashMap<>();

    /**
     * 已处理过的 UID 集合，避免重复处理。
     */
    private final Set<String> processedUids = new LinkedHashSet<>();
    private static final int MAX_PROCESSED_UIDS = 100_000;

    public EmailChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "email";
        this.displayName = "Email";
        this.config = (config instanceof EmailConfig c) ? c : new EmailConfig();
    }

    @Override
    public void start() throws Exception {
        if (!config.isConsentGranted()) {
            System.err.println("Email channel disabled: consent_granted is false");
            return;
        }

        if (!validateConfig()) {
            return;
        }

        running = true;

        if (!config.isVerifyDkim() && !config.isVerifySpf()) {
            System.err.println("Warning: DKIM and SPF verification are both disabled");
        }

        System.out.println("Starting Email channel (IMAP polling mode)...");

        // TODO:
        // 1. 启动轮询线程/任务
        // 2. 周期性调用 fetchNewMessages()
        // 3. 每封邮件转成统一消息给 Agent
    }

    @Override
    public void stop() throws Exception {
        running = false;
    }

    @Override
    public void send(OutboundMessage msg) throws Exception {
        if (!config.isConsentGranted()) {
            System.err.println("Skip email send: consent_granted is false");
            return;
        }

        if (config.getSmtpHost() == null || config.getSmtpHost().isBlank()) {
            System.err.println("Email SMTP host not configured");
            return;
        }

        String toAddr = msg.getChatId() != null ? msg.getChatId().trim() : "";
        if (toAddr.isBlank()) {
            System.err.println("Email missing recipient address");
            return;
        }

        boolean isReply = lastSubjectByChat.containsKey(toAddr);
        boolean forceSend = msg.getMetadata() != null && Boolean.TRUE.equals(msg.getMetadata().get("force_send"));

        if (isReply && !config.isAutoReplyEnabled() && !forceSend) {
            System.out.println("Skip email auto-reply because autoReplyEnabled=false");
            return;
        }

        // TODO:
        // 1. 构造 MimeMessage / EmailMessage
        // 2. 设置 subject / In-Reply-To / References
        // 3. 添加附件
        // 4. SMTP 发送

        System.out.println("Email send -> " + toAddr + ": " + msg.getContent());
    }

    /**
     * 校验基础配置。
     */
    private boolean validateConfig() {
        if (config.getImapHost() == null || config.getImapHost().isBlank()) {
            System.err.println("Email IMAP host not configured");
            return false;
        }
        if (config.getSmtpHost() == null || config.getSmtpHost().isBlank()) {
            System.err.println("Email SMTP host not configured");
            return false;
        }
        return true;
    }

    /**
     * 拉取新邮件。
     *
     * 返回值中的每一项，后续都要转成：
     * - sender
     * - subject
     * - message_id
     * - content
     * - media
     * - metadata
     */
    public List<Map<String, Object>> fetchNewMessages() {
        // TODO:
        // 1. IMAP 登录
        // 2. 选中 INBOX / mailbox
        // 3. 搜索未读邮件
        // 4. 解析正文 / 附件 / 认证头
        // 5. 构造成统一结构
        return new ArrayList<>();
    }

    /**
     * 处理一封已解析邮件。
     */
    public void handleParsedEmail(Map<String, Object> item) throws Exception {
        String sender = String.valueOf(item.getOrDefault("sender", ""));
        String subject = String.valueOf(item.getOrDefault("subject", ""));
        String messageId = String.valueOf(item.getOrDefault("message_id", ""));
        String content = String.valueOf(item.getOrDefault("content", ""));

        if (!subject.isBlank()) {
            lastSubjectByChat.put(sender, subject);
        }
        if (!messageId.isBlank()) {
            lastMessageIdByChat.put(sender, messageId);
        }

        @SuppressWarnings("unchecked")
        List<String> media = (List<String>) item.getOrDefault("media", new ArrayList<>());

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) item.getOrDefault("metadata", new HashMap<>());

        handleMessage(sender, sender, content, media, metadata);
    }

    /**
     * 记录已处理 UID，防止无界增长。
     */
    public void markProcessedUid(String uid) {
        processedUids.add(uid);
        if (processedUids.size() > MAX_PROCESSED_UIDS) {
            Iterator<String> it = processedUids.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }
}