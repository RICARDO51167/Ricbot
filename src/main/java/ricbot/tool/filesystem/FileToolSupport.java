package ricbot.tool.filesystem;

import ricbot.infra.fs.FsPathUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
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
    public static final long MAX_TEXT_FILE_BYTES = 2L * 1024L * 1024L;
    private static final int BINARY_SAMPLE_BYTES = 8192;

    private FileToolSupport() {
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
        Path p = FsPathUtils.expandUser(path);
        if (!p.isAbsolute()) {
            p = workspace.resolve(path);
        }
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
        long size = Files.size(path);
        if (size > MAX_TEXT_FILE_BYTES) {
            throw new IOException("文件超过文本工具大小限制：" + MAX_TEXT_FILE_BYTES + " bytes");
        }
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
        String safeContent = content != null ? content : "";
        long bytes = safeContent.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT_FILE_BYTES) {
            throw new IOException("写入内容超过文本工具大小限制：" + MAX_TEXT_FILE_BYTES + " bytes");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                path,
                safeContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
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
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(BINARY_SAMPLE_BYTES);
            int sample = Math.min(bytes.length, BINARY_SAMPLE_BYTES);
            for (int i = 0; i < sample; i++) {
                byte b = bytes[i];
                if (b == 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
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
        if (content == null) {
            return "";
        }

        String[] lines = content.split("\\R", -1);

        int start = Math.max(1, offset);
        int startIndex = start - 1;
        if (startIndex >= lines.length) {
            return "";
        }

        int endExclusive;
        if (limit == null || limit <= 0) {
            endExclusive = lines.length;
        } else {
            endExclusive = Math.min(lines.length, startIndex + limit);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < endExclusive; i++) {
            sb.append(i + 1).append(": ").append(lines[i]);
            if (i < endExclusive - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public static String sliceLines(Path path, int offset, Integer limit) throws IOException {
        long size = Files.size(path);
        if (size > MAX_TEXT_FILE_BYTES) {
            throw new IOException("文件超过文本工具大小限制：" + MAX_TEXT_FILE_BYTES + " bytes");
        }

        int start = Math.max(1, offset);
        int maxLines = limit == null || limit <= 0 ? Integer.MAX_VALUE : limit;
        int endExclusive = maxLines == Integer.MAX_VALUE ? Integer.MAX_VALUE : start + maxLines;

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo < start) {
                    continue;
                }
                if (lineNo >= endExclusive) {
                    break;
                }
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(lineNo).append(": ").append(line);
            }
        }
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
        try (var stream = Files.list(dir)) {
            return stream
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
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
        StringBuilder sb = new StringBuilder();
        sb.append("目录: ").append(dir.toAbsolutePath().normalize()).append("\n\n");

        for (Path item : items) {
            boolean isDir = Files.isDirectory(item);
            sb.append(isDir ? "[目录]  " : "[文件] ");
            sb.append(item.getFileName());
            if (!isDir) {
                try {
                    sb.append(" (").append(Files.size(item)).append(" 字节)");
                } catch (IOException ignored) {
                }
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * 规范化额外允许目录列表，将其转换为绝对规范路径。
     *
     * @param extraAllowedDirs 原始额外允许目录列表
     * @return 规范化后的路径列表，若输入为 null 则返回空列表
     */
    public static List<Path> normalizeExtraDirs(List<Path> extraAllowedDirs) {
        if (extraAllowedDirs == null) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        for (Path p : extraAllowedDirs) {
            if (p != null) {
                result.add(p.toAbsolutePath().normalize());
            }
        }
        return result;
    }
}
