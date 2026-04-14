package ricbot.llm.transcription;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.llm.api.MultipartBodyBuilder;
import ricbot.llm.api.TranscriptionProvider;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 对应 Python: OpenAITranscriptionProvider
 */
public class OpenAITranscriptionProvider implements TranscriptionProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String apiUrl = "https://api.openai.com/v1/audio/transcriptions";

    public OpenAITranscriptionProvider(String apiKey) {
        this.apiKey = (apiKey != null && !apiKey.isBlank())
                ? apiKey
                : System.getenv("OPENAI_API_KEY");
    }

    @Override
    public String transcribe(Path filePath) {
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("OpenAI API key not configured for transcription");
            return "";
        }
        if (filePath == null || !Files.exists(filePath)) {
            System.err.println("Audio file not found: " + filePath);
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
            System.err.println("OpenAI transcription error: " + e.getMessage());
            return "";
        }
    }
}