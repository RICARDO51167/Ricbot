package ricbot.tool.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件读取状态管理器。
 */
public final class FileReadState {

    private FileReadState() {
    }

    private static final Map<String, ReadState> STATE = new ConcurrentHashMap<>();

    public static final class ReadState {
        private final long mtime;
        private final int offset;
        private final Integer limit;
        private final String contentHash;
        private final boolean canDedup;

        public ReadState(long mtime, int offset, Integer limit, String contentHash, boolean canDedup) {
            this.mtime = mtime;
            this.offset = offset;
            this.limit = limit;
            this.contentHash = contentHash;
            this.canDedup = canDedup;
        }

        public long getMtime() {
            return mtime;
        }

        public int getOffset() {
            return offset;
        }

        public Integer getLimit() {
            return limit;
        }

        public String getContentHash() {
            return contentHash;
        }

        public boolean isCanDedup() {
            return canDedup;
        }
    }

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static String hashFile(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);

            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static void recordRead(Path path, int offset, Integer limit) {
        try {
            Path p = path.toAbsolutePath().normalize();
            long mtime = Files.getLastModifiedTime(p).toMillis();

            STATE.put(normalize(p), new ReadState(
                    mtime,
                    offset,
                    limit,
                    hashFile(p),
                    true
            ));
        } catch (IOException ignored) {
        }
    }

    public static void recordWrite(Path path) {
        try {
            Path p = path.toAbsolutePath().normalize();
            long mtime = Files.getLastModifiedTime(p).toMillis();

            STATE.put(normalize(p), new ReadState(
                    mtime,
                    1,
                    null,
                    hashFile(p),
                    false
            ));
        } catch (IOException e) {
            STATE.remove(normalize(path));
        }
    }

    public static String checkRead(Path path) {
        Path p = path.toAbsolutePath().normalize();
        ReadState entry = STATE.get(normalize(p));
        if (entry == null) {
            return "警告：文件尚未被读取。请先读取文件以在编辑前验证内容。";
        }

        try {
            long currentMtime = Files.getLastModifiedTime(p).toMillis();
            if (currentMtime != entry.getMtime()) {
                String currentHash = hashFile(p);
                if (entry.getContentHash() != null && entry.getContentHash().equals(currentHash)) {
                    STATE.put(normalize(p), new ReadState(
                            currentMtime,
                            entry.getOffset(),
                            entry.getLimit(),
                            entry.getContentHash(),
                            entry.isCanDedup()
                    ));
                    return null;
                }
                return "警告：文件自上次读取后已被修改。请重新读取以在编辑前验证内容。";
            }
        } catch (IOException ignored) {
            return null;
        }

        return null;
    }

    public static boolean isUnchanged(Path path, int offset, Integer limit) {
        Path p = path.toAbsolutePath().normalize();
        ReadState entry = STATE.get(normalize(p));
        if (entry == null) {
            return false;
        }
        if (!entry.isCanDedup()) {
            return false;
        }
        if (entry.getOffset() != offset) {
            return false;
        }
        if (!equalsNullable(entry.getLimit(), limit)) {
            return false;
        }

        try {
            long currentMtime = Files.getLastModifiedTime(p).toMillis();
            return currentMtime == entry.getMtime();
        } catch (IOException e) {
            return false;
        }
    }

    public static void clear() {
        STATE.clear();
    }

    private static boolean equalsNullable(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}