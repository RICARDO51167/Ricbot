package ricbot.integration.llm.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * multipart/form-data 构造辅助类
 */
public final class MultipartBodyBuilder {

    // 私有构造函数，防止实例化
    private MultipartBodyBuilder() {
    }

    /**
     * 构建 multipart/form-data 请求体
     *
     * @param boundary  分隔符
     * @param filePath  文件路径
     * @param modelName 模型名称
     * @return 构建好的字节数组
     * @throws IOException IO异常
     */
    public static byte[] build(String boundary, Path filePath, String modelName) throws IOException {
        // 创建字节输出流
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 写入文件部分的头部信息
        writeLine(out, "--" + boundary); // 分隔符起始
        writeLine(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + filePath.getFileName() + "\""); // 文件字段描述
        writeLine(out, "Content-Type: application/octet-stream"); // 文件内容类型
        writeLine(out, ""); // 空行，分隔头部和主体
        out.write(Files.readAllBytes(filePath)); // 写入文件内容
        writeLine(out, ""); // 空行，结束文件部分

        // 写入模型名称部分的头部信息
        writeLine(out, "--" + boundary); // 分隔符起始
        writeLine(out, "Content-Disposition: form-data; name=\"model\""); // 模型字段描述
        writeLine(out, ""); // 空行，分隔头部和主体
        writeLine(out, modelName); // 写入模型名称

        // 写入结束分隔符
        writeLine(out, "--" + boundary + "--"); // 结束分隔符
        return out.toByteArray(); // 返回字节数组
    }

    /**
     * 向输出流中写入一行数据（包含换行符）
     *
     * @param out  输出流
     * @param line 要写入的行内容
     * @throws IOException IO异常
     */
    private static void writeLine(ByteArrayOutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8)); // 写入行内容
        out.write("\r\n".getBytes(StandardCharsets.UTF_8)); // 写入换行符
    }
}