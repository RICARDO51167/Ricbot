package ricbot.domain.memory;

import java.util.List;

public class MemoryWritePolicy {

    public boolean shouldGenerateCandidates(String userText) {
        String text = normalize(userText);
        return text.length() >= 6 && text.length() <= 500;
    }

    public List<MemoryEntry> createCandidates(String userText) {
        String text = normalize(userText);
        if (!shouldGenerateCandidates(text)) {
            return List.of();
        }

        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (containsAny(text, "我喜欢", "我偏好", "我希望", "以后请", "记住")
                || containsAny(lower, "i prefer", "remember that", "please remember")) {
            return List.of(new MemoryEntry()
                    .setType(MemoryEntry.TYPE_PREFERENCE)
                    .setScope(MemoryEntry.SCOPE_LONG_TERM)
                    .setSummary(text)
                    .setDetails("即时候选：来自用户明确偏好或记忆请求")
                    .setImportance(0.75d)
                    .setConfidence(0.75d)
                    .setSource("candidate")
                    .setTags(List.of("user")));
        }

        if (containsAny(text, "项目", "代码库", "仓库")
                && containsAny(text, "使用", "基于", "采用", "需要", "约定", "规范")) {
            return List.of(new MemoryEntry()
                    .setType(MemoryEntry.TYPE_PROJECT)
                    .setScope(MemoryEntry.SCOPE_LONG_TERM)
                    .setSummary(text)
                    .setDetails("即时候选：来自用户描述的项目事实")
                    .setImportance(0.70d)
                    .setConfidence(0.70d)
                    .setSource("candidate")
                    .setTags(List.of("project")));
        }

        if (containsAny(text, "流程", "步骤", "规范", "约定")
                || containsAny(lower, "workflow", "convention", "standard")) {
            return List.of(new MemoryEntry()
                    .setType(MemoryEntry.TYPE_WORKFLOW)
                    .setScope(MemoryEntry.SCOPE_LONG_TERM)
                    .setSummary(text)
                    .setDetails("即时候选：来自用户描述的流程或约定")
                    .setImportance(0.68d)
                    .setConfidence(0.68d)
                    .setSource("candidate")
                    .setTags(List.of("workflow")));
        }
        return List.of();
    }

    private String normalize(String userText) {
        return userText != null ? userText.trim() : "";
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
