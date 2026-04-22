package ricbot.tool.filesystem;

import ricbot.infra.fs.FsPathUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 文件工具公共辅助类，提供路径解析、权限校验、文件读写、二进制检测、目录列表等通用功能。
 */
public final class FileToolSupport {

    // 私有构造函数，防止外部实例化此类
    private FileToolSupport() {
        // 私有构造函数，防止实例化
    }

    /**
     * 解析给定路径为绝对规范路径。
     * 如果输入路径是相对路径，则基于工作空间路径进行解析；否则直接转换为绝对规范路径。
     *
     * @param workspace 工作空间根路径
     * @param path      待解析的路径字符串
     * @return 绝对且规范化的 Path 对象
     */
    public static Path resolvePath(Path workspace, String path) {
        // 将输入的路径字符串转换为 Path 对象
        Path p = FsPathUtils.expandUser(path);
        // 如果路径不是绝对路径
        if (!p.isAbsolute()) {
            // 基于工作空间路径解析该相对路径
            p = workspace.resolve(path);
        }
        // 返回绝对路径并规范化（去除 . 和 .. 等冗余部分）
        return p.toAbsolutePath().normalize();
    }

    /**
     * 检查指定路径是否在允许的主目录或额外允许目录列表中。
     * 如果路径不在任何允许的目录下，则抛出 IllegalArgumentException。
     *
     * @param path             待检查的路径
     * @param allowedDir       主允许目录
     * @param extraAllowedDirs 额外允许目录列表
     * @throws IllegalArgumentException 如果路径不在允许范围内
     */
    public static void ensureAllowed(Path path, Path allowedDir, List<Path> extraAllowedDirs) {
        FsPathUtils.validateAccess(path, allowedDir, extraAllowedDirs, true);
    }

    /**
     * 校验写入路径是否在允许范围内。
     * 允许最终文件不存在，但会解析父目录的真实路径，拒绝借助符号链接越界写入。
     */
    public static void ensureAllowedForWrite(Path path, Path allowedDir, List<Path> extraAllowedDirs) {
        FsPathUtils.validateAccess(path, allowedDir, extraAllowedDirs, true);
    }

    /**
     * 以 UTF-8 编码读取文件内容为字符串。
     *
     * @param path 文件路径
     * @return 文件内容字符串
     * @throws IOException 如果发生 I/O 错误
     */
    public static String readText(Path path) throws IOException {
        // 使用 UTF-8 编码读取文件所有内容并返回字符串
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 以 UTF-8 编码将字符串写入文件。如果父目录不存在，会自动创建。
     *
     * @param path    目标文件路径
     * @param content 要写入的内容，若为 null 则写入空字符串
     * @throws IOException 如果发生 I/O 错误
     */
    public static void writeText(Path path, String content) throws IOException {
        // 获取文件的父目录路径
        Path parent = path.getParent();
        // 如果父目录存在且不为 null
        if (parent != null) {
            // 创建所有必需的父目录，如果它们尚不存在
            Files.createDirectories(parent);
        }
        // 将内容写入文件
        Files.writeString(
                path,
                // 如果内容为 null，则写入空字符串，否则写入原内容
                content != null ? content : "",
                // 指定字符集为 UTF-8
                StandardCharsets.UTF_8,
                // 如果文件不存在则创建
                StandardOpenOption.CREATE,
                // 如果文件已存在则截断（清空）原有内容
                StandardOpenOption.TRUNCATE_EXISTING,
                // 以写入模式打开
                StandardOpenOption.WRITE
        );
    }

    /**
     * 判断文件是否为二进制文件。
     * 通过读取文件前 1024 字节，检查是否包含 null 字节（0x00）来判断。
     *
     * @param path 文件路径
     * @return 如果是二进制文件返回 true，否则返回 false
     */
    public static boolean isBinary(Path path) {
        try {
            // 读取文件的所有字节
            byte[] bytes = Files.readAllBytes(path);
            // 确定采样长度，最多取前 1024 字节
            int sample = Math.min(bytes.length, 1024);
            // 遍历采样范围内的字节
            for (int i = 0; i < sample; i++) {
                byte b = bytes[i];
                // 如果发现 null 字节 (0x00)，则认为是二进制文件
                if (b == 0) {
                    return true;
                }
            }
            // 未发现 null 字节，认为是文本文件
            return false;
        } catch (Exception e) {
            // 如果发生异常（如文件无法读取），默认返回 false，视为非二进制或处理失败
            return false;
        }
    }

    /**
     * 将文本内容按行切片，并带上行号。
     * 行号从 1 开始，与 Python 版本保持一致。
     *
     * @param content 原始文本内容
     * @param offset  起始行号（从 1 开始）
     * @param limit   最大行数，若为 null 或 <=0 则读取到末尾
     * @return 带行号的切片文本
     */
    public static String sliceLines(String content, int offset, Integer limit) {
        // 如果内容为 null，返回空字符串
        if (content == null) {
            return "";
        }

        // 使用正则表达式 \\R 匹配任意换行符序列，分割字符串为行数组
        // -1 参数确保尾随的空字符串也会被保留
        String[] lines = content.split("\\R", -1);

        // 计算起始行索引，确保至少从第 1 行开始
        int start = Math.max(1, offset);
        // 转换为数组索引（从 0 开始）
        int startIndex = start - 1;
        // 如果起始索引超出数组范围，返回空字符串
        if (startIndex >= lines.length) {
            return "";
        }

        // 计算结束索引（不包含）
        int endExclusive;
        // 如果 limit 为 null 或小于等于 0，则读取到末尾
        if (limit == null || limit <= 0) {
            endExclusive = lines.length;
        } else {
            // 否则，计算起始索引 + 限制行数，并确保不超过数组总长度
            endExclusive = Math.min(lines.length, startIndex + limit);
        }

        // 创建 StringBuilder 用于构建结果字符串
        StringBuilder sb = new StringBuilder();
        // 遍历选定的行范围
        for (int i = startIndex; i < endExclusive; i++) {
            // 追加行号（i+1 因为行号从 1 开始）和内容
            sb.append(i + 1).append(": ").append(lines[i]);
            // 如果不是最后一行，追加换行符
            if (i < endExclusive - 1) {
                sb.append("\n");
            }
        }
        // 返回构建好的字符串
        return sb.toString();
    }

    /**
     * 列出目录下的所有文件和子目录，并按类型和名称排序。
     * 目录排在前面，文件排在后面；同类中按文件名不区分大小写排序。
     *
     * @param dir 目标目录路径
     * @return 排序后的路径列表
     * @throws IOException 如果发生 I/O 错误
     */
    public static List<Path> listDir(Path dir) throws IOException {
        // 使用 try-with-resources 确保流被正确关闭
        try (var stream = Files.list(dir)) {
            // 对目录项进行排序并收集为列表
            return stream
                    // 首先按是否是目录排序：!Files.isDirectory(p) 使得目录(false, 0) 排在文件(true, 1) 前面
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            // 其次按文件名的小写形式排序，实现不区分大小写的字母顺序
                            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    // 将流转换为不可变列表
                    .toList();
        }
    }

    /**
     * 格式化目录列表为可读字符串，包含每个条目是目录还是文件，以及文件大小（如果是文件）。
     *
     * @param dir   目录路径
     * @param items 目录项列表
     * @return 格式化后的字符串
     */
    public static String formatDirList(Path dir, List<Path> items) {
        // 创建 StringBuilder 用于构建输出字符串
        StringBuilder sb = new StringBuilder();
        // 追加目录标题，包含绝对规范化路径
        sb.append("目录: ").append(dir.toAbsolutePath().normalize()).append("\n\n");

        // 遍历目录中的每一项
        for (Path item : items) {
            // 判断当前项是否是目录
            boolean isDir = Files.isDirectory(item);
            // 根据类型追加标签："[目录]  " 或 "[文件] "
            sb.append(isDir ? "[目录]  " : "[文件] ");
            // 追加文件名
            sb.append(item.getFileName());
            // 如果是文件，尝试追加文件大小
            if (!isDir) {
                try {
                    // 获取文件大小并追加到字符串中
                    sb.append(" (").append(Files.size(item)).append(" 字节)");
                } catch (IOException ignored) {
                    // 如果获取文件大小失败，忽略异常，不显示大小
                }
            }
            // 每项结束后追加换行符
            sb.append("\n");
        }

        // 返回去除首尾空白字符后的字符串
        return sb.toString().trim();
    }

    /**
     * 规范化额外允许目录列表，将其转换为绝对规范路径。
     *
     * @param extraAllowedDirs 原始额外允许目录列表
     * @return 规范化后的路径列表，若输入为 null 则返回空列表
     */
    public static List<Path> normalizeExtraDirs(List<Path> extraAllowedDirs) {
        // 如果输入列表为 null，返回一个空的不可变列表
        if (extraAllowedDirs == null) {
            return List.of();
        }
        // 创建一个新的可变列表用于存储规范化后的路径
        List<Path> result = new ArrayList<>();
        // 遍历输入列表中的每一个路径
        for (Path p : extraAllowedDirs) {
            // 如果路径不为 null
            if (p != null) {
                // 将其转换为绝对规范化路径并添加到结果列表中
                result.add(p.toAbsolutePath().normalize());
            }
        }
        // 返回规范化后的路径列表
        return result;
    }
}
