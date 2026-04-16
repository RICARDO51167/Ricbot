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

    private MultipartBodyBuilder() {
    }

    public static byte[] build(String boundary, Path filePath, String modelName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writeLine(out, "--" + boundary);
        writeLine(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + filePath.getFileName() + "\"");
        writeLine(out, "Content-Type: application/octet-stream");
        writeLine(out, "");
        out.write(Files.readAllBytes(filePath));
        writeLine(out, "");

        writeLine(out, "--" + boundary);
        writeLine(out, "Content-Disposition: form-data; name=\"model\"");
        writeLine(out, "");
        out.write(modelName.getBytes(StandardCharsets.UTF_8));

        writeLine(out, "--" + boundary + "--");
        return out.toByteArray();
    }

    private static void writeLine(ByteArrayOutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}