package ricbot.integration.channel;

import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;
import ricbot.domain.message.OutboundMessage;
import ricbot.integration.llm.api.GroqTranscriptionProvider;
import ricbot.integration.llm.openai.OpenAITranscriptionProvider;
import ricbot.integration.llm.api.TranscriptionProvider;
import ricbot.integration.channel.event.ChannelEvent;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Channel 抽象基类
 */
public abstract class BaseChannel {

    protected final Object channelConfig;

    protected final MessageBus bus;

    protected String name;
    protected String displayName;
    protected boolean running = false;
    protected String transcriptionProvider;
    protected String transcriptionApiKey;
    protected String transcriptionApiBase;

    private final ArrayDeque<String> recentEventIds = new ArrayDeque<>();
    private final Set<String> recentEventIdSet = new HashSet<>();

    protected BaseChannel(
            Object channelConfig,
            MessageBus bus
    ) {
        this.channelConfig = channelConfig;
        this.bus = bus;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isRunning() {
        return running;
    }

    public abstract void start() throws Exception;

    public abstract void stop() throws Exception;

    public abstract List<String> getAllowFrom();

    public void setTranscriptionProvider(String provider) {
        this.transcriptionProvider = provider;
    }

    public void setTranscriptionApiKey(String apiKey) {
        this.transcriptionApiKey = apiKey;
    }

    public void setTranscriptionApiBase(String apiBase) {
        this.transcriptionApiBase = apiBase;
    }

    protected String getStringConfig(String key) {
        if (channelConfig instanceof Map<?, ?> m) {
            Object v = m.get(key);
            return v != null ? String.valueOf(v) : null;
        }
        return null;
    }

    protected void onMessage(
            String content,
            String chatId,
            String senderId,
            String senderName,
            Map<String, Object> metadata
    ) {
        try {
            handleMessage(senderId, chatId, content, null, metadata, senderName, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Error processing message from " + getName() + ": " + e.getMessage());
        }
    }

    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata
    ) throws Exception {
        handleMessage(senderId, chatId, content, media, metadata, null);
    }

    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String sessionKeyOverride
    ) throws Exception {
        handleMessage(senderId, chatId, content, media, metadata, null, sessionKeyOverride);
    }

    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String senderName,
            String sessionKeyOverride
    ) throws Exception {
        InboundMessage msg = new InboundMessage();
        msg.setChannel(getName());
        msg.setSenderId(senderId);
        msg.setChatId(chatId);
        msg.setContent(content);
        msg.setMedia(media);
        msg.setTimestamp(java.time.LocalDateTime.now());

        Map<String, Object> m = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        if (senderName != null && !senderName.isBlank()) {
            m.putIfAbsent("sender_name", senderName);
        }
        msg.setMetadata(m);
        msg.setSessionKeyOverride(sessionKeyOverride);
        bus.publishInbound(msg);
    }

    protected void publishEvent(ChannelEvent event) throws Exception {
        if (event == null) {
            return;
        }
        if (!acceptEvent(event.eventId())) {
            return;
        }

        InboundMessage msg = event.toInboundMessage();
        if (msg.getChannel() == null || msg.getChannel().isBlank()) {
            msg.setChannel(getName());
        }
        if (msg.getTimestamp() == null) {
            msg.setTimestamp(LocalDateTime.now());
        }
        bus.publishInbound(msg);
    }

    private synchronized boolean acceptEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        if (recentEventIdSet.contains(eventId)) {
            return false;
        }
        recentEventIds.addLast(eventId);
        recentEventIdSet.add(eventId);
        
        int max = 5000;
        while (recentEventIds.size() > max) {
            String removed = recentEventIds.removeFirst();
            recentEventIdSet.remove(removed);
        }
        return true;
    }

    public void sendDelta(String chatId, String delta, Map<String, Object> metadata) throws Exception {
    }

    public abstract void send(OutboundMessage msg) throws Exception;

    public String transcribeAudio(Path filePath) {
        if (filePath == null) {
            return "";
        }

        String providerName = resolveTranscriptionProviderName();
        TranscriptionProvider provider = buildTranscriptionProvider(providerName);

        if (provider == null) {
            System.err.println("当前渠道没有可用的语音转写 provider：" + getName());
            return "";
        }

        return provider.transcribe(filePath);
    }

    protected String resolveTranscriptionProviderName() {
        if (transcriptionProvider != null && !transcriptionProvider.isBlank()) {
            return transcriptionProvider;
        }
        return "groq";
    }

    protected TranscriptionProvider buildTranscriptionProvider(String providerName) {
        String name = providerName != null
                ? providerName.trim().toLowerCase(java.util.Locale.ROOT)
                : "groq";

        return switch (name) {
            case "openai", "whisper", "openai_whisper" ->
                    new OpenAITranscriptionProvider(transcriptionApiKey, transcriptionApiBase);

            case "groq", "groq_whisper" ->
                    new GroqTranscriptionProvider(transcriptionApiKey, transcriptionApiBase);

            default -> {
                System.err.println("不支持的语音转写 provider：" + providerName + "，将回退到 groq");
                yield new GroqTranscriptionProvider(transcriptionApiKey, transcriptionApiBase);
            }
        };
    }
}
