package ricbot.tool.rag;

import ricbot.domain.rag.WorkspaceRagService;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class RagTool extends Tool {
    private final WorkspaceRagService ragService;

    public RagTool(Path workspace) {
        this(new WorkspaceRagService(workspace));
    }

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
        return true;
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("action", "string", "操作：index_workspace、search_code、search_docs、search_project_knowledge、find_related_files、explain_symbol、refresh_changed_files", true),
                ToolParam.of("query", "string", "检索查询", false),
                ToolParam.of("symbol", "string", "要解释的 Java symbol", false),
                ToolParam.of("limit", "integer", "返回数量限制", false).setDefaultValue(10)
        );
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String action = string(params.get("action")).toLowerCase();
        int limit = intValue(params.get("limit"), 10);
        return switch (action) {
            case "index_workspace" -> renderIndex(ragService.indexWorkspace());
            case "refresh_changed_files" -> renderIndex(ragService.refreshChangedFiles());
            case "search_code" -> renderSearch("rag code results", ragService.searchCode(string(params.get("query")), limit));
            case "search_docs" -> renderSearch("rag docs results", ragService.searchDocs(string(params.get("query")), limit));
            case "search_project_knowledge" -> renderSearch("rag project knowledge results", ragService.searchProjectKnowledge(string(params.get("query")), limit));
            case "find_related_files" -> renderSearch("rag related files", ragService.findRelatedFiles(string(params.get("query")), limit));
            case "explain_symbol" -> ragService.explainSymbol(stringOrDefault(params.get("symbol"), string(params.get("query"))));
            default -> "错误：未知 rag action：" + action;
        };
    }

    private String renderIndex(WorkspaceRagService.IndexReport report) {
        return "rag index updated\n"
                + "files: " + report.files() + "\n"
                + "chunks: " + report.chunks() + "\n"
                + "symbol_files: " + report.symbols() + "\n"
                + "added: " + report.added() + "\n"
                + "modified: " + report.modified() + "\n"
                + "deleted: " + report.deleted() + "\n"
                + "skipped: " + report.skipped() + "\n"
                + "chunks_file: " + report.chunksFile();
    }

    private String renderSearch(String title, List<WorkspaceRagService.SearchResult> results) {
        if (results.isEmpty()) {
            return "未找到 RAG 结果。";
        }
        StringBuilder sb = new StringBuilder(title).append("\n");
        for (WorkspaceRagService.SearchResult result : results) {
            WorkspaceRagService.FileChunk chunk = result.chunk();
            sb.append("- ").append(chunk.path())
                    .append(":").append(chunk.startLine()).append("-").append(chunk.endLine())
                    .append(" [").append(chunk.kind()).append("]")
                    .append(" score=").append(round(result.score()));
            if (result.snippet() != null && !result.snippet().isBlank()) {
                sb.append("\n  snippet: ").append(result.snippet().replace("\n", " "));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

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
            }
        }
        return fallback;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }
}
