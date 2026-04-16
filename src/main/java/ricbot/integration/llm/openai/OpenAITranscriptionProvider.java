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
 * 对应 Python: OpenAITranscriptionProvider
 */
public class OpenAITranscriptionProvider implements TranscriptionProvider {

    // 用于 JSON 序列化和反序列化的 ObjectMapper 实例
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // OpenAI API 密钥
    private final String apiKey;
    // 音频转录 API 的完整 URL
    private final String apiUrl;

    /**
     * 构造函数，仅传入 API 密钥，使用默认的 OpenAI 基础 URL
     *
     * @param apiKey OpenAI API 密钥
     */
    public OpenAITranscriptionProvider(String apiKey) {
        this(apiKey, null);
    }

    /**
     * 构造函数，传入 API 密钥和可选的基础 URL
     *
     * @param apiKey  OpenAI API 密钥
     * @param apiBase 可选的 API 基础 URL，如果为空则使用默认值
     */
    public OpenAITranscriptionProvider(String apiKey, String apiBase) {
        // 如果提供的 apiKey 不为空且非空白，则使用它；否则从环境变量中获取
        this.apiKey = (apiKey != null && !apiKey.isBlank())
                ? apiKey
                : System.getenv("OPENAI_API_KEY");

        // 解析并设置音频转录的完整 API URL
        this.apiUrl = resolveAudioTranscriptionsUrl(
                apiBase,
                "https://api.openai.com/v1",
                "https://api.openai.com/v1/audio/transcriptions"
        );
    }

    /**
     * 将音频文件转录为文本
     *
     * @param filePath 音频文件的路径
     * @return 转录后的文本，如果失败则返回空字符串
     */
    @Override
    public String transcribe(Path filePath) {
        // 检查 API 密钥是否已配置
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("未配置用于转录的 OpenAI API 密钥");
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
            // 构建 multipart 请求体
            byte[] body = MultipartBodyBuilder.build(boundary, filePath, "whisper-1");

            // 构建 HTTP POST 请求
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(60)) // 设置超时时间为 60 秒
                    .header("Authorization", "Bearer " + apiKey) // 设置认证头
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary) // 设置内容类型
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)) // 设置请求体
                    .build();

            // 创建 HttpClient 实例并发送请求
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 如果响应状态码表示错误，则抛出异常
            if (response.statusCode() >= 400) {
                throw new RuntimeException(response.body());
            }

            // 解析响应 JSON，提取 "text" 字段
            Map<String, Object> data = MAPPER.readValue(response.body(), new TypeReference<>() {});
            Object text = data.get("text");
            // 返回转录文本，如果为 null 则返回空字符串
            return text != null ? String.valueOf(text) : "";
        } catch (Exception e) {
            // 捕获异常并打印错误信息
            System.err.println("OpenAI 转录错误: " + e.getMessage());
            return "";
        }
    }

    /**
     * 解析音频转录 API 的完整 URL
     *
     * @param apiBase    用户提供的 API 基础 URL
     * @param defaultBase 默认的 API 基础 URL
     * @param defaultUrl  默认的完整 API URL
     * @return 完整的音频转录 API URL
     */
    private static String resolveAudioTranscriptionsUrl(String apiBase, String defaultBase, String defaultUrl) {
        // 处理 apiBase，如果为 null 则设为空字符串
        String base = apiBase != null ? apiBase.trim() : "";
        // 如果 base 为空，则返回默认 URL
        if (base.isBlank()) {
            return defaultUrl;
        }

        // 去除 base 末尾的斜杠
        String normalized = stripTrailingSlash(base);
        // 如果已经以 /audio/transcriptions 结尾，直接返回
        if (normalized.endsWith("/audio/transcriptions")) {
            return normalized;
        }

        String base2 = normalized;
        // 如果以 /v1 结尾，追加 /audio/transcriptions
        if (base2.endsWith("/v1")) {
            return base2 + "/audio/transcriptions";
        }
        // 如果以 /v1/ 结尾，去除末尾斜杠后追加 /audio/transcriptions
        if (base2.endsWith("/v1/")) {
            return stripTrailingSlash(base2) + "/audio/transcriptions";
        }

        // 去除默认基础 URL 末尾的斜杠
        String defBase = stripTrailingSlash(defaultBase);
        // 如果处理后的 base 与默认基础 URL 相同，追加 /audio/transcriptions
        if (base2.equals(defBase)) {
            return base2 + "/audio/transcriptions";
        }

        // 其他情况，直接追加 /audio/transcriptions
        return base2 + "/audio/transcriptions";
    }

    /**
     * 去除字符串末尾的斜杠
     *
     * @param s 输入字符串
     * @return 去除末尾斜杠后的字符串
     */
    private static String stripTrailingSlash(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        // 从末尾开始查找非斜杠字符
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        // 返回截取后的子字符串
        return s.substring(0, end);
    }
}
