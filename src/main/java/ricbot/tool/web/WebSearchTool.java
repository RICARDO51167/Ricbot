package ricbot.tool.web;

import ricbot.tool.api.Tool;
import ricbot.infra.config.Config;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 对应 Python: WebSearchTool
 *
 * 主要目标：
 * 1. 按 provider 分发搜索请求
 * 2. 返回标题/链接/摘要
 * 3. SearXNG 先做 URL 校验
 * 4. 某些 provider 未配置时回退 DuckDuckGo
 */
public class WebSearchTool extends Tool {

    private final Config.WebSearchConfig config;
    private final String proxy;

    public WebSearchTool(Config.WebSearchConfig config, String proxy) {
        this.config = config != null ? config : new Config.WebSearchConfig();
        this.proxy = proxy;
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "Search the web. Returns titles, URLs, and snippets. count defaults to 5 (max 10). Use web_fetch to read a specific page in full.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    public String execute(String query, Integer count) {
        String provider = config.getProvider() != null ? config.getProvider().trim().toLowerCase(Locale.ROOT) : "duckduckgo";
        int n = Math.min(Math.max(count != null ? count : config.getMaxResults(), 1), 10);

        return switch (provider) {
            case "duckduckgo" -> searchDuckDuckGo(query, n);
            case "tavily" -> searchTavily(query, n);
            case "searxng" -> searchSearxng(query, n);
            case "jina" -> searchJina(query, n);
            case "brave" -> searchBrave(query, n);
            case "kagi" -> searchKagi(query, n);
            default -> "Error: unknown search provider '" + provider + "'";
        };
    }

    private String searchBrave(String query, int n) {
        String apiKey = firstNonBlank(config.getApiKey(), System.getenv("BRAVE_API_KEY"));
        if (isBlank(apiKey)) {
            return searchDuckDuckGo(query, n);
        }

        try {
            HttpClient client = WebToolSupport.buildClient();
            String url = "https://api.search.brave.com/res/v1/web/search?q="
                    + encode(query) + "&count=" + n;

            Map<String, Object> json = WebToolSupport.getJson(
                    client,
                    url,
                    Map.of(
                            "Accept", "application/json",
                            "X-Subscription-Token", apiKey
                    ),
                    Duration.ofSeconds(10)
            );

            List<Map<String, Object>> items = new ArrayList<>();
            Map<String, Object> web = asMap(json.get("web"));
            Object resultsObj = web.get("results");
            if (resultsObj instanceof List<?> list) {
                for (Object itemObj : list) {
                    Map<String, Object> item = asMap(itemObj);
                    items.add(Map.of(
                            "title", safe(item.get("title")),
                            "url", safe(item.get("url")),
                            "content", safe(item.get("description"))
                    ));
                }
            }

            return WebToolSupport.formatResults(query, items, n);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String searchTavily(String query, int n) {
        String apiKey = firstNonBlank(config.getApiKey(), System.getenv("TAVILY_API_KEY"));
        if (isBlank(apiKey)) {
            return searchDuckDuckGo(query, n);
        }

        try {
            HttpClient client = WebToolSupport.buildClient();
            Map<String, Object> json = WebToolSupport.postJson(
                    client,
                    "https://api.tavily.com/search",
                    Map.of("Authorization", "Bearer " + apiKey),
                    Map.of(
                            "query", query,
                            "max_results", n
                    ),
                    Duration.ofSeconds(15)
            );

            List<Map<String, Object>> items = asListOfMaps(json.get("results"));
            return WebToolSupport.formatResults(query, items, n);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String searchSearxng(String query, int n) {
        String baseUrl = firstNonBlank(config.getBaseUrl(), System.getenv("SEARXNG_BASE_URL"));
        if (isBlank(baseUrl)) {
            return searchDuckDuckGo(query, n);
        }

        String endpoint = baseUrl.replaceAll("/+$", "") + "/search";
        var valid = WebToolSupport.validateUrlSafe(endpoint);
        if (!valid.ok()) {
            return "Error: invalid SearXNG URL: " + valid.message();
        }

        try {
            HttpClient client = WebToolSupport.buildClient();
            String url = endpoint + "?q=" + encode(query) + "&format=json";

            Map<String, Object> json = WebToolSupport.getJson(
                    client,
                    url,
                    Map.of("User-Agent", WebToolSupport.USER_AGENT),
                    Duration.ofSeconds(10)
            );

            List<Map<String, Object>> items = asListOfMaps(json.get("results"));
            return WebToolSupport.formatResults(query, items, n);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String searchJina(String query, int n) {
        String apiKey = firstNonBlank(config.getApiKey(), System.getenv("JINA_API_KEY"));
        if (isBlank(apiKey)) {
            return searchDuckDuckGo(query, n);
        }

        try {
            HttpClient client = WebToolSupport.buildClient();
            String url = "https://s.jina.ai/?q=" + encode(query);

            Map<String, Object> json = WebToolSupport.getJson(
                    client,
                    url,
                    Map.of(
                            "Accept", "application/json",
                            "Authorization", "Bearer " + apiKey
                    ),
                    Duration.ofSeconds(10)
            );

            List<Map<String, Object>> items = new ArrayList<>();
            Object dataObj = json.get("data");
            if (dataObj instanceof List<?> list) {
                for (Object obj : list) {
                    Map<String, Object> d = asMap(obj);
                    items.add(Map.of(
                            "title", safe(d.get("title")),
                            "url", safe(d.get("url")),
                            "content", truncate(safe(d.get("content")), 500)
                    ));
                }
            }

            return WebToolSupport.formatResults(query, items, n);
        } catch (Exception e) {
            return searchDuckDuckGo(query, n);
        }
    }

    private String searchKagi(String query, int n) {
        String apiKey = firstNonBlank(config.getApiKey(), System.getenv("KAGI_API_KEY"));
        if (isBlank(apiKey)) {
            return searchDuckDuckGo(query, n);
        }

        try {
            HttpClient client = WebToolSupport.buildClient();
            String url = "https://kagi.com/api/v0/search?q=" + encode(query) + "&limit=" + n;

            Map<String, Object> json = WebToolSupport.getJson(
                    client,
                    url,
                    Map.of("Authorization", "Bot " + apiKey),
                    Duration.ofSeconds(10)
            );

            List<Map<String, Object>> items = new ArrayList<>();
            Object dataObj = json.get("data");
            if (dataObj instanceof List<?> list) {
                for (Object obj : list) {
                    Map<String, Object> d = asMap(obj);
                    int t = d.get("t") instanceof Number num ? num.intValue() : -1;
                    if (t == 0) {
                        items.add(Map.of(
                                "title", safe(d.get("title")),
                                "url", safe(d.get("url")),
                                "content", safe(d.get("snippet"))
                        ));
                    }
                }
            }

            return WebToolSupport.formatResults(query, items, n);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Java 标准库里没有 DDG 官方 SDK，这里给你一个可运行兜底版：
     * 直接调 DuckDuckGo HTML 接口并做简单抽取。
     *
     * 后面你如果想完全对齐 Python DDGS，再单独换实现。
     */
    private String searchDuckDuckGo(String query, int n) {
        try {
            HttpClient client = WebToolSupport.buildClient();
            String url = "https://duckduckgo.com/html/?q=" + encode(query);

            String html = WebToolSupport.fetchText(
                    client,
                    url,
                    Duration.ofSeconds(config.getTimeout() > 0 ? config.getTimeout() : 10)
            );

            // 轻量抽取：这里先直接返回简化文本，避免无依赖情况下卡住。
            // 你后面若想增强，可再用 Jsoup 替换。
            String text = WebToolSupport.extractReadable(html, "text", 3000);
            if (text.isBlank()) {
                return "No results for: " + query;
            }

            List<Map<String, Object>> items = new ArrayList<>();
            items.add(Map.of(
                    "title", "DuckDuckGo results",
                    "url", "https://duckduckgo.com/?q=" + encode(query),
                    "content", truncate(text, 800)
            ));
            return WebToolSupport.formatResults(query, items, 1);
        } catch (Exception e) {
            return "Error: DuckDuckGo search failed (" + e.getMessage() + ")";
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object obj) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (obj instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> m) {
                    list.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        return list;
    }

    private static String safe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : b;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}