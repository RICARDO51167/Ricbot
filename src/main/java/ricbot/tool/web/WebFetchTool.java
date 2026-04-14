package ricbot.tool.web;

import ricbot.tool.api.Tool;
import ricbot.infra.security.NetworkSecurity;

import java.net.http.HttpClient;
import java.time.Duration;

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

    private final int maxChars;
    private final String proxy;

    public WebFetchTool(int maxChars, String proxy) {
        this.maxChars = maxChars > 0 ? maxChars : 50000;
        this.proxy = proxy;
    }

    public WebFetchTool(String proxy) {
        this(50000, proxy);
    }

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "Fetch a URL and extract readable content (HTML -> markdown/text). Output is capped at maxChars.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    public Object execute(String url, String extractMode, Integer maxCharsOverride) {
        int finalMaxChars = maxCharsOverride != null ? maxCharsOverride : this.maxChars;
        String mode = extractMode != null ? extractMode : "markdown";

        NetworkSecurity.ValidationResult isValid = WebToolSupport.validateUrlSafe(url);
        if (!isValid.ok()) {
            return toErrorJson("URL validation failed: " + isValid.message(), url);
        }

        HttpClient client = WebToolSupport.buildClient();

        // 1) 先试图片直取
        try {
            WebToolSupport.BinaryFetchResult binary = WebToolSupport.fetchBinary(
                    client,
                    url,
                    Duration.ofSeconds(15)
            );

            String contentType = binary.contentType() != null ? binary.contentType() : "";
            if (contentType.startsWith("image/")) {
                return WebToolSupport.buildImageContentBlocks(
                        binary.raw(),
                        contentType,
                        binary.finalUrl(),
                        "(Image fetched from: " + url + ")"
                );
            }
        } catch (Exception ignored) {
            // 与 Python 保持一致：图片预探测失败不直接报错，继续后续抓取
        }

        // 2) 优先 Jina
        String result = fetchJina(url, finalMaxChars);
        if (result != null) {
            return result;
        }

        // 3) 回退 readability/text
        return fetchReadability(url, mode, finalMaxChars);
    }

    /**
     * 对应 Python: _fetch_jina(...)
     *
     * 这里走 r.jina.ai 的阅读代理。
     */
    private String fetchJina(String url, int maxChars) {
        try {
            HttpClient client = WebToolSupport.buildClient();
            String jinaUrl = "https://r.jina.ai/http://" + stripScheme(url);

            String text = WebToolSupport.fetchText(
                    client,
                    jinaUrl,
                    Duration.ofSeconds(20)
            );

            if (text == null || text.isBlank()) {
                return null;
            }

            text = WebToolSupport.UNTRUSTED_BANNER + "\n\n" + text.trim();
            if (maxChars > 0 && text.length() > maxChars) {
                text = text.substring(0, maxChars);
            }
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 对应 Python: _fetch_readability(...)
     *
     * Java 版这里先做轻量抽取：
     * - 下载 HTML
     * - strip tags
     * - normalize
     * - markdown 模式加 banner
     */
    private String fetchReadability(String url, String extractMode, int maxChars) {
        try {
            HttpClient client = WebToolSupport.buildClient();
            String html = WebToolSupport.fetchText(
                    client,
                    url,
                    Duration.ofSeconds(20)
            );

            return WebToolSupport.extractReadable(html, extractMode, maxChars);
        } catch (Exception e) {
            return toErrorJson("Fetch failed: " + e.getMessage(), url);
        }
    }

    private static String stripScheme(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceFirst("^https?://", "");
    }

    private static String toErrorJson(String error, String url) {
        return "{\"error\":\"" + escapeJson(error) + "\",\"url\":\"" + escapeJson(url) + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}