package ricbot.integration.api.console;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public class ConsoleWorkspaceService {
    public static final int DEFAULT_MAX_FILE_BYTES = 256 * 1024;

    private static final int BINARY_SAMPLE_BYTES = 8192;
    private static final Set<String> IGNORED_NAMES = Set.of(
            ".git",
            ".hg",
            ".svn",
            ".workspaces",
            "target",
            "node_modules",
            "dist",
            "build",
            ".idea"
    );

    private final Path workspace;
    private final int maxFileBytes;

    public ConsoleWorkspaceService(Path workspace) {
        this(workspace, DEFAULT_MAX_FILE_BYTES);
    }

    public ConsoleWorkspaceService(Path workspace, int maxFileBytes) {
        this.workspace = workspace != null ? workspace.toAbsolutePath().normalize() : null;
        this.maxFileBytes = maxFileBytes > 0 ? maxFileBytes : DEFAULT_MAX_FILE_BYTES;
    }

    public WorkspaceTreeResponse tree(String root, int depth, boolean includeHidden) {
        Path base = resolveWorkspaceRoot();
        Path target = resolvePath(root);
        if (!Files.exists(target)) {
            throw new ConsoleWorkspaceException("path_not_found", "workspace path not found: " + normalizeRelativePath(root), 404);
        }
        if (!Files.isDirectory(target)) {
            throw new ConsoleWorkspaceException("path_not_directory", "workspace path is not a directory: " + relativePath(target), 400);
        }
        if (isBlocked(target, includeHidden)) {
            throw new ConsoleWorkspaceException("path_not_previewable", "workspace path is not previewable: " + relativePath(target), 403);
        }

        int safeDepth = Math.max(0, Math.min(depth, 8));
        return new WorkspaceTreeResponse(
                base.toString(),
                relativePath(target),
                children(target, safeDepth, includeHidden)
        );
    }

    public WorkspaceFileContent fileContent(String path) {
        Path target = resolvePath(path);
        if (!Files.exists(target)) {
            throw new ConsoleWorkspaceException("file_not_found", "workspace file not found: " + normalizeRelativePath(path), 404);
        }
        if (!Files.isRegularFile(target) || isBlocked(target, false)) {
            throw new ConsoleWorkspaceException("path_not_previewable", "workspace file is not previewable: " + relativePath(target), 403);
        }

        try {
            long size = Files.size(target);
            String modifiedAt = modifiedAt(target);
            boolean binary = isBinary(target, size);
            if (binary) {
                return new WorkspaceFileContent(relativePath(target), language(target), size, modifiedAt, true, false, "");
            }
            if (size > maxFileBytes) {
                return new WorkspaceFileContent(relativePath(target), language(target), size, modifiedAt, false, true, "");
            }
            return new WorkspaceFileContent(
                    relativePath(target),
                    language(target),
                    size,
                    modifiedAt,
                    false,
                    false,
                    Files.readString(target, StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            throw new ConsoleWorkspaceException("file_read_failed", "workspace file read failed: " + relativePath(target), 500);
        }
    }

    private List<WorkspaceTreeNode> children(Path directory, int depth, boolean includeHidden) {
        if (depth <= 0) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(path -> !isBlocked(path, includeHidden))
                    .sorted(Comparator
                            .comparing((Path path) -> !Files.isDirectory(path))
                            .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(path -> node(path, depth - 1, includeHidden))
                    .toList();
        } catch (IOException e) {
            throw new ConsoleWorkspaceException("tree_read_failed", "workspace tree read failed: " + relativePath(directory), 500);
        }
    }

    private WorkspaceTreeNode node(Path path, int childDepth, boolean includeHidden) {
        boolean directory = Files.isDirectory(path);
        return new WorkspaceTreeNode(
                path.getFileName().toString(),
                relativePath(path),
                directory ? WorkspaceNodeType.DIRECTORY : WorkspaceNodeType.FILE,
                directory ? 0 : fileSize(path),
                directory ? "" : modifiedAt(path),
                directory ? children(path, childDepth, includeHidden) : List.of()
        );
    }

    private Path resolveWorkspaceRoot() {
        if (workspace == null) {
            throw new ConsoleWorkspaceException("workspace_not_configured", "workspace_not_configured", 400);
        }
        return workspace;
    }

    private Path resolvePath(String path) {
        Path base = resolveWorkspaceRoot();
        String clean = normalizeRelativePath(path);
        try {
            Path relative = clean.isBlank() ? Path.of("") : Path.of(clean);
            Path target = base.resolve(relative).toAbsolutePath().normalize();
            if (!target.startsWith(base)) {
                throw new ConsoleWorkspaceException("path_outside_workspace", "path is outside workspace", 403);
            }
            return target;
        } catch (InvalidPathException e) {
            throw new ConsoleWorkspaceException("invalid_path", "invalid workspace path", 400);
        }
    }

    private boolean isBlocked(Path path, boolean includeHidden) {
        Path relative = resolveWorkspaceRoot().relativize(path.toAbsolutePath().normalize());
        for (Path part : relative) {
            String name = part.toString();
            if (IGNORED_NAMES.contains(name)) {
                return true;
            }
            if (!includeHidden && name.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private String relativePath(Path path) {
        Path relative = resolveWorkspaceRoot().relativize(path.toAbsolutePath().normalize());
        return relative.toString().replace('\\', '/');
    }

    private static String normalizeRelativePath(String path) {
        return path != null ? path.trim().replace('\\', '/') : "";
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String modifiedAt(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time.toInstant().toString();
        } catch (IOException e) {
            return Instant.EPOCH.toString();
        }
    }

    private static boolean isBinary(Path path, long size) throws IOException {
        int length = (int) Math.min(size, BINARY_SAMPLE_BYTES);
        if (length <= 0) {
            return false;
        }
        byte[] sample = new byte[length];
        try (var input = Files.newInputStream(path)) {
            int read = input.read(sample);
            if (read <= 0) {
                return false;
            }
            for (int i = 0; i < read; i++) {
                if (sample[i] == 0) {
                    return true;
                }
            }
            try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(sample, 0, read));
                return false;
            } catch (CharacterCodingException e) {
                return true;
            }
        }
    }

    private static String language(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1) : "";
        return switch (extension) {
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "js", "mjs", "cjs" -> "javascript";
            case "ts" -> "typescript";
            case "vue" -> "vue";
            case "json" -> "json";
            case "md", "markdown" -> "markdown";
            case "xml", "pom" -> "xml";
            case "yml", "yaml" -> "yaml";
            case "html", "htm" -> "html";
            case "css" -> "css";
            case "scss" -> "scss";
            case "sh", "bash", "zsh" -> "shell";
            case "py" -> "python";
            case "sql" -> "sql";
            case "txt", "log" -> "text";
            default -> extension.isBlank() ? "text" : extension;
        };
    }
}
