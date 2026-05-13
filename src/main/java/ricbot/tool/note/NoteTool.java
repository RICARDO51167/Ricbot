package ricbot.tool.note;

import ricbot.domain.note.NoteEntry;
import ricbot.domain.note.NoteService;
import ricbot.tool.api.Tool;
import ricbot.tool.api.ToolParam;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NoteTool extends Tool {
    private final NoteService noteService;

    public NoteTool(Path workspace) {
        this(new NoteService(workspace));
    }

    public NoteTool(NoteService noteService) {
        this.noteService = noteService;
    }

    @Override
    public String getName() {
        return "note";
    }

    @Override
    public String getDescription() {
        return "管理工作区结构化 Markdown 笔记：create、update、search、list、summary、promote、archive、delete。";
    }

    @Override
    public List<ToolParam> getParams() {
        return List.of(
                ToolParam.of("action", "string", "操作：create、update、search、list、summary、promote、archive、delete", true),
                ToolParam.of("id", "string", "笔记 ID，update/summary/promote/archive/delete 需要", false),
                ToolParam.of("title", "string", "标题，create 使用", false),
                ToolParam.of("category", "string", "分类：project、tasks、blockers、temporary、archive", false),
                ToolParam.of("type", "string", "类型：task_state、conclusion、blocker、action、reference、decision、note", false),
                ToolParam.of("content", "string", "笔记正文，create/update 使用", false),
                ToolParam.of("append", "string", "追加内容，update 使用", false),
                ToolParam.of("query", "string", "搜索关键词，search 使用", false),
                ToolParam.of("tags", "array", "标签列表，create 使用", false),
                ToolParam.of("limit", "integer", "返回数量限制", false).setDefaultValue(10)
        );
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String action = string(params.get("action")).toLowerCase();
        return switch (action) {
            case "create" -> renderEntry(noteService.create(
                    string(params.get("title")),
                    stringOrDefault(params.get("category"), "tasks"),
                    stringOrDefault(params.get("type"), "note"),
                    string(params.get("content")),
                    stringList(params.get("tags"))
            ), "note created");
            case "update" -> renderEntry(noteService.update(
                    string(params.get("id")),
                    nullableString(params.get("content")),
                    nullableString(params.get("append"))
            ), "note updated");
            case "search" -> renderSearch(params);
            case "list" -> renderList(intValue(params.get("limit"), 20));
            case "summary" -> noteService.summary(string(params.get("id")));
            case "promote" -> renderEntry(noteService.promote(
                    string(params.get("id")),
                    stringOrDefault(params.get("category"), "project")
            ), "note promoted");
            case "archive" -> renderEntry(noteService.archive(string(params.get("id"))), "note archived");
            case "delete" -> noteService.delete(string(params.get("id"))) ? "note deleted" : "note not found";
            default -> "错误：未知 note action：" + action;
        };
    }

    private String renderSearch(Map<String, Object> params) {
        String query = string(params.get("query"));
        int limit = intValue(params.get("limit"), 10);
        List<NoteService.SearchResult> results = noteService.search(query, limit);
        if (results.isEmpty()) {
            return "未找到笔记：" + query;
        }
        StringBuilder sb = new StringBuilder("note search results\n");
        for (NoteService.SearchResult result : results) {
            NoteEntry entry = result.entry();
            sb.append("- ").append(entry.id())
                    .append(" [").append(entry.category()).append("/").append(entry.type()).append("] ")
                    .append(entry.title())
                    .append(" score=").append(round(result.score()))
                    .append("\n  path: ").append(entry.path());
            if (result.snippet() != null && !result.snippet().isBlank()) {
                sb.append("\n  snippet: ").append(result.snippet().replace("\n", " "));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String renderList(int limit) {
        List<NoteEntry> entries = noteService.list().stream().limit(Math.max(1, limit)).toList();
        if (entries.isEmpty()) {
            return "暂无笔记。";
        }
        StringBuilder sb = new StringBuilder("notes\n");
        for (NoteEntry entry : entries) {
            sb.append("- ").append(entry.id())
                    .append(" [").append(entry.category()).append("/").append(entry.type()).append("] ")
                    .append(entry.title())
                    .append(" -> ").append(entry.path());
            if (entry.archived()) {
                sb.append(" (archived)");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String renderEntry(NoteEntry entry, String message) {
        Map<String, Object> out = new LinkedHashMap<>(entry.toMap());
        return message + "\n" + out;
    }

    private static String string(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static String nullableString(Object raw) {
        return raw != null ? String.valueOf(raw) : null;
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

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }
}
