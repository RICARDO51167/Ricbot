package ricbot.domain.eval;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class WorkspaceSnapshot {
    private static final int MAX_FILES = 5_000;
    private static final Set<String> SKIP_DIRS = Set.of(".git", "target", ".idea", ".rag");
    private static final Set<String> SKIP_ROOT_FILES = Set.of("notes/index.json");

    private WorkspaceSnapshot() {
    }

    static Map<String, Object> capture(Path workspace) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (workspace == null) {
            out.put("available", false);
            out.put("reason", "workspace is null");
            return out;
        }
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            out.put("available", false);
            out.put("reason", "workspace does not exist");
            out.put("path", root.toString());
            return out;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Counter counter = new Counter();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!dir.equals(root) && shouldSkipDir(name, root.relativize(dir).toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return counter.truncated ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (counter.files >= MAX_FILES) {
                        counter.truncated = true;
                        return FileVisitResult.TERMINATE;
                    }
                    Path relative = root.relativize(file);
                    if (SKIP_ROOT_FILES.contains(relative.toString().replace('\\', '/'))) {
                        return FileVisitResult.CONTINUE;
                    }
                    digest.update(relative.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Long.toString(attrs.size()).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    try (InputStream in = Files.newInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            digest.update(buffer, 0, read);
                        }
                    }
                    counter.files++;
                    counter.bytes += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });

            out.put("available", true);
            out.put("path", root.toString());
            out.put("file_count", counter.files);
            out.put("total_bytes", counter.bytes);
            out.put("truncated", counter.truncated);
            out.put("sha256", HexFormat.of().formatHex(digest.digest()));
        } catch (Exception e) {
            out.put("available", false);
            out.put("path", root.toString());
            out.put("reason", e.getMessage());
        }
        return out;
    }

    private static boolean shouldSkipDir(String name, String relative) {
        if (SKIP_DIRS.contains(name)) {
            return true;
        }
        String normalized = relative.replace('\\', '/');
        return normalized.startsWith(".ricbot/evals");
    }

    private static final class Counter {
        private int files;
        private long bytes;
        private boolean truncated;
    }
}
