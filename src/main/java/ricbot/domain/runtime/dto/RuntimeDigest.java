package ricbot.domain.runtime.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class RuntimeDigest {
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private RuntimeDigest() { }

    public static String sha256(Object value) {
        try {
            JsonNode tree = canonicalize(CANONICAL.valueToTree(value));
            byte[] bytes = CANONICAL.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("cannot calculate runtime digest", e);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node;
        if (node.isArray()) {
            ArrayNode array = CANONICAL.createArrayNode();
            node.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        ObjectNode object = CANONICAL.createObjectNode();
        java.util.TreeMap<String, JsonNode> fields = new java.util.TreeMap<>();
        node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        fields.forEach((name, child) -> object.set(name, canonicalize(child)));
        return object;
    }
}
