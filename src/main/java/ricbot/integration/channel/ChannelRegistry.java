package ricbot.integration.channel;


import java.util.*;

/**
 * 渠道注册中心。
 */
public final class ChannelRegistry {

    private ChannelRegistry() {
    }

    public static Map<String, Class<? extends BaseChannel>> discoverPlugins() {
        Map<String, Class<? extends BaseChannel>> plugins = new LinkedHashMap<>();

        ServiceLoader<ChannelPluginProvider> loader = ServiceLoader.load(ChannelPluginProvider.class);
        for (ChannelPluginProvider provider : loader) {
            try {
                String name = provider.name();
                Class<? extends BaseChannel> clazz = provider.channelClass();
                if (name != null && !name.isBlank() && clazz != null) {
                    plugins.put(name, clazz);
                }
            } catch (Exception e) {
                System.err.println("加载渠道插件失败：" + e.getMessage());
            }
        }

        return plugins;
    }

    public static Map<String, Class<? extends BaseChannel>> discoverAll() {
        Map<String, Class<? extends BaseChannel>> builtin = builtinChannels();
        Map<String, Class<? extends BaseChannel>> external = discoverPlugins();

        Set<String> shadowed = new HashSet<>(external.keySet());
        shadowed.retainAll(builtin.keySet());
        if (!shadowed.isEmpty()) {
            System.err.println("以下插件渠道被内置渠道覆盖（已忽略）：" + shadowed);
        }

        Map<String, Class<? extends BaseChannel>> merged = new LinkedHashMap<>(external);
        merged.putAll(builtin);
        return merged;
    }

    private static Map<String, Class<? extends BaseChannel>> builtinChannels() {
        Map<String, Class<? extends BaseChannel>> map = new LinkedHashMap<>();

        map.put("feishu", FeishuChannel.class);
        map.put("dingtalk", DingTalkChannel.class);
        map.put("wecom", WecomChannel.class);
        map.put("qq", QQChannel.class);
        map.put("weixin", WeixinChannel.class);
        map.put("email", EmailChannel.class);
        map.put("websocket", WebSocketChannel.class);

        return map;
    }

    /**
     * 插件提供者 SPI。
     */
    public interface ChannelPluginProvider {
        String name();

        Class<? extends BaseChannel> channelClass();
    }
}
