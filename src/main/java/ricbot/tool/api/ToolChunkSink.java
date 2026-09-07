package ricbot.tool.api;

@FunctionalInterface
public interface ToolChunkSink {
    void accept(ToolChunk chunk) throws Exception;
    static ToolChunkSink discard() { return ignored -> { }; }
}
