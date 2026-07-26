package ricbot.domain.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

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
            JsonNode tree = CANONICAL.valueToTree(value);
            byte[] bytes = CANONICAL.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("cannot calculate runtime digest", e);
        }
    }
}
