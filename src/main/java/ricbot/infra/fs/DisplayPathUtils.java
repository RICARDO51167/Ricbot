package ricbot.infra.fs;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

// 用于处理显示路径缩写的工具类
public final class DisplayPathUtils {

    // 私有构造函数，防止实例化
    private DisplayPathUtils() {
    }

    /**
     * 缩写路径字符串，使其不超过指定长度
     *
     * @param path   原始路径
     * @param maxLen 最大允许长度
     * @return 缩写后的路径
     */
    public static String abbreviatePath(String path, int maxLen) {
        // 如果路径为空或null，直接返回
        if (path == null || path.isEmpty()) {
            return path;
        }
        // 如果最大长度小于等于0，设置默认值为40
        if (maxLen <= 0) {
            maxLen = 40;
        }

        // 如果是HTTP或HTTPS URL，调用专门的URL缩写方法
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return abbreviateUrl(path, maxLen);
        }

        // 将反斜杠替换为正斜杠以统一路径分隔符
        String normalized = path.replace("\\", "/");
        // 获取用户主目录并统一分隔符
        String home = System.getProperty("user.home").replace("\\", "/");
        // 如果路径以主目录开头，替换为 "~"
        if (normalized.startsWith(home + "/")) {
            normalized = "~" + normalized.substring(home.length());
        } else if (normalized.equals(home)) {
            // 如果路径就是主目录，直接替换为 "~"
            normalized = "~";
        }

        // 如果标准化后的路径长度不超过最大长度，直接返回
        if (normalized.length() <= maxLen) {
            return normalized;
        }

        // 移除末尾的斜杠并按 "/" 分割路径部分
        String[] parts = normalized.replaceAll("/+$", "").split("/");
        // 如果分割后只有一部分（即根目录或单级路径），直接截断并添加省略号
        if (parts.length <= 1) {
            return normalized.substring(0, Math.max(0, maxLen - 1)) + "…";
        }

        // 获取最后一部分（文件名或最后一级目录）
        String basename = parts[parts.length - 1];
        // 计算剩余可用长度：总长度 - 文件名长度 - 省略号和斜杠的长度 ("…/" + "/")
        int budget = maxLen - basename.length() - 3; // …/ + /

        // 用于存储保留的中间路径部分
        List<String> kept = new ArrayList<>();
        // 从倒数第二部分开始向前遍历
        for (int i = parts.length - 2; i >= 0; i--) {
            String seg = parts[i];
            // 当前段需要的长度：段本身长度 + 1个斜杠
            int needed = seg.length() + 1;
            // 如果预算足够，则保留该段
            if (budget >= needed) {
                kept.add(0, seg); // 添加到列表头部以保持顺序
                budget -= needed; // 减少预算
            } else {
                // 预算不足，停止遍历
                break;
            }
        }

        // 如果有保留的中间部分，构建结果：…/中间部分/文件名
        if (!kept.isEmpty()) {
            return "…/" + String.join("/", kept) + "/" + basename;
        }
        // 否则，只保留文件名：…/文件名
        return "…/" + basename;
    }

    /**
     * 缩写URL字符串，使其不超过指定长度
     *
     * @param url    原始URL
     * @param maxLen 最大允许长度
     * @return 缩写后的URL
     */
    private static String abbreviateUrl(String url, int maxLen) {
        // 如果URL长度不超过最大长度，直接返回
        if (url.length() <= maxLen) {
            return url;
        }
        try {
            // 解析URL
            URI uri = URI.create(url);
            // 获取主机名，如果为空则设为空字符串
            String domain = uri.getHost() != null ? uri.getHost() : "";
            // 获取路径部分，如果为空则设为空字符串
            String path = uri.getPath() != null ? uri.getPath() : "";
            // 按 "/" 分割路径
            String[] parts = path.split("/");
            // 获取最后一部分作为文件名
            String filename = parts.length > 0 ? parts[parts.length - 1] : "";

            // 构建前缀：协议://域名/
            String prefix = uri.getScheme() + "://" + domain + "/";
            // 尝试构建输出：前缀 + 文件名
            String out = prefix + filename;
            // 如果输出长度不超过最大长度，直接返回
            if (out.length() <= maxLen) {
                return out;
            }
            // 如果前缀长度+2（省略号和至少一个字符）小于最大长度，则缩写文件名
            if (prefix.length() + 2 < maxLen) {
                // 计算文件名需要保留的后缀长度
                int suffixLen = maxLen - prefix.length() - 1;
                // 返回前缀 + 省略号 + 文件名的后缀部分
                return prefix + "…" + filename.substring(Math.max(0, filename.length() - suffixLen));
            }
            // 如果前缀太长，直接截断整个URL并添加省略号
            return url.substring(0, Math.max(0, maxLen - 1)) + "…";
        } catch (Exception e) {
            // 如果解析失败，直接截断整个URL并添加省略号
            return url.substring(0, Math.max(0, maxLen - 1)) + "…";
        }
    }
}