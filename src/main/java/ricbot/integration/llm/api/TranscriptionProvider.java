package ricbot.integration.llm.api;

import java.nio.file.Path;

/**
 * 转写提供者接口
 */
public interface TranscriptionProvider {
    /**
     * 将音频文件转写为文本
     * @param filePath 音频文件路径
     * @return 转写后的文本内容
     */
    String transcribe(Path filePath);
}