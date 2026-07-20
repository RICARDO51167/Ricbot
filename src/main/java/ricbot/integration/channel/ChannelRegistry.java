package ricbot.integration.channel;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 渠道注册中心。
 *
 * 对应 Python: ricbot.channels.registry
 *
 * Java 里没有 pkgutil / entry_points 那种完全等价机制，
 * 这里采用：
 * 1. 内置渠道：静态注册
 * 2. 外部插件：ServiceLoader
 *
 * 并保持“内置优先于插件”的语义。
 */
@Slf4j
public final class ChannelRegistry {

    // 私有构造函数，防止实例化，因为这是一个工具类，所有方法都是静态的
    private ChannelRegistry() {
    }

    /**
     * 返回所有内置渠道名字。
     */
    public static List<String> discoverChannelNames() {
        // 获取内置渠道映射表的键集合（即渠道名称），并转换为一个新的 ArrayList 返回
        return new ArrayList<>(builtinChannels().keySet());
    }

    /**
     * 按模块名加载内置渠道类。
     */
    public static Class<? extends BaseChannel> loadChannelClass(String moduleName) {
        // 从内置渠道映射表中根据模块名获取对应的渠道类
        Class<? extends BaseChannel> clazz = builtinChannels().get(moduleName);
        // 如果未找到对应的类，则抛出非法参数异常
        if (clazz == null) {
            throw new IllegalArgumentException("未找到模块对应的 BaseChannel 子类：" + moduleName);
        }
        // 返回找到的渠道类
        return clazz;
    }

    /**
     * 发现插件渠道。
     *
     * 要求插件实现 ChannelPluginProvider。
     */
    public static Map<String, Class<? extends BaseChannel>> discoverPlugins() {
        // 创建一个有序的 LinkedHashMap 用于存储发现的插件渠道
        Map<String, Class<? extends BaseChannel>> plugins = new LinkedHashMap<>();

        // 使用 ServiceLoader 加载所有实现了 ChannelPluginProvider 接口的插件提供者
        ServiceLoader<ChannelPluginProvider> loader = ServiceLoader.load(ChannelPluginProvider.class);
        // 遍历所有加载到的插件提供者
        for (ChannelPluginProvider provider : loader) {
            try {
                // 获取插件提供的渠道名称
                String name = provider.name();
                // 获取插件提供的渠道类
                Class<? extends BaseChannel> clazz = provider.channelClass();
                // 检查名称不为空且类不为 null，确保有效性
                if (name != null && !name.isBlank() && clazz != null) {
                    // 将有效的插件渠道存入映射表
                    plugins.put(name, clazz);
                }
            } catch (Exception e) {
                log.warn("加载渠道插件失败", e);
            }
        }

        // 返回所有成功加载的插件渠道映射
        return plugins;
    }

    /**
     * 返回全部渠道：插件 + 内置，其中内置优先。
     */
    public static Map<String, Class<? extends BaseChannel>> discoverAll() {
        // 获取所有内置渠道
        Map<String, Class<? extends BaseChannel>> builtin = builtinChannels();
        // 获取所有插件渠道
        Map<String, Class<? extends BaseChannel>> external = discoverPlugins();

        // 创建一个集合，包含所有插件渠道的名称
        Set<String> shadowed = new HashSet<>(external.keySet());
        // 保留那些同时也存在于内置渠道中的名称，即找出被内置渠道覆盖的插件渠道
        shadowed.retainAll(builtin.keySet());
        // 如果有被覆盖的插件渠道，打印警告信息
        if (!shadowed.isEmpty()) {
            log.warn("以下插件渠道被内置渠道覆盖（已忽略）：{}", shadowed);
        }

        // 创建一个新的 LinkedHashMap，首先放入所有插件渠道
        Map<String, Class<? extends BaseChannel>> merged = new LinkedHashMap<>(external);
        // 然后放入所有内置渠道，由于 putAll 会覆盖同名键，因此内置渠道将优先于插件渠道
        merged.putAll(builtin);
        // 返回合并后的渠道映射
        return merged;
    }

    /**
     * 内置渠道静态表。
     *
     * 你后面新增内置渠道时，把它加到这里。
     */
    private static Map<String, Class<? extends BaseChannel>> builtinChannels() {
        // 创建一个有序的 LinkedHashMap 用于存储内置渠道
        Map<String, Class<? extends BaseChannel>> map = new LinkedHashMap<>();

        // 注册飞书渠道
        map.put("feishu", FeishuChannel.class);
        // 注册钉钉渠道
        map.put("dingtalk", DingTalkChannel.class);
        // 注册企业微信渠道
        map.put("wecom", WecomChannel.class);
        // 注册 QQ 渠道
        map.put("qq", QQChannel.class);
        // 注册微信渠道
        map.put("weixin", WeixinChannel.class);
        // 注册邮件渠道
        map.put("email", EmailChannel.class);
        // 注册 WebSocket 渠道
        map.put("websocket", WebSocketChannel.class);

        // 返回内置渠道映射
        return map;
    }

    /**
     * 插件提供者 SPI。
     *
     * 外部插件只要实现这个接口，并在
     * META-INF/services/ricbot.channels.ChannelRegistry$ChannelPluginProvider
     * 里注册，就能被发现。
     */
    public interface ChannelPluginProvider {
        // 获取渠道名称
        String name();
        // 获取渠道类
        Class<? extends BaseChannel> channelClass();
    }
}
