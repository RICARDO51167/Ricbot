package ricbot.infra.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public final class SearchUsageFetcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SearchUsageFetcher() {
    }

    public static class SearchUsageInfo {
        private String provider;
        private boolean supported;
        private String error;
        private Integer used;
        private Integer limit;
        private Integer remaining;
        private String resetDate;
        private Integer searchUsed;
        private Integer extractUsed;
        private Integer crawlUsed;

        public SearchUsageInfo(String provider) {
            this.provider = provider;
        }

        public String format() {
            StringBuilder lines = new StringBuilder("🔍 Web Search: " + provider + "\n");

            if (!supported) {
                lines.append("   Usage tracking: not available for this provider");
                return lines.toString();
            }
            if (error != null) {
                lines.append("   Usage: unavailable (").append(error).append(")");
                return lines.toString();
            }

            if (used != null && limit != null) {
                lines.append("   Usage: ").append(used).append(" / ").append(limit).append(" requests\n");
            } else if (used != null) {
                lines.append("   Usage: ").append(used).append(" requests\n");
            }

            StringBuilder breakdown = new StringBuilder();
            if (searchUsed != null) breakdown.append("Search: ").append(searchUsed).append(" | ");
            if (extractUsed != null) breakdown.append("Extract: ").append(extractUsed).append(" | ");
            if (crawlUsed != null) breakdown.append("Crawl: ").append(crawlUsed).append(" | ");
            if (!breakdown.isEmpty()) {
                lines.append("   Breakdown: ")
                        .append(breakdown.substring(0, breakdown.length() - 3))
                        .append("\n");
            }

            if (remaining != null) {
                lines.append("   Remaining: ").append(remaining).append(" requests\n");
            }
            if (resetDate != null) {
                lines.append("   Resets: ").append(resetDate);
            }

            return lines.toString().trim();
        }

        // getters/setters omitted for brevity
        public SearchUsageInfo setSupported(boolean supported) { this.supported = supported; return this; }
        public SearchUsageInfo setError(String error) { this.error = error; return this; }
        public SearchUsageInfo setUsed(Integer used) { this.used = used; return this; }
        public SearchUsageInfo setLimit(Integer limit) { this.limit = limit; return this; }
        public SearchUsageInfo setRemaining(Integer remaining) { this.remaining = remaining; return this; }
        public SearchUsageInfo setResetDate(String resetDate) { this.resetDate = resetDate; return this; }
        public SearchUsageInfo setSearchUsed(Integer searchUsed) { this.searchUsed = searchUsed; return this; }
        public SearchUsageInfo setExtractUsed(Integer extractUsed) { this.extractUsed = extractUsed; return this; }
        public SearchUsageInfo setCrawlUsed(Integer crawlUsed) { this.crawlUsed = crawlUsed; return this; }
    }

    public static SearchUsageInfo fetchSearchUsage(String provider, String apiKey) {
        String p = (provider == null || provider.isBlank()) ? "duckduckgo" : provider.trim().toLowerCase();

        if ("tavily".equals(p)) {
            return fetchTavilyUsage(apiKey);
        }
        return new SearchUsageInfo(p).setSupported(false);
    }

    private static SearchUsageInfo fetchTavilyUsage(String apiKey) {
        String key = (apiKey != null && !apiKey.isBlank()) ? apiKey : System.getenv("TAVILY_API_KEY");
        SearchUsageInfo info = new SearchUsageInfo("tavily").setSupported(true);

        if (key == null || key.isBlank()) {
            return info.setError("missing API key");
        }

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.tavily.com/usage"))
                    .header("Authorization", "Bearer " + key)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return info.setError("HTTP " + response.statusCode());
            }

            Map<String, Object> json = MAPPER.readValue(response.body(), new TypeReference<>() {});
            info.setUsed(intValue(json.get("used")));
            info.setLimit(intValue(json.get("limit")));
            info.setRemaining(intValue(json.get("remaining")));
            info.setResetDate(string(json.get("reset_date")));
            info.setSearchUsed(intValue(json.get("search_used")));
            info.setExtractUsed(intValue(json.get("extract_used")));
            info.setCrawlUsed(intValue(json.get("crawl_used")));
            return info;
        } catch (Exception e) {
            return info.setError(e.getMessage());
        }
    }

    private static Integer intValue(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return o != null ? Integer.parseInt(String.valueOf(o)) : null; } catch (Exception e) { return null; }
    }

    private static String string(Object o) {
        return o != null ? String.valueOf(o) : null;
    }
}