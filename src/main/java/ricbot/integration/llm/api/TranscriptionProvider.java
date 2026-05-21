package ricbot.integration.llm.api;

import java.nio.file.Path;

/**
 * 转写提供者接口
 */
public interface TranscriptionProvider {
    String transcribe(Path filePath);
}