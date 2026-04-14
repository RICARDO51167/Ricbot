package ricbot.transport.channel;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.FlagTerm;
import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Email 渠道实现。
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
        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
        public boolean isMarkSeen() { return markSeen; }
        public void setMarkSeen(boolean markSeen) { this.markSeen = markSeen; }
        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
    }

    private final EmailConfig config;
    private ScheduledExecutorService scheduler;
    private final Map<String, String> lastSubjectByChat = new HashMap<>();

    public EmailChannel(Object config, MessageBus bus) {
        super(config, bus);
        this.name = "email";
        this.displayName = "Email";
        this.config = (config instanceof EmailConfig c) ? c : new EmailConfig();
    }

    @Override
    public List<String> getAllowFrom() {
        return config.getAllowFrom();
    }

    @Override
    public void start() throws Exception {
        if (!config.isConsentGranted() || !config.isEnabled()) {
            return;
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.scheduler.scheduleWithFixedDelay(this::fetchNewMessages, 0, config.getPollIntervalSeconds(), TimeUnit.SECONDS);
        this.running = true;
    }

    @Override
    public void stop() throws Exception {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        this.running = false;
    }

    @Override
    public void send(OutboundMessage msg) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", config.isSmtpUseTls() ? "true" : "false");
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        if (config.isSmtpUseSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
        }

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getSmtpUsername(), config.getSmtpPassword());
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.getFromAddress()));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(msg.getChatId()));
        
        String subject = lastSubjectByChat.getOrDefault(msg.getChatId(), "Re: Nanobot Response");
        if (!subject.startsWith("Re: ")) subject = "Re: " + subject;
        message.setSubject(subject);
        message.setText(msg.getContent());

        Transport.send(message);
    }

    private void fetchNewMessages() {
        try {
            Properties props = new Properties();
            props.setProperty("mail.store.protocol", "imaps");
            props.setProperty("mail.imaps.host", config.getImapHost());
            props.setProperty("mail.imaps.port", String.valueOf(config.getImapPort()));
            props.setProperty("mail.imaps.ssl.enable", "true");

            Session session = Session.getDefaultInstance(props, null);
            Store store = session.getStore("imaps");
            store.connect(config.getImapUsername(), config.getImapPassword());

            Folder folder = store.getFolder(config.getImapMailbox());
            folder.open(Folder.READ_WRITE);

            Message[] messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            for (Message m : messages) {
                String from = ((InternetAddress) m.getFrom()[0]).getAddress();
                String content = "";
                if (m.isMimeType("text/plain")) {
                    content = m.getContent().toString();
                } else {
                    // 简化处理，只处理纯文本
                    content = "[HTML/Complex Content]";
                }

                lastSubjectByChat.put(from, m.getSubject());
                
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("subject", m.getSubject());
                metadata.put("message_id", m.getHeader("Message-ID")[0]);

                this.onMessage(content, from, from, from, metadata);

                if (config.isMarkSeen()) {
                    m.setFlag(Flags.Flag.SEEN, true);
                }
            }

            folder.close(true);
            store.close();
        } catch (Exception e) {
            System.err.println("Email fetch error: " + e.getMessage());
        }
    }
}
