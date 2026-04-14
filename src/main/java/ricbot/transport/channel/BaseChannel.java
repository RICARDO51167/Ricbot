package ricbot.transport.channel;

import ricbot.core.message.MessageBus;
import ricbot.infra.config.Config;
import ricbot.llm.api.GroqTranscriptionProvider;
import ricbot.llm.api.OpenAITranscriptionProvider;
import ricbot.llm.api.TranscriptionProvider;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Channel 抽象基类
 *
 * 主要目标：
 * 1. 作为所有聊天渠道的统一父类
 * 2. 持有 channel config 和 MessageBus
 * 3. 提供语音转写辅助逻辑
 *
 * 你后面像：
 * - TelegramChannel
 * - DiscordChannel
 * - SlackChannel
 * - WeixinChannel
 *
 * 都可以继承这个类。
 */
public abstract class BaseChannel {

    /**
     * 渠道配置
     */
    protected final Map<String, Object> channelConfig;

    /**
     * 全局 Config
     */
    protected final Config config;

    /**
     * 消息总线
     */
    protected final MessageBus bus;

    protected BaseChannel(
            Map<String, Object> channelConfig,
            Config config,
            MessageBus bus
    ) {
        this.channelConfig = channelConfig != null ? channelConfig : Collections.emptyMap();
        this.config = config;
        this.bus = bus;
    }

    /**
     * 渠道显示名
     */
    public abstract String getDisplayName();

    /**
     * 渠道内部名，如 telegram / discord / weixin
     */
    public abstract String getChannelName();

    /**
     * 启动渠道
     */
    public abstract void start() throws Exception;

    /**
     * 停止渠道
     */
    public abstract void stop() throws Exception;

    /**
     * 是否启用
     *
     * 对应 Python 里 config.channels.xxx.enabled 判断
     */
    public boolean isEnabled() {
        Object enabled = channelConfig.get("enabled");
        return enabled instanceof Boolean b && b;
    }

    /**
     * 登录流程占位。
     *
     * 对应 Python 里不同渠道可能有 login(force=False)
     */
    public boolean login(boolean force) throws Exception {
        return true;
    }

    /**
     * 读取渠道配置项
     */
    public String getStringConfig(String key) {
        Object value = channelConfig.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    public boolean getBooleanConfig(String key, boolean defaultValue) {
        Object value = channelConfig.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return defaultValue;
    }

    public int getIntConfig(String key, int defaultValue) {
        Object value = channelConfig.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (Exception ignored) {
            }
        }
        return defaultValue;
    }

    // =========================================================
    // Audio transcription
    // =========================================================

    /**
     * 语音转写主入口
     *
     * 对应你前面 Python transcription.py 的 provider 选择逻辑。
     */
    public String transcribeAudio(Path filePath) {
        if (filePath == null) {
            return "";
        }

        String providerName = resolveTranscriptionProviderName();
        TranscriptionProvider provider = buildTranscriptionProvider(providerName);

        if (provider == null) {
            System.err.println("No transcription provider available for channel: " + getChannelName());
            return "";
        }

        return provider.transcribe(filePath);
    }

    /**
     * 决定当前使用哪个语音转写 provider。
     *
     * 优先级：
     * 1. channel 自己配置 transcriptionProvider
     * 2. 全局 channels.transcriptionProvider
     * 3. 默认 groq
     */
    protected String resolveTranscriptionProviderName() {
        String local = getStringConfig("transcriptionProvider");
        if (local != null && !local.isBlank()) {
            return local;
        }

        if (config != null && config.getChannels() != null) {
            String global = config.getChannels().getTranscriptionProvider();
            if (global != null && !global.isBlank()) {
                return global;
            }
        }

        return "groq";
    }

    /**
     * 根据 provider 名称实例化具体转写 provider。
     *
     * 当前支持：
     * - groq
     * - openai
     */
    protected TranscriptionProvider buildTranscriptionProvider(String providerName) {
        String name = providerName != null
                ? providerName.trim().toLowerCase(java.util.Locale.ROOT)
                : "groq";

        return switch (name) {
            case "openai", "whisper", "openai_whisper" ->
                    new OpenAITranscriptionProvider(resolveOpenAITranscriptionApiKey());

            case "groq", "groq_whisper" ->
                    new GroqTranscriptionProvider(resolveGroqTranscriptionApiKey());

            default -> {
                System.err.println("Unsupported transcription provider: " + providerName + ", fallback to groq");
                yield new GroqTranscriptionProvider(resolveGroqTranscriptionApiKey());
            }
        };
    }

    /**
     * 优先从 Config.providers.openai 取 key，没有再走环境变量。
     */
    protected String resolveOpenAITranscriptionApiKey() {
        try {
            if (config != null
                    && config.getProviders() != null
                    && config.getProviders().getOpenai() != null) {
                String key = config.getProviders().getOpenai().getApiKey();
                if (key != null && !key.isBlank()) {
                    return key;
                }
            }
        } catch (Exception ignored) {
        }
        return System.getenv("OPENAI_API_KEY");
    }

    /**
     * 优先从 Config.providers.groq 取 key，没有再走环境变量。
     */
    protected String resolveGroqTranscriptionApiKey() {
        try {
            if (config != null
                    && config.getProviders() != null
                    && config.getProviders().getGroq() != null) {
                String key = config.getProviders().getGroq().getApiKey();
                if (key != null && !key.isBlank()) {
                    return key;
                }
            }
        } catch (Exception ignored) {
        }
        return System.getenv("GROQ_API_KEY");
    }
}