package ricbot.integration.llm.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 对应 Python: GroqTranscriptionProvider
 */
public class GroqTranscriptionProvider implements TranscriptionProvider {

    // 创建 ObjectMapper 实例，用于 JSON 的序列化和反序列化
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Groq API 密钥
    private final String apiKey;
    // Groq 语音转录 API 的地址
    private final String apiUrl;

    /**
     * 构造函数，初始化 API 密钥
     * @param apiKey 传入的 API 密钥，如果为空则从环境变量中获取
     */
    public GroqTranscriptionProvider(String apiKey) {
        this(apiKey, null);
    }

    public GroqTranscriptionProvider(String apiKey, String apiBase) {
        // 如果传入的 apiKey 不为空且非空白，则使用传入的值，否则从环境变量 GROQ_API_KEY 中获取
        this.apiKey = (apiKey != null && !apiKey.isBlank())
                ? apiKey
                : System.getenv("GROQ_API_KEY");

        this.apiUrl = resolveAudioTranscriptionsUrl(
                apiBase,
                "https://api.groq.com/openai/v1",
                "https://api.groq.com/openai/v1/audio/transcriptions"
        );
    }

    /**
     * 转录音频文件为文本
     * @param filePath 音频文件的路径
     * @return 转录后的文本，如果出错则返回空字符串
     */
    @Override
    public String transcribe(Path filePath) {
        // 检查 API 密钥是否配置
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("未配置用于转录的 Groq API 密钥");
            return "";
        }

        // 检查音频文件是否存在
        if (filePath == null || !Files.exists(filePath)) {
            System.err.println("未找到音频文件: " + filePath);
            return "";
        }

        try {
            // 生成 multipart/form-data 的边界字符串
            String boundary = "----NanobotBoundary" + UUID.randomUUID().toString().replace("-", "");
            HttpRequest.BodyPublisher body = MultipartBodyBuilder.buildPublisher(boundary, filePath, "whisper-large-v3");

            // 构建 HTTP 请求
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(60)) // 设置超时时间为 60 秒
                    .header("Authorization", "Bearer " + apiKey) // 设置授权头
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary) // 设置内容类型和边界
                    .POST(body) // 设置 POST 请求体
                    .build();

            // 创建 HttpClient 实例
            HttpClient client = HttpClient.newHttpClient();
            // 发送请求并获取响应
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 如果响应状态码大于等于 400，抛出异常
            if (response.statusCode() >= 400) {
                throw new RuntimeException(response.body());
            }

            // 解析响应 JSON 数据
            Map<String, Object> data = MAPPER.readValue(response.body(), new TypeReference<>() {});
            // 获取转录文本
            Object text = data.get("text");
            // 返回转录文本，如果为 null 则返回空字符串
            return text != null ? String.valueOf(text) : "";
        } catch (Exception e) {
            // 捕获异常并打印错误信息
            System.err.println("Groq 转录错误: " + e.getMessage());
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

        String defBase = stripTrailingSlash(defaultBase);
        if (normalized.equals(defBase)) {
            return normalized + "/audio/transcriptions";
        }

        return normalized + "/audio/transcriptions";
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
