package ricbot.llm.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对应 Python github_copilot_provider.py 里的登录与 token 存储辅助函数
 */
public final class GitHubCopilotAuth {

    public static final String DEFAULT_GITHUB_DEVICE_CODE_URL = "https://github.com/login/device/code";
    public static final String DEFAULT_GITHUB_ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    public static final String DEFAULT_GITHUB_USER_URL = "https://api.github.com/user";
    public static final String DEFAULT_COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token";
    public static final String DEFAULT_COPILOT_BASE_URL = "https://api.githubcopilot.com";
    public static final String GITHUB_COPILOT_CLIENT_ID = "Iv1.b507a08c87ecfe98";
    public static final String GITHUB_COPILOT_SCOPE = "read:user";
    public static final String TOKEN_FILENAME = "github-copilot.json";
    public static final String TOKEN_APP_NAME = "oldricbot";
    public static final String USER_AGENT = "oldricbot/0.1";
    public static final String EDITOR_VERSION = "vscode/1.99.0";
    public static final String EDITOR_PLUGIN_VERSION = "copilot-chat/0.26.0";
    public static final int EXPIRY_SKEW_SECONDS = 60;
    public static final int LONG_LIVED_TOKEN_SECONDS = 315360000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GitHubCopilotAuth() {
    }

    public static Path storageFile() {
        Path dir = Path.of(System.getProperty("user.home"), ".nanobot", "oauth");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return dir.resolve(TOKEN_FILENAME);
    }

    public static OAuthToken loadGithubToken() {
        Path file = storageFile();
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = Files.readString(file);
            OAuthToken token = MAPPER.readValue(json, OAuthToken.class);
            return token.getAccess() == null || token.getAccess().isBlank() ? null : token;
        } catch (Exception e) {
            return null;
        }
    }

    public static OAuthToken getGithubCopilotLoginStatus() {
        return loadGithubToken();
    }

    public static void saveGithubToken(OAuthToken token) {
        try {
            Files.writeString(storageFile(), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(token));
        } catch (Exception e) {
            throw new RuntimeException("Failed to save GitHub Copilot token", e);
        }
    }

    public static Map<String, String> copilotHeaders(String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "token " + token);
        headers.put("Accept", "application/json");
        headers.put("User-Agent", USER_AGENT);
        headers.put("Editor-Version", EDITOR_VERSION);
        headers.put("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION);
        return headers;
    }

    /**
     * 对应 Python: login_github_copilot(...)
     */
    public static OAuthToken loginGithubCopilot(PrintFn printFn) throws Exception {
        PrintFn printer = printFn != null ? printFn : System.out::println;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String requestBody = "client_id=" + GITHUB_COPILOT_CLIENT_ID + "&scope=" + GITHUB_COPILOT_SCOPE;

        HttpRequest deviceReq = HttpRequest.newBuilder(URI.create(DEFAULT_GITHUB_DEVICE_CODE_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> deviceResp = client.send(deviceReq, HttpResponse.BodyHandlers.ofString());
        if (deviceResp.statusCode() >= 400) {
            throw new RuntimeException("GitHub device code request failed: " + deviceResp.body());
        }

        Map<String, Object> payload = MAPPER.readValue(deviceResp.body(), new TypeReference<>() {});
        String deviceCode = String.valueOf(payload.get("device_code"));
        String userCode = String.valueOf(payload.get("user_code"));
        String verifyUrl = payload.get("verification_uri") != null
                ? String.valueOf(payload.get("verification_uri"))
                : String.valueOf(payload.getOrDefault("verification_uri_complete", ""));
        String verifyComplete = payload.get("verification_uri_complete") != null
                ? String.valueOf(payload.get("verification_uri_complete"))
                : verifyUrl;
        int interval = Math.max(1, intValue(payload.get("interval"), 5));
        int expiresIn = intValue(payload.get("expires_in"), 900);

        printer.println("Open: " + verifyUrl);
        printer.println("Code: " + userCode);

        try {
            if (verifyComplete != null && !verifyComplete.isBlank() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(verifyComplete));
            }
        } catch (Exception ignored) {
        }

        long deadline = System.currentTimeMillis() + expiresIn * 1000L;
        int currentInterval = interval;
        String accessToken = null;
        int tokenExpiresIn = LONG_LIVED_TOKEN_SECONDS;

        while (System.currentTimeMillis() < deadline) {
            String pollBody = "client_id=" + GITHUB_COPILOT_CLIENT_ID
                    + "&device_code=" + deviceCode
                    + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";

            HttpRequest pollReq = HttpRequest.newBuilder(URI.create(DEFAULT_GITHUB_ACCESS_TOKEN_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(pollBody))
                    .build();

            HttpResponse<String> pollResp = client.send(pollReq, HttpResponse.BodyHandlers.ofString());
            if (pollResp.statusCode() >= 400) {
                throw new RuntimeException("GitHub token polling failed: " + pollResp.body());
            }

            Map<String, Object> pollPayload = MAPPER.readValue(pollResp.body(), new TypeReference<>() {});
            Object tokenObj = pollPayload.get("access_token");
            if (tokenObj != null) {
                accessToken = String.valueOf(tokenObj);
                tokenExpiresIn = intValue(pollPayload.get("expires_in"), LONG_LIVED_TOKEN_SECONDS);
                break;
            }

            String error = pollPayload.get("error") != null ? String.valueOf(pollPayload.get("error")) : null;
            if ("authorization_pending".equals(error)) {
                Thread.sleep(currentInterval * 1000L);
                continue;
            }
            if ("slow_down".equals(error)) {
                currentInterval += 5;
                Thread.sleep(currentInterval * 1000L);
                continue;
            }
            if ("expired_token".equals(error)) {
                throw new RuntimeException("GitHub device code expired. Please run login again.");
            }
            if ("access_denied".equals(error)) {
                throw new RuntimeException("GitHub device flow was denied.");
            }
            if (error != null) {
                String desc = pollPayload.get("error_description") != null
                        ? String.valueOf(pollPayload.get("error_description"))
                        : error;
                throw new RuntimeException(desc);
            }

            Thread.sleep(currentInterval * 1000L);
        }

        if (accessToken == null) {
            throw new RuntimeException("GitHub device flow timed out.");
        }

        HttpRequest userReq = HttpRequest.newBuilder(URI.create(DEFAULT_GITHUB_USER_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> userResp = client.send(userReq, HttpResponse.BodyHandlers.ofString());
        if (userResp.statusCode() >= 400) {
            throw new RuntimeException("GitHub user request failed: " + userResp.body());
        }

        Map<String, Object> userPayload = MAPPER.readValue(userResp.body(), new TypeReference<>() {});
        String accountId = userPayload.get("login") != null
                ? String.valueOf(userPayload.get("login"))
                : String.valueOf(userPayload.getOrDefault("id", ""));

        long expiresMs = System.currentTimeMillis() + tokenExpiresIn * 1000L;

        OAuthToken token = new OAuthToken();
        token.setAccess(accessToken);
        token.setRefresh("");
        token.setExpires(expiresMs);
        token.setAccountId(accountId != null && !accountId.isBlank() ? accountId : null);

        saveGithubToken(token);
        return token;
    }

    public interface PrintFn {
        void println(String text);
    }

    public static class OAuthToken {
        private String access;
        private String refresh;
        private long expires;
        private String accountId;

        public String getAccess() { return access; }
        public void setAccess(String access) { this.access = access; }
        public String getRefresh() { return refresh; }
        public void setRefresh(String refresh) { this.refresh = refresh; }
        public long getExpires() { return expires; }
        public void setExpires(long expires) { this.expires = expires; }
        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value != null ? Integer.parseInt(String.valueOf(value)) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}