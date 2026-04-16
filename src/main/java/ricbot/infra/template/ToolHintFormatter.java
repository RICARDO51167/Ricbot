package ricbot.infra.template;


import ricbot.infra.fs.DisplayPathUtils;
import ricbot.integration.llm.api.ToolCallRequest;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ToolHintFormatter {

    // 定义工具调用的格式规范映射，键为工具名称，值为对应的格式规范对象
    private static final Map<String, FormatSpec> TOOL_FORMATS = Map.of(
            "read_file", new FormatSpec(List.of("path", "file_path"), "read %s", true, false),
            "write_file", new FormatSpec(List.of("path", "file_path"), "write %s", true, false),
            "edit_file", new FormatSpec(List.of("file_path", "path"), "edit %s", true, false),
            "glob", new FormatSpec(List.of("pattern"), "glob \"%s\"", false, false),
            "grep", new FormatSpec(List.of("pattern"), "grep \"%s\"", false, false),
            "exec", new FormatSpec(List.of("command"), "$ %s", false, true),
            "web_search", new FormatSpec(List.of("query"), "search \"%s\"", false, false),
            "web_fetch", new FormatSpec(List.of("url"), "fetch %s", true, false),
            "list_dir", new FormatSpec(List.of("path"), "ls %s", true, false)
    );

    // 正则表达式用于匹配命令中的路径，支持双引号、单引号和无引号的路径
    private static final Pattern PATH_IN_CMD_RE = Pattern.compile(
            "\"(?<double>(?:[A-Za-z]:[/\\\\]|~/|/)[^\"]+)\"" +
                    "|'(?<single>(?:[A-Za-z]:[/\\\\]|~/|/)[^']+)'" +
                    "|(?<bare>(?:[A-Za-z]:[/\\\\]|~/|(?<=\\s)/)[^\\s;&|<>\"']+)"
    );

    // 私有构造函数，防止实例化
    private ToolHintFormatter() {
    }

    /**
     * 格式化工具调用提示列表
     * @param toolCalls 工具调用请求列表
     * @return 格式化后的提示字符串
     */
    public static String formatToolHints(List<ToolCallRequest> toolCalls) {
        // 如果列表为空或null，返回空字符串
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "";
        }

        // 存储格式化后的单个提示
        List<String> formatted = new ArrayList<>();
        for (ToolCallRequest tc : toolCalls) {
            // 获取该工具名称对应的格式规范
            FormatSpec spec = TOOL_FORMATS.get(tc.getName());
            if (spec != null) {
                // 如果存在已知格式，使用已知格式进行格式化
                formatted.add(formatKnown(tc, spec));
            } else if (tc.getName() != null && tc.getName().startsWith("mcp_")) {
                // 如果是MCP工具，使用MCP格式化处理
                formatted.add(formatMcp(tc));
            } else {
                // 否则使用 fallback 格式化处理
                formatted.add(formatFallback(tc));
            }
        }

        // 合并连续的相同提示并计数
        List<CountedHint> hints = new ArrayList<>();
        for (String hint : formatted) {
            // 如果当前提示与上一个提示相同，则增加计数
            if (!hints.isEmpty() && hints.get(hints.size() - 1).hint.equals(hint)) {
                hints.get(hints.size() - 1).count++;
            } else {
                // 否则添加新的提示项
                hints.add(new CountedHint(hint, 1));
            }
        }

        // 构建最终输出列表，处理重复计数显示
        List<String> out = new ArrayList<>();
        for (CountedHint h : hints) {
            // 如果计数大于1，添加计数后缀，否则直接添加提示
            out.add(h.count > 1 ? h.hint + " × " + h.count : h.hint);
        }
        // 使用逗号和空格连接所有提示
        return String.join(", ", out);
    }

    /**
     * 格式化已知类型的工具调用
     * @param tc 工具调用请求
     * @param spec 格式规范
     * @return 格式化后的字符串
     */
    private static String formatKnown(ToolCallRequest tc, FormatSpec spec) {
        // 提取关键参数值
        String value = extractArg(tc, spec.keyArgs);
        if (value == null) {
            // 如果没有提取到值，返回工具名称
            return tc.getName();
        }

        // 根据规范类型处理值的显示
        if (spec.isPath) {
            // 如果是路径，进行路径缩写处理
            value = DisplayPathUtils.abbreviatePath(value, 40);
        } else if (spec.isCommand) {
            // 如果是命令，进行命令缩写处理
            value = abbreviateCommand(value, 60);
        } else if (value.length() > 40) {
            // 如果长度超过40，截断并添加省略号
            value = value.substring(0, 40) + "…";
        }

        // 使用模板格式化返回值
        return spec.template.formatted(value);
    }

    /**
     * 格式化MCP工具调用
     * @param tc 工具调用请求
     * @return 格式化后的字符串
     */
    private static String formatMcp(ToolCallRequest tc) {
        // 将mcp_前缀替换为mcp:
        return tc.getName().replaceFirst("^mcp_", "mcp:");
    }

    /**
     * 格式化未知类型的工具调用（fallback）
     * @param tc 工具调用请求
     * @return 格式化后的字符串
     */
    private static String formatFallback(ToolCallRequest tc) {
        // 尝试提取任意字符串参数
        String value = extractAnyString(tc.getArguments());
        if (value == null) {
            // 如果没有提取到值，返回工具名称
            return tc.getName();
        }
        // 如果长度超过40，截断并添加省略号
        if (value.length() > 40) {
            value = value.substring(0, 40) + "…";
        }
        // 返回工具名称和参数值的组合
        return tc.getName() + "(\"" + value + "\")";
    }

    /**
     * 从工具调用参数中提取指定键的值
     * @param tc 工具调用请求
     * @param keys 优先查找的键列表
     * @return 提取到的字符串值，如果没有则返回null
     */
    private static String extractArg(ToolCallRequest tc, List<String> keys) {
        Map<String, Object> args = tc.getArguments();
        if (args == null) {
            return null;
        }
        // 遍历优先键列表，查找非空字符串值
        for (String key : keys) {
            Object v = args.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        // 如果优先键未找到，尝试提取任意字符串
        return extractAnyString(args);
    }

    /**
     * 从参数映射中提取第一个非空字符串值
     * @param args 参数映射
     * @return 提取到的字符串值，如果没有则返回null
     */
    private static String extractAnyString(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        // 遍历所有值，返回第一个非空字符串
        for (Object v : args.values()) {
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * 缩写命令字符串中的路径部分
     * @param cmd 原始命令字符串
     * @param maxLen 最大允许长度
     * @return 缩写后的命令字符串
     */
    private static String abbreviateCommand(String cmd, int maxLen) {
        if (cmd == null) {
            return "";
        }
        // 使用正则表达式匹配命令中的路径
        Matcher m = PATH_IN_CMD_RE.matcher(cmd);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            // 获取匹配到的路径原始值
            String raw = firstNonNull(m.group("double"), m.group("single"), m.group("bare"));
            // 对路径进行缩写处理
            String repl = DisplayPathUtils.abbreviatePath(raw, 30);
            // 替换匹配到的部分
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        String out = sb.toString().trim();
        // 如果结果长度超过最大限制，截断并添加省略号
        return out.length() <= maxLen ? out : out.substring(0, maxLen) + "…";
    }

    /**
     * 返回第一个非null的字符串
     * @param vals 字符串数组
     * @return 第一个非null字符串，如果都为null则返回空字符串
     */
    private static String firstNonNull(String... vals) {
        for (String v : vals) {
            if (v != null) return v;
        }
        return "";
    }

    // 记录类，定义工具格式规范
    private record FormatSpec(List<String> keyArgs, String template, boolean isPath, boolean isCommand) {}
    
    // 内部类，用于存储提示及其出现次数
    private static class CountedHint {
        String hint; int count;
        CountedHint(String hint, int count) { this.hint = hint; this.count = count; }
    }
}