package ricbot.domain.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 提示词上下文包，用于管理和渲染不同部分的上下文信息
final class PromptContextBundle {

    // 定义上下文部分的固定顺序
    static final List<String> ORDER = List.of(
            "recent_history",   // 最近的历史记录
            "task_state",       // 任务状态
            "user_profile",     // 用户画像
            "memory_recall",    // 记忆召回
            "tool_trace"        // 工具调用轨迹
    );

    // 使用 LinkedHashMap 保持插入顺序，存储各个部分的内容列表
    private final Map<String, List<String>> sections = new LinkedHashMap<>();

    // 构造函数，初始化所有预定义的上下文部分为空列表
    PromptContextBundle() {
        for (String key : ORDER) {
            sections.put(key, new ArrayList<>());
        }
    }

    // 向指定部分添加一项内容
    void addItem(String section, String item) {
        // 如果部分名或内容为空，则直接返回
        if (section == null || item == null || item.isBlank()) {
            return;
        }
        String normalized = item.trim();
        List<String> target = sections.computeIfAbsent(section, ignored -> new ArrayList<>());
        if (!target.contains(normalized)) {
            target.add(normalized);
        }
    }

    // 获取指定部分的内容列表，如果不存在则返回空列表
    List<String> section(String section) {
        return sections.getOrDefault(section, List.of());
    }

    // 判断所有部分是否都为空
    boolean isEmpty() {
        return sections.values().stream().allMatch(List::isEmpty);
    }

    // 渲染上下文为 Markdown 格式的字符串
    String render() {
        StringBuilder sb = new StringBuilder();
        // 按照预定义的顺序遍历各个部分
        for (String key : ORDER) {
            List<String> items = sections.get(key);
            // 如果该部分没有内容，则跳过
            if (items == null || items.isEmpty()) {
                continue;
            }
            // 如果 StringBuilder 不为空，添加两个换行符以分隔不同部分
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            // 添加部分标题
            sb.append("## ").append(key).append("\n");
            // 添加该部分下的每一项内容，格式为 "- 内容"
            for (String item : items) {
                sb.append("- ").append(item).append("\n");
            }
        }
        // 返回修剪后的字符串，去除首尾空白
        return sb.toString().trim();
    }
}
