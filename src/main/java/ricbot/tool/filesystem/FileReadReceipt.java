package ricbot.tool.filesystem;

import java.time.Instant;
import java.util.Map;

/** Durable optimistic-concurrency proof scoped to one Run and Workspace. */
public record FileReadReceipt(String runId, String taskId, String workspaceId, String logicalPath,
                              String sha256, int startLine, int endLine, boolean readToEof,
                              long fileSize, String source, Instant createdAt) {
    public FileReadReceipt {
        runId = clean(runId); taskId = clean(taskId); workspaceId = clean(workspaceId); logicalPath = clean(logicalPath);
        sha256 = clean(sha256); startLine = Math.max(1, startLine); endLine = Math.max(startLine, endLine);
        fileSize = Math.max(0, fileSize); source = clean(source); createdAt = createdAt != null ? createdAt : Instant.now();
    }
    public String key() { return workspaceId + ":" + logicalPath + ":" + sha256 + ":" + startLine + ":" + endLine; }
    public boolean full() { return startLine == 1 && readToEof; }
    public Map<String, Object> toMap() {
        return Map.ofEntries(Map.entry("runId", runId), Map.entry("taskId", taskId),
                Map.entry("workspaceId", workspaceId), Map.entry("logicalPath", logicalPath),
                Map.entry("sha256", sha256), Map.entry("startLine", startLine), Map.entry("endLine", endLine),
                Map.entry("readToEof", readToEof), Map.entry("fileSize", fileSize),
                Map.entry("source", source), Map.entry("createdAt", createdAt.toString()));
    }
    public static FileReadReceipt from(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        try { return new FileReadReceipt(text(map,"runId"), text(map,"taskId"), text(map,"workspaceId"),
                text(map,"logicalPath"), text(map,"sha256"), number(map,"startLine",1), number(map,"endLine",1),
                Boolean.parseBoolean(text(map,"readToEof")), longNumber(map,"fileSize"), text(map,"source"),
                Instant.parse(text(map,"createdAt"))); }
        catch (Exception ignored) { return null; }
    }
    private static String text(Map<?, ?> map, String key) { Object value=map.get(key); return value != null ? String.valueOf(value) : ""; }
    private static int number(Map<?, ?> map,String key,int fallback){Object v=map.get(key);return v instanceof Number n?n.intValue():fallback;}
    private static long longNumber(Map<?, ?> map,String key){Object v=map.get(key);return v instanceof Number n?n.longValue():0;}
    private static String clean(String value) { return value != null ? value.trim() : ""; }
}
