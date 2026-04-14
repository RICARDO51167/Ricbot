package ricbot.llm.transcription;

import java.nio.file.Path;

/**
 * 转写 provider 接口
 */
public interface TranscriptionProvider {
    String transcribe(Path filePath);
}