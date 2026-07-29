package ricbot.domain.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.session.Session;
import ricbot.domain.session.SessionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EvalRuntimeState {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private EvalRuntimeState() {
    }

    static Map<String, Object> sessionState(SessionManager sessions, String sessionKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", sessionKey);
        if (sessions == null || sessionKey == null || sessionKey.isBlank()) {
            out.put("available", false);
            out.put("reason", "session unavailable");
            return out;
        }
        try {
            Session session = sessions.find(sessionKey).orElse(null);
            if (session == null) {
                out.put("available", false);
                out.put("message_count", 0);
                out.put("role_counts", Map.of());
                out.put("metadata_keys", List.of());
                return out;
            }
            List<Map<String, Object>> messages = session.getMessages() != null ? session.getMessages() : List.of();
            out.put("available", true);
            out.put("message_count", messages.size());
            out.put("last_consolidated", session.getLastConsolidated());
            out.put("metadata_keys", sortedKeys(session.getMetadata()));
            out.put("role_counts", roleCounts(messages));
            out.put("last_message", compactMessage(messages.isEmpty() ? null : messages.get(messages.size() - 1)));
            out.put("content_sha256", sha256Json(messages));
        } catch (Exception e) {
            out.put("available", false);
            out.put("reason", e.getMessage());
        }
        return out;
    }

    static Map<String, Object> memoryState(Path workspace) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path root = workspace != null ? workspace.toAbsolutePath().normalize() : null;
        Path memoryDir = root != null ? root.resolve("memory") : null;
        out.put("available", root != null && Files.exists(memoryDir));
        out.put("path", memoryDir != null ? memoryDir.toString() : "");
        out.put("memory_md_sha256", fileSha(root != null ? root.resolve("memory").resolve("MEMORY.md") : null));
        out.put("user_md_sha256", fileSha(root != null ? root.resolve("USER.md") : null));
        out.put("soul_md_sha256", fileSha(root != null ? root.resolve("SOUL.md") : null));
        out.put("history_count", jsonlCount(memoryDir != null ? memoryDir.resolve("history.jsonl") : null));
        out.put("entry_count", jsonlCount(memoryDir != null ? memoryDir.resolve("memory_entries.jsonl") : null));
        return out;
    }

    static List<String> restoreSession(SessionManager sessions, String sessionKey, Map<String, Object> beforeState) {
        List<String> errors = new ArrayList<>();
        if (sessions == null || sessionKey == null || sessionKey.isBlank()) {
            return errors;
        }
        try {
            if (beforeState == null || !Boolean.TRUE.equals(beforeState.get("_existed"))) {
                sessions.delete(sessionKey);
                return errors;
            }
            Session session = sessions.getOrCreate(sessionKey);
            Object rawMessages = beforeState != null ? beforeState.get("_messages") : null;
            Object rawMetadata = beforeState != null ? beforeState.get("_metadata") : null;
            int lastConsolidated = numberValue(beforeState != null ? beforeState.get("_last_consolidated") : null, 0);
            session.setMessages(objectMapList(rawMessages));
            session.setMetadata(objectMap(rawMetadata));
            session.setLastConsolidated(lastConsolidated);
            sessions.save(session);
            sessions.invalidate(sessionKey);
        } catch (Exception e) {
            errors.add("failed to restore session " + sessionKey + ": " + e.getMessage());
        }
        return errors;
    }

    static Map<String, Object> sessionRestoreSnapshot(SessionManager sessions, String sessionKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (sessions == null || sessionKey == null || sessionKey.isBlank()) {
            return out;
        }
        try {
            Session session = sessions.find(sessionKey).orElse(null);
            if (session == null) {
                out.put("_existed", false);
                return out;
            }
            out.put("_existed", true);
            out.put("_messages", session.getMessages() != null ? session.getMessages() : List.of());
            out.put("_metadata", session.getMetadata() != null ? session.getMetadata() : Map.of());
            out.put("_last_consolidated", session.getLastConsolidated());
        } catch (Exception ignored) {
        }
        return out;
    }

    static String readMemoryText(Path workspace, String relativePath) {
        try {
            if (workspace == null || relativePath == null || relativePath.isBlank()) {
                return "";
            }
            Path root = workspace.toAbsolutePath().normalize();
            Path target = root.resolve(relativePath).normalize();
            if (!target.startsWith(root) || !Files.exists(target) || !Files.isRegularFile(target)) {
                return "";
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, Object> compactMessage(Map<String, Object> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", raw.get("role"));
        out.put("name", raw.get("name"));
        Object content = raw.get("content");
        String text = content != null ? String.valueOf(content) : "";
        out.put("content_preview", text.length() > 240 ? text.substring(0, 240) : text);
        return out;
    }

    private static Map<String, Integer> roleCounts(List<Map<String, Object>> messages) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> message : messages != null ? messages : List.<Map<String, Object>>of()) {
            String role = String.valueOf(message.getOrDefault("role", ""));
            if (!role.isBlank()) {
                counts.merge(role, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static List<String> sortedKeys(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(String::compareTo);
        return keys;
    }

    private static String sha256Json(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = MAPPER.writeValueAsBytes(value);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return "";
        }
    }

    private static String fileSha(Path path) {
        try {
            if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
                return "";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static int jsonlCount(Path path) {
        try {
            if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
                return 0;
            }
            int count = 0;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectMapList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(objectMap(map));
            } else if (item instanceof Map) {
                out.add(MAPPER.convertValue(item, MAP_TYPE));
            }
        }
        return out;
    }

    private static Map<String, Object> objectMap(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static int numberValue(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }
}
