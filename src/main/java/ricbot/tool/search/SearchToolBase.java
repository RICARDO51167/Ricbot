package ricbot.tool.search;

import ricbot.tool.filesystem.FsTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 搜索工具基类。
 */
abstract class SearchToolBase extends FsTool {

    protected static final Set<String> IGNORE_DIRS = Set.of(
            ".git", "node_modules", "__pycache__", ".venv", "venv",
            "dist", "build", ".tox", ".mypy_cache", ".pytest_cache", ".ruff_cache"
    );

    protected SearchToolBase(Path workspace, Path allowedDir, List<Path> extraAllowedDirs) {
        super(workspace, allowedDir, extraAllowedDirs);
    }

    protected Iterable<Path> iterFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            out.add(root);
            return out;
        }
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !containsIgnoredDir(p))
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    protected Iterable<Path> iterEntries(Path root, boolean includeFiles, boolean includeDirs) throws IOException {
        List<Path> out = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            if (includeFiles) out.add(root);
            return out;
        }
        try (var stream = Files.walk(root)) {
            stream.filter(p -> !p.equals(root))
                    .filter(p -> !containsIgnoredDir(p))
                    .sorted()
                    .forEach(p -> {
                        if (Files.isRegularFile(p) && includeFiles) out.add(p);
                        if (Files.isDirectory(p) && includeDirs) out.add(p);
                    });
        }
        return out;
    }

    protected boolean containsIgnoredDir(Path p) {
        for (Path part : p) {
            if (IGNORE_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    protected String displayPath(Path target, Path root) {
        try {
            if (workspace != null) {
                return workspace.relativize(target).toString().replace("\\", "/");
            }
        } catch (Exception ignored) {
        }
        return root.relativize(target).toString().replace("\\", "/");
    }

    protected static boolean isBinary(byte[] raw) {
        for (byte b : raw) {
            if (b == 0) return true;
        }
        int sample = Math.min(raw.length, 4096);
        if (sample == 0) return false;
        int nonText = 0;
        for (int i = 0; i < sample; i++) {
            int v = raw[i] & 0xff;
            if (v < 9 || (v > 13 && v < 32)) nonText++;
        }
        return ((double) nonText / sample) > 0.2;
    }
}
