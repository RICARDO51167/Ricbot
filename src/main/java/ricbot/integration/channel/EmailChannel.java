package ricbot.integration.channel;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.FlagTerm;
import lombok.extern.slf4j.Slf4j;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.integration.channel.event.CommandEvent;
import ricbot.integration.channel.event.IncomingMessageEvent;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Email 渠道实现。
 */
@Slf4j
public class EmailChannel extends BaseChannel {

    // 内部静态类，用于存储 Email 渠道的配置信息
    public static class EmailConfig {
        private boolean enabled = false; // 是否启用 Email 渠道
        private boolean consentGranted = false; // 用户是否已授予同意

        private String imapHost = ""; // IMAP 服务器主机地址
        private int imapPort = 993; // IMAP 服务器端口，默认为 993 (SSL)
        private String imapUsername = ""; // IMAP 登录用户名
        private String imapPassword = ""; // IMAP 登录密码
        private String imapMailbox = "INBOX"; // IMAP 邮箱文件夹名称，默认为 INBOX
        private boolean imapUseSsl = true; // IMAP 连接是否使用 SSL

        private String smtpHost = ""; // SMTP 服务器主机地址
        private int smtpPort = 587; // SMTP 服务器端口，默认为 587 (TLS)
        private String smtpUsername = ""; // SMTP 登录用户名
        private String smtpPassword = ""; // SMTP 登录密码
        private boolean smtpUseTls = true; // SMTP 连接是否使用 TLS
        private boolean smtpUseSsl = false; // SMTP 连接是否使用 SSL
        private String fromAddress = ""; // 发件人地址

        private boolean autoReplyEnabled = true; // 是否启用自动回复（当前代码未直接使用此字段，保留配置）
        private int pollIntervalSeconds = 30; // 轮询新邮件的时间间隔（秒）
        private boolean markSeen = true; // 读取邮件后是否标记为已读
        private int maxBodyChars = 12000; // 邮件正文最大字符数限制（当前代码未直接使用此字段，保留配置）
        private String subjectPrefix = "Re: "; // 回复邮件的主题前缀
        private List<String> allowFrom = new ArrayList<>(); // 允许接收邮件的发件人列表

        // 获取是否启用
        public boolean isEnabled() { return enabled; }
        // 设置是否启用
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        // 获取是否已授予同意
        public boolean isConsentGranted() { return consentGranted; }
        // 设置是否已授予同意
        public void setConsentGranted(boolean consentGranted) { this.consentGranted = consentGranted; }
        // 获取 IMAP 主机地址
        public String getImapHost() { return imapHost; }
        // 设置 IMAP 主机地址
        public void setImapHost(String imapHost) { this.imapHost = imapHost; }
        // 获取 IMAP 端口
        public int getImapPort() { return imapPort; }
        // 设置 IMAP 端口
        public void setImapPort(int imapPort) { this.imapPort = imapPort; }
        // 获取 IMAP 用户名
        public String getImapUsername() { return imapUsername; }
        // 设置 IMAP 用户名
        public void setImapUsername(String imapUsername) { this.imapUsername = imapUsername; }
        // 获取 IMAP 密码
        public String getImapPassword() { return imapPassword; }
        // 设置 IMAP 密码
        public void setImapPassword(String imapPassword) { this.imapPassword = imapPassword; }
        // 获取 IMAP 邮箱文件夹
        public String getImapMailbox() { return imapMailbox; }
        // 设置 IMAP 邮箱文件夹
        public void setImapMailbox(String imapMailbox) { this.imapMailbox = imapMailbox; }
        // 获取 IMAP 是否使用 SSL
        public boolean isImapUseSsl() { return imapUseSsl; }
        // 设置 IMAP 是否使用 SSL
        public void setImapUseSsl(boolean imapUseSsl) { this.imapUseSsl = imapUseSsl; }
        // 获取 SMTP 主机地址
        public String getSmtpHost() { return smtpHost; }
        // 设置 SMTP 主机地址
        public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }
        // 获取 SMTP 端口
        public int getSmtpPort() { return smtpPort; }
        // 设置 SMTP 端口
        public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }
        // 获取 SMTP 用户名
        public String getSmtpUsername() { return smtpUsername; }
        // 设置 SMTP 用户名
        public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }
        // 获取 SMTP 密码
        public String getSmtpPassword() { return smtpPassword; }
        // 设置 SMTP 密码
        public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }
        // 获取 SMTP 是否使用 TLS
        public boolean isSmtpUseTls() { return smtpUseTls; }
        // 设置 SMTP 是否使用 TLS
        public void setSmtpUseTls(boolean smtpUseTls) { this.smtpUseTls = smtpUseTls; }
        // 获取 SMTP 是否使用 SSL
        public boolean isSmtpUseSsl() { return smtpUseSsl; }
        // 设置 SMTP 是否使用 SSL
        public void setSmtpUseSsl(boolean smtpUseSsl) { this.smtpUseSsl = smtpUseSsl; }
        // 获取发件人地址
        public String getFromAddress() { return fromAddress; }
        // 设置发件人地址
        public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
        // 获取轮询间隔秒数
        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        // 设置轮询间隔秒数
        public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
        // 获取是否标记为已读
        public boolean isMarkSeen() { return markSeen; }
        // 设置是否标记为已读
        public void setMarkSeen(boolean markSeen) { this.markSeen = markSeen; }
        // 获取允许的发件人列表
        public List<String> getAllowFrom() { return allowFrom; }
        // 设置允许的发件人列表
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
    }

    private final EmailConfig config; // Email 配置对象
    private ScheduledExecutorService scheduler; // 定时任务调度器，用于定期拉取邮件
    private final Map<String, String> lastSubjectByChat = new HashMap<>(); // 记录每个聊天 ID（发件人）最后的邮件主题，用于回复时保持主题连贯

    /**
     * 构造函数
     * @param config 配置对象，期望是 EmailConfig 类型
     * @param bus 消息总线，用于发布事件
     */
    public EmailChannel(Object config, MessageBus bus) {
        super(config, bus); // 调用父类构造函数
        this.name = "email"; // 设置渠道名称为 email
        this.displayName = "Email"; // 设置显示名称为 Email
        // 如果传入的配置是 EmailConfig 类型则使用，否则创建一个新的默认配置
        this.config = (config instanceof EmailConfig c) ? c : new EmailConfig();
    }

    /**
     * 获取允许的发件人列表
     * @return 允许的发件人邮箱地址列表
     */
    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }

    /**
     * 启动 Email 渠道
     * 如果配置未启用或未获得同意，则不执行任何操作
     * 否则，启动定时任务定期拉取新邮件
     */
    @Override
    public void start() throws Exception {
        if (!config.isConsentGranted() || !config.isEnabled()) {
            return; // 如果未授权或未启用，直接返回
        }

        // 创建单线程调度执行器
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        // 安排定期任务：立即执行第一次，然后每隔 pollIntervalSeconds 秒执行一次 fetchNewMessages
        this.scheduler.scheduleWithFixedDelay(this::fetchNewMessages, 0, config.getPollIntervalSeconds(), TimeUnit.SECONDS);
        this.running = true; // 标记渠道为运行状态
    }

    /**
     * 停止 Email 渠道
     * 关闭调度器并标记渠道为非运行状态
     */
    @Override
    public void stop() throws Exception {
        if (scheduler != null) {
            scheduler.shutdown(); // 关闭调度器，不再接受新任务
        }
        this.running = false; // 标记渠道为非运行状态
    }

    /**
     * 发送出站消息（回复邮件）
     * @param msg 要发送的出站消息对象
     */
    @Override
    public void send(OutboundMessage msg) throws Exception {
        // 创建 SMTP 属性配置
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true"); // 启用 SMTP 认证
        props.put("mail.smtp.starttls.enable", config.isSmtpUseTls() ? "true" : "false"); // 根据配置启用 STARTTLS
        props.put("mail.smtp.host", config.getSmtpHost()); // 设置 SMTP 主机
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort())); // 设置 SMTP 端口
        if (config.isSmtpUseSsl()) {
            props.put("mail.smtp.ssl.enable", "true"); // 如果配置了 SSL，则启用 SMTP SSL
        }

        // 创建带有身份验证的会话
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                // 返回 SMTP 用户名和密码进行认证
                return new PasswordAuthentication(config.getSmtpUsername(), config.getSmtpPassword());
            }
        });

        // 创建 MIME 消息对象
        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.getFromAddress())); // 设置发件人
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(msg.getChatId())); // 设置收件人（chatId 即为邮箱地址）
        
        // 获取之前的主题，如果没有则使用默认主题
        String subject = lastSubjectByChat.getOrDefault(msg.getChatId(), "Re: Nanobot 回复");
        // 确保主题以 "Re: " 开头
        if (!subject.startsWith("Re: ")) subject = "Re: " + subject;
        message.setSubject(subject); // 设置邮件主题
        message.setText(msg.getContent()); // 设置邮件正文内容

        Transport.send(message); // 发送邮件
    }

    /**
     * 拉取新邮件的方法，由定时任务调用
     */
    private void fetchNewMessages() {
        try {
            // 创建 IMAP 属性配置
            Properties props = new Properties();
            props.setProperty("mail.store.protocol", "imaps"); // 使用 imaps 协议
            props.setProperty("mail.imaps.host", config.getImapHost()); // 设置 IMAP 主机
            props.setProperty("mail.imaps.port", String.valueOf(config.getImapPort())); // 设置 IMAP 端口
            props.setProperty("mail.imaps.ssl.enable", "true"); // 启用 IMAP SSL

            // 获取默认会话实例
            Session session = Session.getDefaultInstance(props, null);
            Store store = session.getStore("imaps"); // 获取 IMAP 存储对象
            store.connect(config.getImapUsername(), config.getImapPassword()); // 连接到 IMAP 服务器

            Folder folder = store.getFolder(config.getImapMailbox()); // 获取指定的邮箱文件夹
            folder.open(Folder.READ_WRITE); // 以读写模式打开文件夹

            // 搜索所有未读（未见）的邮件
            Message[] messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            // 遍历每一封未读邮件
            for (Message m : messages) {
                // 获取发件人地址
                String from = ((InternetAddress) m.getFrom()[0]).getAddress();
                String content = "";
                // 检查邮件内容类型
                if (m.isMimeType("text/plain")) {
                    content = m.getContent().toString(); // 如果是纯文本，直接获取内容
                } else {
                    // 简化处理，非纯文本内容暂时标记为占位符
                    content = "[HTML/复杂内容]";
                }

                // 记录该发件人的最后邮件主题，用于后续回复
                lastSubjectByChat.put(from, m.getSubject());
                
                // 创建元数据映射
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("subject", m.getSubject()); // 添加主题到元数据
                String messageId = null;
                try {
                    // 尝试获取 Message-ID 头
                    String[] headers = m.getHeader("Message-ID");
                    if (headers != null && headers.length > 0) {
                        messageId = headers[0]; // 获取第一个 Message-ID
                    }
                } catch (Exception ignored) {
                    // 忽略获取 Message-ID 时的异常
                }
                if (messageId != null) {
                    metadata.put("message_id", messageId); // 如果获取到 Message-ID，添加到元数据
                }

                // 解析命令
                ChannelParsedCommand cmd = parseCommand(content);
                if (cmd != null) {
                    // 如果解析出命令，发布 CommandEvent 事件
                    publishEvent(new CommandEvent(
                            getName(), // 渠道名称
                            from, // 发件人
                            from, // 发送者 ID
                            from, // 聊天 ID
                            cmd.command(), // 命令名
                            cmd.args(), // 命令参数
                            metadata, // 元数据
                            null, // 附件列表（暂无）
                            messageId, // 消息 ID
                            LocalDateTime.now() // 时间戳
                    ));
                } else {
                    // 如果不是命令，发布 IncomingMessageEvent 事件
                    publishEvent(new IncomingMessageEvent(
                            getName(), // 渠道名称
                            from, // 发件人
                            from, // 发送者 ID
                            from, // 聊天 ID
                            content, // 消息内容
                            List.of(), // 附件列表（暂无）
                            metadata, // 元数据
                            null, // 回复消息 ID（暂无）
                            messageId, // 消息 ID
                            LocalDateTime.now() // 时间戳
                    ));
                }

                // 如果配置要求标记为已读，则设置 SEEN 标志
                if (config.isMarkSeen()) {
                    m.setFlag(Flags.Flag.SEEN, true);
                }
            }

            folder.close(true); // 关闭文件夹，true 表示保存更改（如标记已读）
            store.close(); // 关闭存储连接
        } catch (Exception e) {
            log.error("邮件获取错误: {}", e.getMessage(), e);
        }
    }

    /**
     * 解析文本中的命令
     * 命令格式以 "/" 开头，例如 "/help" 或 "/cmd arg1 arg2"
     * @param text 待解析的文本
     * @return 解析后的命令对象，如果不是命令则返回 null
     */
    private static ChannelParsedCommand parseCommand(String text) {
        if (text == null) {
            return null; // 文本为空，返回 null
        }
        String s = text.trim(); // 去除首尾空白
        if (!s.startsWith("/")) {
            return null; // 不以 "/" 开头，不是命令
        }
        s = s.substring(1).trim(); // 去掉 "/" 并再次去除空白
        if (s.isEmpty()) {
            return null; // 去掉 "/" 后为空，不是有效命令
        }
        int idx = s.indexOf(' '); // 查找第一个空格的位置
        if (idx < 0) {
            // 没有空格，整个字符串就是命令名，参数为空
            return new ChannelParsedCommand(s, "");
        }
        // 有空格，分割命令名和参数
        return new ChannelParsedCommand(s.substring(0, idx).trim(), s.substring(idx + 1).trim());
    }

    /**
     * 记录解析后的命令
     * @param command 命令名
     * @param args 命令参数
     */
    private record ChannelParsedCommand(String command, String args) {
    }
}
