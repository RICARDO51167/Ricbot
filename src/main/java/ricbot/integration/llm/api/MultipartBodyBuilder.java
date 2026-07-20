package ricbot.integration.llm.api;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * multipart/form-data 构造辅助类
 */
public final class MultipartBodyBuilder {

    private static final long MAX_AUDIO_FILE_BYTES = 25L * 1024 * 1024;

    // 私有构造函数，防止实例化
    private MultipartBodyBuilder() {
    }

    /**
     * 构建 multipart/form-data 请求体
     *
     * @param boundary  分隔符
     * @param filePath  文件路径
     * @param modelName 模型名称
     * @return 构建好的请求体发布器
     * @throws IOException IO异常
     */
    public static HttpRequest.BodyPublisher buildPublisher(String boundary, Path filePath, String modelName)
            throws IOException {
        validateFileSize(filePath);

        byte[] filePartHeader = line(
                "--" + boundary,
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + filePath.getFileName() + "\"",
                "Content-Type: application/octet-stream",
                ""
        );
        byte[] filePartFooter = line("");
        byte[] modelPart = line(
                "--" + boundary,
                "Content-Disposition: form-data; name=\"model\"",
                "",
                modelName,
                "--" + boundary + "--"
        );

        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(filePartHeader),
                HttpRequest.BodyPublishers.ofFile(filePath),
                HttpRequest.BodyPublishers.ofByteArray(filePartFooter),
                HttpRequest.BodyPublishers.ofByteArray(modelPart)
        );
    }

    /**
     * 向输出流中写入一行数据（包含换行符）
     *
     * @param lines 要写入的行内容
     * @return 带 CRLF 的字节数组
     */
    private static byte[] line(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void validateFileSize(Path filePath) throws IOException {
        long fileSize = Files.size(filePath);
        if (fileSize > MAX_AUDIO_FILE_BYTES) {
            throw new IOException("音频文件过大，超过 25MB 限制: " + fileSize + " bytes");
        }
    }
}
