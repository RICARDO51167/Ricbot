package ricbot.infra.persistence;

import java.util.List;
import java.util.Optional;

/** CAS-capable storage boundary implementable by local disk, SQL, Redis, or object storage. */
public interface SharedStateStore {
    long ANY_VERSION = -1;
    long MUST_NOT_EXIST = 0;

    Optional<SharedValue> get(String namespace, String key);
    SharedValue put(String namespace, String key, byte[] content, long expectedVersion);
    List<SharedValue> list(String namespace);
    boolean delete(String namespace, String key, long expectedVersion);
}
