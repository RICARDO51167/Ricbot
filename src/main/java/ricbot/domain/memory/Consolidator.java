package ricbot.domain.memory;

import ricbot.infra.template.PromptTemplates;
import ricbot.integration.llm.api.LLMProvider;
import ricbot.integration.llm.api.LLMResponse;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量级会话归档器。
 */
public class Consolidator {

    private static final Logger log = LoggerFactory.getLogger(Consolidator.class);
    private static final int SAFETY_BUFFER = 4096;
    private static final int MAX_CONSOLIDATION_ROUNDS = 8;
    private static final int MIN_KEEP_MESSAGES = 8;
    private static final int MAX_ARCHIVE_PROMPT_CHARS = 60_000;
    private static final int MAX_MESSAGE_SNIPPET_CHARS = 2_000;

    private final MemoryStore store;
    private final LLMProvider provider;
    private final String model;
    private final SessionManager sessions;
    private final Integer contextWindowTokens;

    private final int maxCompletionTokens;

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public Consolidator(
            MemoryStore store,
            LLMProvider provider,
            String model,
            SessionManager sessions,
            Integer contextWindowTokens,
            int maxCompletionTokens
    ) {
        this.store = store;
        this.provider = provider;
        this.model = model;
        this.sessions = sessions;
        this.contextWindowTokens = contextWindowTokens;
        this.maxCompletionTokens = maxCompletionTokens;
    }

    public Object getLock(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    public String archive(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        try {
            StringBuilder conversation = new StringBuilder();
            for (Map<String, Object> msg : messages) {
                String role = msg != null && msg.get("role") != null ? String.valueOf(msg.get("role")) : "unknown";
                Object contentObj = msg != null ? msg.get("content") : null;
                String content = stringifyMessageContent(contentObj);
                if (content.length() > MAX_MESSAGE_SNIPPET_CHARS) {
                    content = content.substring(0, MAX_MESSAGE_SNIPPET_CHARS) + "\n... (truncated)";
                }

                String line = role + ": " + content + "\n";
                if (conversation.length() + line.length() > MAX_ARCHIVE_PROMPT_CHARS) {
                    conversation.append("\n... (conversation truncated)\n");
                    break;
                }
                conversation.append(line);
            }

            Map<String, Object> kwargs = new HashMap<>();
            kwargs.put("conversation", conversation.toString());
            String prompt = PromptTemplates.renderTemplate("agent/consolidator_archive.md", true, kwargs);

            List<Map<String, Object>> promptMessages = new ArrayList<>();
            Map<String, Object> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", prompt);
            promptMessages.add(systemMsg);

            LLMResponse response = provider.chat(promptMessages, List.of(), model, null, null, null, null);
            String summary = response.getContent();

            String normalized = summary != null ? summary.trim() : "";
            if (normalized.isBlank() || "(nothing)".equalsIgnoreCase(normalized) || "(无内容)".equals(normalized)) {
                store.rawArchive(messages);
                return "(nothing)";
            }

            store.appendHistory(normalized);
            return normalized;
        } catch (Exception e) {
            log.warn("会话整合失败，转为原始消息存入历史", e);
            store.rawArchive(messages);
            return null;
        }
    }

    public void maybeConsolidateByTokens(Session session) {
        if (session.getMessages().isEmpty() || contextWindowTokens == null || contextWindowTokens <= 0) {
            return;
        }

        synchronized (getLock(session.getKey())) {
            int budget = contextWindowTokens - maxCompletionTokens - SAFETY_BUFFER;
            if (budget <= 0) {
                return;
            }

            int estimated = estimateTokens(session.getMessages());

            if (estimated < budget) {
                return;
            }

            log.info("开始对会话 {} 进行整合，预估 Token: {}, 预算: {}", 
                    session.getKey(), estimated, budget);

            for (int round = 0; round < MAX_CONSOLIDATION_ROUNDS; round++) {
                if (estimated <= budget) {
                    return;
                }
                if (session.getMessages().size() <= MIN_KEEP_MESSAGES) {
                    return;
                }

                int maxCut = session.getMessages().size() - MIN_KEEP_MESSAGES;
                int overflow = estimated - budget;
                int targetRemoveTokens = Math.max(budget / 4, overflow);

                int cut = pickCutIndex(session.getMessages(), maxCut, targetRemoveTokens);
                if (cut <= 0) {
                    return;
                }

                List<Map<String, Object>> chunk = new ArrayList<>(session.getMessages().subList(0, cut));
                String archived = archive(chunk);
                if (archived == null) {
                    return;
                }

                session.setMessages(new ArrayList<>(session.getMessages().subList(cut, session.getMessages().size())));
                session.setLastConsolidated(Math.max(0, session.getLastConsolidated() - cut));
                sessions.save(session);

                estimated = estimateTokens(session.getMessages());
            }
        }
    }

    private int pickCutIndex(List<Map<String, Object>> messages, int maxCut, int targetTokens) {
        int tokens = 0;
        int cut = 0;
        for (int i = 0; i < maxCut; i++) {
            tokens += estimateTokens(messages.get(i));
            cut = i + 1;
            if (tokens >= targetTokens) {
                break;
            }
        }
        if (cut <= 0) {
            return 0;
        }
        return adjustCutForToolLegality(messages, cut, maxCut);
    }

    private int adjustCutForToolLegality(List<Map<String, Object>> messages, int cut, int maxCut) {
        if (cut >= messages.size()) {
            return Math.min(cut, maxCut);
        }
        List<Map<String, Object>> remaining = messages.subList(cut, messages.size());
        int legalStart = findLegalMessageStart(remaining);
        int adjusted = cut + legalStart;
        return Math.min(adjusted, maxCut);
    }

    private int findLegalMessageStart(List<Map<String, Object>> messages) {
        Set<String> declared = new HashSet<>();
        int start = 0;

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (msg == null) {
                continue;
            }
            String role = String.valueOf(msg.get("role"));

            if ("assistant".equals(role)) {
                Object toolCallsObj = msg.get("tool_calls");
                if (toolCallsObj instanceof List<?> toolCalls) {
                    for (Object tcObj : toolCalls) {
                        if (tcObj instanceof Map<?, ?> tc) {
                            Object id = tc.get("id");
                            if (id != null) {
                                declared.add(String.valueOf(id));
                            }
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                Object tid = msg.get("tool_call_id");
                if (tid != null && !declared.contains(String.valueOf(tid))) {
                    start = i + 1;
                    declared.clear();
                }
            }
        }
        return start;
    }

    private int estimateTokens(List<Map<String, Object>> messages) {
        int total = 0;
        if (messages == null) {
            return 0;
        }
        for (Map<String, Object> msg : messages) {
            total += estimateTokens(msg);
        }
        return total;
    }

    private int estimateTokens(Map<String, Object> msg) {
        if (msg == null) {
            return 0;
        }
        String role = msg.get("role") != null ? String.valueOf(msg.get("role")) : "";
        Object contentObj = msg.get("content");
        int tokens = 0;

        tokens += approximateTokens(role);
        tokens += approximateTokens(stringifyMessageContent(contentObj));

        Object toolCalls = msg.get("tool_calls");
        if (toolCalls instanceof List<?> list) {
            tokens += 20;
            for (Object item : list) {
                tokens += approximateTokens(String.valueOf(item));
            }
        }

        Object name = msg.get("name");
        if (name != null) {
            tokens += approximateTokens(String.valueOf(name));
        }

        return tokens + 6;
    }

    private String stringifyMessageContent(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object itemObj : list) {
                if (!(itemObj instanceof Map<?, ?> rawItem)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) rawItem;
                String type = item.get("type") != null ? String.valueOf(item.get("type")) : "";
                if ("text".equals(type)) {
                    sb.append(item.get("text") != null ? String.valueOf(item.get("text")) : "");
                    sb.append("\n");
                } else if ("image_url".equals(type)) {
                    sb.append("[image]").append("\n");
                } else {
                    sb.append("[").append(type).append("]").append("\n");
                }
                if (sb.length() > MAX_MESSAGE_SNIPPET_CHARS) {
                    break;
                }
            }
            return sb.toString().trim();
        }
        return String.valueOf(content);
    }

    private int approximateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        int ascii = 0;
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x7F) {
                ascii++;
            } else {
                nonAscii++;
            }
        }

        int t = (ascii / 4) + (nonAscii / 2);
        return Math.max(1, t);
    }
}
