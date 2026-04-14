package ricbot.tool.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件读取状态管理器。
 * <p>
 * 对应 Python: read_state.py
 * <p>
 * 主要目标：
 * 1. 记录文件最近一次读取状态（包括修改时间、读取偏移量、限制行数、内容哈希等）。
 * 2. 为 edit 前的 read-before-edit 提供校验，确保在编辑前文件未被外部修改。
 * 3. 支持重复读取去重判断，避免不必要的重复处理。
 */
public final class FileReadState {

    /**
     * 私有构造函数，防止实例化。
     */
    private FileReadState() {
    }

    /**
     * 存储文件路径到其读取状态的映射。
     * Key: 标准化后的绝对路径字符串。
     * Value: 该文件的读取状态信息。
     */
    private static final Map<String, ReadState> STATE = new ConcurrentHashMap<>();

    /**
     * 文件读取状态内部类。
     * 封装了单次文件读取操作的相关元数据。
     */
    public static final class ReadState {
        /**
         * 文件最后修改时间戳（毫秒）。
         */
        private final long mtime;

        /**
         * 读取起始偏移量（行号或字节偏移，视具体实现而定，此处通常为行号索引）。
         */
        private final int offset;

        /**
         * 读取限制数量（例如最大行数），null 表示无限制。
         */
        private final Integer limit;

        /**
         * 文件内容的 SHA-256 哈希值，用于检测内容是否真正发生变化。
         */
        private final String contentHash;

        /**
         * 是否允许用于去重判断。
         * 如果为 false，通常表示该状态是由写入操作触发的，不应作为未变更的依据。
         */
        private final boolean canDedup;

        /**
         * 构造一个新的读取状态。
         *
         * @param mtime       文件最后修改时间戳
         * @param offset      读取起始偏移量
         * @param limit       读取限制数量
         * @param contentHash 文件内容哈希
         * @param canDedup    是否允许去重
         */
        public ReadState(long mtime, int offset, Integer limit, String contentHash, boolean canDedup) {
            this.mtime = mtime;
            this.offset = offset;
            this.limit = limit;
            this.contentHash = contentHash;
            this.canDedup = canDedup;
        }

        /**
         * 获取文件最后修改时间戳。
         *
         * @return 时间戳
         */
        public long getMtime() {
            return mtime;
        }

        /**
         * 获取读取起始偏移量。
         *
         * @return 偏移量
         */
        public int getOffset() {
            return offset;
        }

        /**
         * 获取读取限制数量。
         *
         * @return 限制数量，可能为 null
         */
        public Integer getLimit() {
            return limit;
        }

        /**
         * 获取文件内容哈希。
         *
         * @return SHA-256 哈希字符串
         */
        public String getContentHash() {
            return contentHash;
        }

        /**
         * 检查是否允许用于去重判断。
         *
         * @return 如果允许去重返回 true，否则返回 false
         */
        public boolean isCanDedup() {
            return canDedup;
        }
    }

    /**
     * 标准化文件路径。
     * 将路径转换为绝对路径并解析其中的符号链接和冗余名称元素（如 "." 和 ".."）。
     *
     * @param path 原始路径
     * @return 标准化后的路径字符串
     */
    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * 计算文件的 SHA-256 哈希值。
     *
     * @param path 文件路径
     * @return 十六进制格式的哈希字符串，如果发生错误则返回 null
     */
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

    /**
     * 记录一次文件读取操作的状态。
     * 此方法通常在成功读取文件后调用，以便后续编辑操作进行校验。
     *
     * @param path   被读取的文件路径
     * @param offset 读取的起始偏移量
     * @param limit  读取的限制数量
     */
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
            // 忽略 IO 异常，静默失败
        }
    }

    /**
     * 记录一次文件写入操作的状态。
     * 写入后，之前的读取状态失效，标记为不可去重，并更新最新的修改时间和哈希。
     *
     * @param path 被写入的文件路径
     */
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
            // 如果无法获取写入后的状态，则移除该文件的记录，强制下次重新读取
            STATE.remove(normalize(path));
        }
    }

    /**
     * 检查文件自上次读取后是否发生变更。
     * <p>
     * 对应 Python: check_read(path)
     *
     * @param path 要检查的文件路径
     * @return 如果文件未变更或状态一致，返回 null；
     *         如果文件未读过，返回警告信息提示先读取；
     *         如果文件已被修改，返回警告信息提示重新读取。
     */
    public static String checkRead(Path path) {
        Path p = path.toAbsolutePath().normalize();
        ReadState entry = STATE.get(normalize(p));
        if (entry == null) {
            return "Warning: file has not been read yet. Read it first to verify content before editing.";
        }

        try {
            long currentMtime = Files.getLastModifiedTime(p).toMillis();
            // 如果修改时间发生变化，需要进一步检查内容哈希
            if (currentMtime != entry.getMtime()) {
                String currentHash = hashFile(p);
                // 如果哈希值相同，说明只是时间戳变化（如 touch 操作），内容未变，更新状态并允许继续
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
                // 内容确实发生了变化
                return "Warning: file has been modified since last read. Re-read to verify content before editing.";
            }
        } catch (IOException ignored) {
            // 发生 IO 错误时，保守起见返回 null，允许继续（或者可以根据策略返回警告）
            return null;
        }

        // 修改时间未变，认为文件未变更
        return null;
    }

    /**
     * 判断文件自上次指定条件的读取后是否保持不变。
     * <p>
     * 用于去重逻辑，只有当偏移量、限制条件一致且文件未修改时才返回 true。
     * <p>
     * 对应 Python: is_unchanged(path, offset, limit)
     *
     * @param path   文件路径
     * @param offset 读取起始偏移量
     * @param limit  读取限制数量
     * @return 如果文件状态与指定条件匹配且未变更，返回 true；否则返回 false
     */
    public static boolean isUnchanged(Path path, int offset, Integer limit) {
        Path p = path.toAbsolutePath().normalize();
        ReadState entry = STATE.get(normalize(p));
        if (entry == null) {
            return false;
        }
        // 如果该状态标记为不可去重（例如由写入操作产生），则直接返回 false
        if (!entry.isCanDedup()) {
            return false;
        }
        // 检查偏移量是否一致
        if (entry.getOffset() != offset) {
            return false;
        }
        // 检查限制数量是否一致（处理 null 情况）
        if (!equalsNullable(entry.getLimit(), limit)) {
            return false;
        }

        try {
            long currentMtime = Files.getLastModifiedTime(p).toMillis();
            // 比较当前修改时间与记录的修改时间
            return currentMtime == entry.getMtime();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 清除所有记录的文件读取状态。
     * 通常在重置上下文或会话结束时调用。
     */
    public static void clear() {
        STATE.clear();
    }

    /**
     * 安全地比较两个对象是否相等，支持 null 值。
     *
     * @param a 第一个对象
     * @param b 第二个对象
     * @return 如果两者都为 null 或两者相等，则返回 true；否则返回 false
     */
    private static boolean equalsNullable(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}