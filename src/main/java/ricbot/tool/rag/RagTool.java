package ricbot.tool.rag;

import ricbot.domain.rag.WorkspaceRagService;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * RAG (Retrieval-Augmented Generation) 工具，提供工作区知识库的索引与检索功能。
 * <p>
 * 支持的操作包括：
 * <ul>
 *     <li>index_workspace: 索引整个工作区</li>
 *     <li>refresh_changed_files: 刷新变更的文件</li>
 *     <li>search_code: 搜索代码片段</li>
 *     <li>search_docs: 搜索文档</li>
 *     <li>search_project_knowledge: 搜索项目知识</li>
 *     <li>find_related_files: 查找相关文件</li>
 *     <li>explain_symbol: 解释 Java 符号</li>
 * </ul>
 */
public class RagTool extends Tool {
    private final WorkspaceRagService ragService;

    /**
     * 基于工作区路径创建 RagTool 实例。
     *
     * @param workspace 工作区路径
     */
    public RagTool(Path workspace) {
        this(new WorkspaceRagService(workspace));
    }

    /**
     * 基于已有的 WorkspaceRagService 创建 RagTool 实例。
     *
     * @param ragService RAG 服务实例
     */
    public RagTool(WorkspaceRagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String getName() {
        return "rag";
    }

    @Override
    public String getDescription() {
        return "工作区知识库索引与检索：index_workspace、search_code、search_docs、search_project_knowledge、find_related_files、explain_symbol、refresh_changed_files。";
    }

    @Override
    public boolean isReadOnly() {
        // 虽然 index 操作会修改本地索引文件，但对用户而言是只读查询工具，不修改源代码
        return true;
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("action", "string", "操作类型：index_workspace, refresh_changed_files, search_code, search_docs, search_project_knowledge, find_related_files, explain_symbol", true),
                ToolParam.of("query", "string", "检索查询语句或关键词（用于搜索类操作）", false),
                ToolParam.of("symbol", "string", "要解释的 Java 符号名称（用于 explain_symbol 操作）", false),
                ToolParam.of("limit", "integer", "返回结果的数量限制，默认为 10", false).setDefaultValue(10)
        );
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String action = string(params.get("action")).toLowerCase();
        int limit = intValue(params.get("limit"), 10);

        try {
            return switch (action) {
                case "index_workspace" -> renderIndex(ragService.indexWorkspace());
                case "refresh_changed_files" -> renderIndex(ragService.refreshChangedFiles());
                case "search_code" -> renderSearch("RAG Code Search Results", ragService.searchCode(string(params.get("query")), limit));
                case "search_docs" -> renderSearch("RAG Docs Search Results", ragService.searchDocs(string(params.get("query")), limit));
                case "search_project_knowledge" -> renderSearch("RAG Project Knowledge Results", ragService.searchProjectKnowledge(string(params.get("query")), limit));
                case "find_related_files" -> renderSearch("RAG Related Files", ragService.findRelatedFiles(string(params.get("query")), limit));
                case "explain_symbol" -> ragService.explainSymbol(stringOrDefault(params.get("symbol"), string(params.get("query"))));
                default -> "错误：未知的 RAG 操作 '" + action + "'。支持的操作: index_workspace, refresh_changed_files, search_code, search_docs, search_project_knowledge, find_related_files, explain_symbol";
            };
        } catch (Exception e) {
            return "执行 RAG 操作时发生错误: " + e.getMessage();
        }
    }

    /**
     * 格式化索引报告。
     *
     * @param report 索引报告
     * @return 格式化的字符串
     */
    private String renderIndex(WorkspaceRagService.IndexReport report) {
        if (report == null) {
            return "索引操作完成，但未返回详细报告。";
        }
        return "rag index updated\n"
                + "  Files: " + report.files() + "\n"
                + "  Chunks: " + report.chunks() + "\n"
                + "  Symbol Files: " + report.symbols() + "\n"
                + "  Added: " + report.added() + "\n"
                + "  Modified: " + report.modified() + "\n"
                + "  Deleted: " + report.deleted() + "\n"
                + "  Skipped: " + report.skipped() + "\n"
                + "  Chunks File: " + report.chunksFile();
    }

    /**
     * 格式化搜索结果。
     *
     * @param title   结果标题
     * @param results 搜索结果列表
     * @return 格式化的字符串
     */
    private String renderSearch(String title, List<WorkspaceRagService.SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "未找到相关的 RAG 结果。";
        }

        StringBuilder sb = new StringBuilder(title).append("\n");
        for (int i = 0; i < results.size(); i++) {
            WorkspaceRagService.SearchResult result = results.get(i);
            WorkspaceRagService.FileChunk chunk = result.chunk();
            
            sb.append(i + 1).append(". ").append(chunk.path())
                    .append(":").append(chunk.startLine()).append("-").append(chunk.endLine())
                    .append(" [").append(chunk.kind()).append("]")
                    .append(" (score: ").append(round(result.score())).append(")\n");
            
            if (result.snippet() != null && !result.snippet().isBlank()) {
                // 限制 snippet 长度以避免输出过长，并清理换行符
                String snippet = result.snippet().replace("\n", " ").trim();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   Snippet: ").append(snippet).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // --- Helper Methods ---

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String stringOrDefault(Object raw, String fallback) {
        String value = string(raw);
        return value.isBlank() ? fallback : value;
    }

    private static int intValue(Object raw, int fallback) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(String.valueOf(raw));
            } catch (Exception ignored) {
                // Ignore parsing errors
            }
        }
        return fallback;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }
}
