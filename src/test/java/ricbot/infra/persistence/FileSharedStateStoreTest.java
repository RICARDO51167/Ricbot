package ricbot.infra.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileSharedStateStoreTest {
    @Test
    void supportsVersionedCompareAndSet(@TempDir Path workspace) {
        FileSharedStateStore store = new FileSharedStateStore(workspace);
        SharedValue first = store.put("runs", "unsafe/../../key", bytes("one"), SharedStateStore.MUST_NOT_EXIST);
        SharedValue second = store.put("runs", "unsafe/../../key", bytes("two"), first.version());

        assertEquals(2, second.version());
        assertEquals("two", new String(store.get("runs", "unsafe/../../key").orElseThrow().content(), StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () ->
                store.put("runs", "unsafe/../../key", bytes("stale"), first.version()));
        assertEquals(1, store.list("runs").size());
        assertTrue(store.delete("runs", "unsafe/../../key", second.version()));
        assertFalse(store.delete("runs", "unsafe/../../key", SharedStateStore.ANY_VERSION));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
