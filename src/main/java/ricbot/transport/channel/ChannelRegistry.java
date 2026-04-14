package ricbot.transport.channel;


import java.util.*;

/**
 * 渠道注册中心。
 *
 * 对应 Python: nanobot.channels.registry
 *
 * Java 里没有 pkgutil / entry_points 那种完全等价机制，
 * 这里采用：
 * 1. 内置渠道：静态注册
 * 2. 外部插件：ServiceLoader
 *
 * 并保持“内置优先于插件”的语义。
 */
public final class ChannelRegistry {

    private ChannelRegistry() {
    }

    /**
     * 返回所有内置渠道名字。
     */
    public static List<String> discoverChannelNames() {
        return new ArrayList<>(builtinChannels().keySet());
    }

    /**
     * 按模块名加载内置渠道类。
     */
    public static Class<? extends BaseChannel> loadChannelClass(String moduleName) {
        Class<? extends BaseChannel> clazz = builtinChannels().get(moduleName);
        if (clazz == null) {
            throw new IllegalArgumentException("No BaseChannel subclass for " + moduleName);
        }
        return clazz;
    }

    /**
     * 发现插件渠道。
     *
     * 要求插件实现 ChannelPluginProvider。
     */
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
                System.err.println("Failed to load channel plugin: " + e.getMessage());
            }
        }

        return plugins;
    }

    /**
     * 返回全部渠道：插件 + 内置，其中内置优先。
     */
    public static Map<String, Class<? extends BaseChannel>> discoverAll() {
        Map<String, Class<? extends BaseChannel>> builtin = builtinChannels();
        Map<String, Class<? extends BaseChannel>> external = discoverPlugins();

        Set<String> shadowed = new HashSet<>(external.keySet());
        shadowed.retainAll(builtin.keySet());
        if (!shadowed.isEmpty()) {
            System.err.println("Plugin(s) shadowed by built-in channels (ignored): " + shadowed);
        }

        Map<String, Class<? extends BaseChannel>> merged = new LinkedHashMap<>(external);
        merged.putAll(builtin);
        return merged;
    }

    /**
     * 内置渠道静态表。
     *
     * 你后面新增内置渠道时，把它加到这里。
     */
    private static Map<String, Class<? extends BaseChannel>> builtinChannels() {
        Map<String, Class<? extends BaseChannel>> map = new LinkedHashMap<>();

        // TODO: 把你真正写好的渠道类放进来
        // map.put("discord", DiscordChannel.class);
        // map.put("dingtalk", DingTalkChannel.class);
        // map.put("email", EmailChannel.class);
        // map.put("feishu", FeishuChannel.class);
        // map.put("matrix", MatrixChannel.class);
        // map.put("mochat", MochatChannel.class);
        // map.put("qq", QQChannel.class);

        return map;
    }

    /**
     * 插件提供者 SPI。
     *
     * 外部插件只要实现这个接口，并在
     * META-INF/services/nanobot.channels.ChannelRegistry$ChannelPluginProvider
     * 里注册，就能被发现。
     */
    public interface ChannelPluginProvider {
        String name();
        Class<? extends BaseChannel> channelClass();
    }
}