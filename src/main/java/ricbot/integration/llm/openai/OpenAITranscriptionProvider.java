package ricbot.integration.llm.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.integration.llm.api.MultipartBodyBuilder;
import ricbot.integration.llm.api.TranscriptionProvider;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI 音频转录提供者
 */
public class OpenAITranscriptionProvider implements TranscriptionProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String apiUrl;

    public OpenAITranscriptionProvider(String apiKey) {
        this(apiKey, null);
    }

    public OpenAITranscriptionProvider(String apiKey, String apiBase) {
        this.apiKey = (apiKey != null && !apiKey.isBlank())
                ? apiKey
                : System.getenv("OPENAI_API_KEY");

        this.apiUrl = resolveAudioTranscriptionsUrl(
                apiBase,
                "https://api.openai.com/v1",
                "https://api.openai.com/v1/audio/transcriptions"
        );
    }

    @Override
    public String transcribe(Path filePath) {
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("未配置用于转录的 OpenAI API 密钥");
            return "";
        }
        if (filePath == null || !Files.exists(filePath)) {
            System.err.println("未找到音频文件: " + filePath);
            return "";
        }

        try {
            String boundary = "----NanobotBoundary" + UUID.randomUUID().toString().replace("-", "");
            byte[] body = MultipartBodyBuilder.build(boundary, filePath, "whisper-1");

            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new RuntimeException(response.body());
            }

            Map<String, Object> data = MAPPER.readValue(response.body(), new TypeReference<>() {});
            Object text = data.get("text");
            return text != null ? String.valueOf(text) : "";
        } catch (Exception e) {
            System.err.println("OpenAI 转录错误: " + e.getMessage());
            return "";
        }
    }

    private static String resolveAudioTranscriptionsUrl(String apiBase, String defaultBase, String defaultUrl) {
        String base = apiBase != null ? apiBase.trim() : "";
        if (base.isBlank()) {
            return defaultUrl;
        }

        String normalized = stripTrailingSlash(base);
        if (normalized.endsWith("/audio/transcriptions")) {
            return normalized;
        }

        String base2 = normalized;
        if (base2.endsWith("/v1")) {
            return base2 + "/audio/transcriptions";
        }
        if (base2.endsWith("/v1/")) {
            return stripTrailingSlash(base2) + "/audio/transcriptions";
        }

        String defBase = stripTrailingSlash(defaultBase);
        if (base2.equals(defBase)) {
            return base2 + "/audio/transcriptions";
        }

        return base2 + "/audio/transcriptions";
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }
}
