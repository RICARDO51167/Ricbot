package ricbot.integration.llm.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class MultipartBodyBuilderTest {

    @Test
    void buildPublisher_usesStreamingMultipartBody(@TempDir Path tempDir) throws Exception {
        Path audio = tempDir.resolve("sample.wav");
        Files.writeString(audio, "audio-data", StandardCharsets.UTF_8);

        String boundary = "----test-boundary";
        HttpRequest.BodyPublisher publisher = MultipartBodyBuilder.buildPublisher(boundary, audio, "whisper-1");

        long expectedLength =
                bytes("--" + boundary).length
                        + bytes("Content-Disposition: form-data; name=\"file\"; filename=\"sample.wav\"").length
                        + bytes("Content-Type: application/octet-stream").length
                        + bytes("").length
                        + Files.size(audio)
                        + bytes("").length
                        + bytes("--" + boundary).length
                        + bytes("Content-Disposition: form-data; name=\"model\"").length
                        + bytes("").length
                        + bytes("whisper-1").length
                        + bytes("--" + boundary + "--").length;

        assertEquals(expectedLength, publisher.contentLength());
    }

    @Test
    void buildPublisher_rejectsOversizedAudio(@TempDir Path tempDir) throws Exception {
        Path audio = tempDir.resolve("large.wav");
        byte[] oversized = new byte[(25 * 1024 * 1024) + 1];
        Files.write(audio, oversized);

        IOException error = assertThrows(
                IOException.class,
                () -> MultipartBodyBuilder.buildPublisher("----test-boundary", audio, "whisper-1")
        );

        assertTrue(error.getMessage().contains("25MB"), error.getMessage());
    }

    private static byte[] bytes(String line) {
        return (line + "\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
