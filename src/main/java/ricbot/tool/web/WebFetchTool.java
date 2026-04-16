package ricbot.tool.web;

import ricbot.tool.api.Tool;
import ricbot.infra.security.NetworkSecurity;
import ricbot.infra.common.CircuitBreaker;
import ricbot.infra.common.RetryUtils;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * WebFetchTool: 抓取 URL 并提取可读内容。
 * 流程：SSRF 校验 -> 图片探测 -> Jina 抽取 -> Readability/Text 回退。
 */
public class WebFetchTool extends Tool {

    private final int maxChars;
    private final String proxy;
    private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 30000);

    public WebFetchTool(int maxChars, String proxy) {
        this.maxChars = maxChars > 0 ? maxChars : 50000;
        this.proxy = proxy;
    }

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "抓取一个 URL 并提取可读内容（HTML -> markdown/text）。输出长度上限为 maxChars。";
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
            return toErrorJson("URL 校验失败：" + isValid.message(), url);
        }

        final String finalUrl = url;

        try {
            return RetryUtils.withExponentialBackoff(() -> circuitBreaker.execute(() -> {
                HttpClient client = WebToolSupport.buildClient();

                try {
                    WebToolSupport.BinaryFetchResult binary = WebToolSupport.fetchBinary(
                            client,
                            finalUrl,
                            Duration.ofSeconds(15)
                    );

                    String contentType = binary.contentType() != null ? binary.contentType() : "";
                    if (contentType.startsWith("image/")) {
                        return WebToolSupport.buildImageContentBlocks(
                                binary.raw(),
                                contentType,
                                binary.finalUrl(),
                                "（图片来源：" + finalUrl + "）"
                        );
                    }
                } catch (Exception ignored) {
                }

                String result = fetchJina(finalUrl, finalMaxChars);
                if (result != null) {
                    return result;
                }

                return fetchReadability(finalUrl, mode, finalMaxChars);
            }), 3, 1000, 2.0);
        } catch (Exception e) {
            return toErrorJson("抓取失败：" + e.getMessage(), finalUrl);
        }
    }

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
            return toErrorJson("抓取失败：" + e.getMessage(), url);
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
