package ricbot.tool.web;

import ricbot.tool.api.Tool; // 导入基础工具类
import ricbot.infra.security.NetworkSecurity; // 导入网络安全校验类
import ricbot.infra.common.CircuitBreaker; // 导入熔断器类
import ricbot.infra.common.RetryUtils; // 导入重试工具类

import java.net.http.HttpClient; // 导入 HTTP 客户端类
import java.time.Duration; // 导入时间Duration类，用于设置超时

/**
 * 对应 Python: WebFetchTool
 *
 * 主要目标：
 * 1. 先做 SSRF 校验
 * 2. 先探测图片，若是图片直接返回 image block
 * 3. 普通页面优先走 Jina 抽取
 * 4. Jina 失败再回退到 readability/text 抽取
 */
public class WebFetchTool extends Tool {

    private final int maxChars; // 最大字符数限制
    private final String proxy; // 代理服务器地址
    private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30000); // 5 次失败后熔断 30 秒

    /**
     * 构造函数
     * @param maxChars 最大字符数，若小于等于0则默认为50000
     * @param proxy 代理地址
     */
    public WebFetchTool(int maxChars, String proxy) {
        this.maxChars = maxChars > 0 ? maxChars : 50000; // 初始化最大字符数，确保为正数
        this.proxy = proxy; // 初始化代理地址
    }

    /**
     * 获取工具名称
     * @return 工具名称 "web_fetch"
     */
    @Override
    public String getName() {
        return "web_fetch";
    }

    /**
     * 获取工具描述
     * @return 工具描述信息
     */
    @Override
    public String getDescription() {
        return "抓取一个 URL 并提取可读内容（HTML -> markdown/text）。输出长度上限为 maxChars。";
    }

    /**
     * 判断是否为只读操作
     * @return true，表示该工具不修改任何状态
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 执行网页抓取任务
     * @param url 待抓取的URL
     * @param extractMode 提取模式 (markdown/text)
     * @param maxCharsOverride 覆盖默认的最大字符数限制
     * @return 抓取结果或错误信息
     */
    public Object execute(String url, String extractMode, Integer maxCharsOverride) {
        // 确定最终使用的最大字符数，优先使用传入的覆盖值
        int finalMaxChars = maxCharsOverride != null ? maxCharsOverride : this.maxChars;
        // 确定提取模式，默认为 markdown
        String mode = extractMode != null ? extractMode : "markdown";

        // 进行 URL 安全性校验，防止 SSRF 攻击
        NetworkSecurity.ValidationResult isValid = WebToolSupport.validateUrlSafe(url);
        if (!isValid.ok()) {
            // 如果校验失败，返回错误 JSON
            return toErrorJson("URL 校验失败：" + isValid.message(), url);
        }

        final String finalUrl = url; // 声明为 final 以便在 Lambda 中使用

        try {
            // 使用指数退避重试机制执行抓取逻辑，并结合熔断器
            return RetryUtils.withExponentialBackoff(() -> circuitBreaker.execute(() -> {
                // 构建 HTTP 客户端
                HttpClient client = WebToolSupport.buildClient();

                // 1) 先试图片直取
                try {
                    // 获取二进制内容以检查是否为图片
                    WebToolSupport.BinaryFetchResult binary = WebToolSupport.fetchBinary(
                            client,
                            finalUrl,
                            Duration.ofSeconds(15) // 设置15秒超时
                    );

                    // 获取内容类型，若为空则设为空字符串
                    String contentType = binary.contentType() != null ? binary.contentType() : "";
                    // 如果内容类型以 "image/" 开头，说明是图片
                    if (contentType.startsWith("image/")) {
                        // 构建图片内容块并返回
                        return WebToolSupport.buildImageContentBlocks(
                                binary.raw(), // 原始字节数据
                                contentType, // 内容类型
                                binary.finalUrl(), // 最终 URL（可能经过重定向）
                                "（图片来源：" + finalUrl + "）" // 添加来源说明
                        );
                    }
                } catch (Exception ignored) {
                    // 与 Python 保持一致：图片预探测失败不直接报错，继续后续抓取
                }

                // 2) 优先 Jina
                // 尝试使用 Jina AI 服务提取内容
                String result = fetchJina(finalUrl, finalMaxChars);
                if (result != null) {
                    // 如果 Jina 提取成功，直接返回结果
                    return result;
                }

                // 3) 回退 readability/text
                // 如果 Jina 失败，回退到本地解析方式
                return fetchReadability(finalUrl, mode, finalMaxChars);
            }), 3, 1000, 2.0); // 最多重试3次，初始间隔1000ms，倍数2.0
        } catch (Exception e) {
            // 捕获所有异常，返回错误 JSON
            return toErrorJson("抓取失败：" + e.getMessage(), finalUrl);
        }
    }

    /**
     * 对应 Python: _fetch_jina(...)
     *
     * 这里走 r.jina.ai 的阅读代理。
     * @param url 待抓取的URL
     * @param maxChars 最大字符数限制
     * @return 提取后的文本内容，失败返回 null
     */
    private String fetchJina(String url, int maxChars) {
        try {
            // 构建 HTTP 客户端
            HttpClient client = WebToolSupport.buildClient();
            // 构造 Jina AI 的代理 URL，去除原 URL 的协议头
            String jinaUrl = "https://r.jina.ai/http://" + stripScheme(url);

            // 从 Jina AI 服务获取文本内容，设置20秒超时
            String text = WebToolSupport.fetchText(
                    client,
                    jinaUrl,
                    Duration.ofSeconds(20)
            );

            // 如果获取内容为空或空白，返回 null
            if (text == null || text.isBlank()) {
                return null;
            }

            // 添加不可信内容横幅，并去除首尾空白
            text = WebToolSupport.UNTRUSTED_BANNER + "\n\n" + text.trim();
            // 如果设置了最大字符数且内容超出限制，进行截断
            if (maxChars > 0 && text.length() > maxChars) {
                text = text.substring(0, maxChars);
            }
            return text; // 返回处理后的文本
        } catch (Exception e) {
            // 发生异常时返回 null，触发回退逻辑
            return null;
        }
    }

    /**
     * 对应 Python: _fetch_readability(...)
     *
     * Java 版这里先做轻量抽取：
     * - 下载 HTML
     * - 去除标签
     * - 标准化文本
     * - markdown 模式加提示横幅
     * @param url 待抓取的URL
     * @param extractMode 提取模式
     * @param maxChars 最大字符数限制
     * @return 提取后的文本内容或错误信息
     */
    private String fetchReadability(String url, String extractMode, int maxChars) {
        try {
            // 构建 HTTP 客户端
            HttpClient client = WebToolSupport.buildClient();
            // 直接获取网页 HTML 内容，设置20秒超时
            String html = WebToolSupport.fetchText(
                    client,
                    url,
                    Duration.ofSeconds(20)
            );

            // 使用 WebToolSupport 提取可读内容
            return WebToolSupport.extractReadable(html, extractMode, maxChars);
        } catch (Exception e) {
            // 发生异常时返回错误 JSON
            return toErrorJson("抓取失败：" + e.getMessage(), url);
        }
    }

    /**
     * 去除 URL 中的协议头 (http:// 或 https://)
     * @param url 原始 URL
     * @return 去除协议头后的 URL
     */
    private static String stripScheme(String url) {
        if (url == null) {
            return ""; // 如果 URL 为空，返回空字符串
        }
        // 使用正则替换掉开头的 http:// 或 https://
        return url.replaceFirst("^https?://", "");
    }

    /**
     * 将错误信息转换为 JSON 格式字符串
     * @param error 错误消息
     * @param url 相关 URL
     * @return JSON 格式的错误字符串
     */
    private static String toErrorJson(String error, String url) {
        // 拼接 JSON 字符串，并对内容进行转义
        return "{\"error\":\"" + escapeJson(error) + "\",\"url\":\"" + escapeJson(url) + "\"}";
    }

    /**
     * 对字符串进行 JSON 转义处理
     * @param s 待转义的字符串
     * @return 转义后的字符串
     */
    private static String escapeJson(String s) {
        if (s == null) {
            return ""; // 如果字符串为空，返回空字符串
        }
        // 替换反斜杠和双引号，防止 JSON 格式错误
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
