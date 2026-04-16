package ricbot.tool.filesystem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 文件工具公共辅助类
 */
public final class FileToolSupport {

    private FileToolSupport() {
    }

    public static Path resolvePath(Path workspace, String path) {
        Path p = Path.of(path);
        if (!p.isAbsolute()) {
            p = workspace.resolve(path);
        }
        return p.toAbsolutePath().normalize();
    }

    public static void ensureAllowed(Path path, Path allowedDir, List<Path> extraAllowedDirs) {
        Path normalized = path.toAbsolutePath().normalize();

        if (allowedDir == null && (extraAllowedDirs == null || extraAllowedDirs.isEmpty())) {
            return;
        }

        if (allowedDir != null) {
            Path base = allowedDir.toAbsolutePath().normalize();
            if (normalized.equals(base) || normalized.startsWith(base)) {
                return;
            }
        }

        if (extraAllowedDirs != null) {
            for (Path extra : extraAllowedDirs) {
                Path base = extra.toAbsolutePath().normalize();
                if (normalized.equals(base) || normalized.startsWith(base)) {
                    return;
                }
            }
        }

        throw new IllegalArgumentException("路径超出允许的目录范围: " + normalized);
    }

    public static String readText(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public static void writeText(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                path,
                content != null ? content : "",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    public static boolean isBinary(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            int sample = Math.min(bytes.length, 1024);
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

    public static List<Path> listDir(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
    }

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