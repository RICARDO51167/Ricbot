package ricbot.domain.eval;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class WorkspaceRestorePoint implements AutoCloseable {
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git",
            ".idea",
            ".rag",
            ".ricbot",
            "target",
            "sessions",
            "legacy_sessions",
            "memory"
    );
    private static final Set<String> SKIP_ROOT_FILES = Set.of("notes/index.json");

    private final Path workspace;
    private final Path backup;
    private final List<Path> excludedRoots;
    private final Set<String> backedFiles = new HashSet<>();

    private WorkspaceRestorePoint(Path workspace, Path backup, List<Path> excludedRoots) {
        this.workspace = workspace;
        this.backup = backup;
        this.excludedRoots = excludedRoots != null ? excludedRoots : List.of();
    }

    static WorkspaceRestorePoint create(Path workspace, List<Path> excludedRoots) throws Exception {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace is not configured");
        }
        Path root = workspace.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path backup = Files.createTempDirectory("ricbot-eval-workspace-restore-");
        WorkspaceRestorePoint point = new WorkspaceRestorePoint(root, backup, normalizeExcludedRoots(excludedRoots));
        point.copyWorkspaceToBackup();
        return point;
    }

    private static List<Path> normalizeExcludedRoots(List<Path> roots) {
        List<Path> out = new ArrayList<>();
        for (Path root : roots != null ? roots : List.<Path>of()) {
            if (root != null) {
                out.add(root.toAbsolutePath().normalize());
            }
        }
        return out;
    }

    List<String> restore() {
        List<String> errors = new ArrayList<>();
        try {
            deleteAddedWorkspaceFiles(errors);
            copyBackupToWorkspace(errors);
            pruneEmptyWorkspaceDirectories(errors);
        } finally {
            try {
                deleteTree(backup);
            } catch (Exception e) {
                errors.add("failed to remove restore backup: " + e.getMessage());
            }
        }
        return errors;
    }

    @Override
    public void close() throws Exception {
        deleteTree(backup);
    }

    private void copyWorkspaceToBackup() throws Exception {
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(workspace) && shouldSkipDir(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = workspace.relativize(file);
                if (SKIP_ROOT_FILES.contains(toKey(relative))) {
                    return FileVisitResult.CONTINUE;
                }
                backedFiles.add(toKey(relative));
                Path target = backup.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteAddedWorkspaceFiles(List<String> errors) {
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(workspace) && shouldSkipDir(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String key = toKey(workspace.relativize(file));
                    if (SKIP_ROOT_FILES.contains(key)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!backedFiles.contains(key)) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (Exception e) {
                            errors.add("failed to delete added file " + key + ": " + e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            errors.add("failed to scan workspace for added files: " + e.getMessage());
        }
    }

    private void copyBackupToWorkspace(List<String> errors) {
        try {
            Files.walkFileTree(backup, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = backup.relativize(file);
                    Path target = workspace.resolve(relative);
                    try {
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        errors.add("failed to restore file " + toKey(relative) + ": " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            errors.add("failed to scan restore backup: " + e.getMessage());
        }
    }

    private void pruneEmptyWorkspaceDirectories(List<String> errors) {
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(workspace) && shouldSkipDir(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) {
                    if (dir.equals(workspace)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        if (isEmptyDirectory(dir)) {
                            Files.deleteIfExists(dir);
                        }
                    } catch (Exception e) {
                        errors.add("failed to prune directory " + toKey(workspace.relativize(dir)) + ": " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            errors.add("failed to prune workspace directories: " + e.getMessage());
        }
    }

    private static boolean isEmptyDirectory(Path dir) throws java.io.IOException {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }

    private boolean shouldSkipDir(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        for (Path excludedRoot : excludedRoots) {
            if (normalized.startsWith(excludedRoot)) {
                return true;
            }
        }
        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
        return SKIP_DIRS.contains(name);
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) throws java.io.IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String toKey(Path relative) {
        return relative.toString().replace('\\', '/');
    }
}
