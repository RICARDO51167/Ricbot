package ricbot.transport.channel;

import ricbot.core.message.MessageBus;
import ricbot.core.message.OutboundMessage;
import ricbot.llm.api.GroqTranscriptionProvider;
import ricbot.llm.api.OpenAITranscriptionProvider;
import ricbot.llm.api.TranscriptionProvider;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Channel 抽象基类
 */
public abstract class BaseChannel {

    /**
     * 渠道配置
     */
    protected final Object channelConfig;

    /**
     * 消息总线
     */
    protected final MessageBus bus;

    protected String name;
    protected String displayName;
    protected boolean running = false;
    protected String transcriptionProvider;
    protected String transcriptionApiKey;

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

    protected String getStringConfig(String key) {
        if (channelConfig instanceof Map<?, ?> m) {
            Object v = m.get(key);
            return v != null ? String.valueOf(v) : null;
        }
        return null;
    }

    /**
     * 处理入站消息。
     */
    protected void onMessage(
            String content,
            String chatId,
            String senderId,
            String senderName,
            Map<String, Object> metadata
    ) {
        try {
            ricbot.core.message.InboundMessage msg = new ricbot.core.message.InboundMessage();
            msg.setChannel(getName());
            msg.setChatId(chatId);
            msg.setSenderId(senderId);
            msg.setContent(content);
            msg.setTimestamp(java.time.LocalDateTime.now());
            
            if (metadata == null) metadata = new HashMap<>();
            metadata.put("sender_name", senderName);
            msg.setMetadata(metadata);
            
            bus.publishInbound(msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Error processing message from " + getName() + ": " + e.getMessage());
        }
    }

    /**
     * 处理入站消息 (legacy)。
     */
    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata
    ) throws Exception {
        handleMessage(senderId, chatId, content, media, metadata, null);
    }

    /**
     * 处理入站消息 (带 sessionKeyOverride)。
     */
    protected void handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String sessionKeyOverride
    ) throws Exception {
        ricbot.core.message.InboundMessage msg = new ricbot.core.message.InboundMessage();
        msg.setChannel(getName());
        msg.setSenderId(senderId);
        msg.setChatId(chatId);
        msg.setContent(content);
        msg.setMedia(media);
        msg.setMetadata(metadata);
        msg.setSessionKeyOverride(sessionKeyOverride);
        bus.publishInbound(msg);
    }

    /**
     * 发送增量。
     */
    public void sendDelta(String chatId, String delta, Map<String, Object> metadata) throws Exception {
        // 默认不支持流式，子类可重写
    }

    public abstract void send(OutboundMessage msg) throws Exception;

    // =========================================================
    // Audio transcription
    // =========================================================

    public String transcribeAudio(Path filePath) {
        if (filePath == null) {
            return "";
        }

        String providerName = resolveTranscriptionProviderName();
        TranscriptionProvider provider = buildTranscriptionProvider(providerName);

        if (provider == null) {
            System.err.println("No transcription provider available for channel: " + getName());
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
                    new OpenAITranscriptionProvider(transcriptionApiKey);

            case "groq", "groq_whisper" ->
                    new GroqTranscriptionProvider(transcriptionApiKey);

            default -> {
                System.err.println("Unsupported transcription provider: " + providerName + ", fallback to groq");
                yield new GroqTranscriptionProvider(transcriptionApiKey);
            }
        };
    }
}