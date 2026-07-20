package ricbot.domain.eval;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorkspaceDiff {
    private static final int MAX_FILES = 5_000;
    private static final int MAX_TEXT_DIFF_BYTES = 64 * 1024;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git",
            ".idea",
            ".rag",
            ".ricbot",
            ".traces",
            "target",
            "sessions",
            "legacy_sessions",
            "memory"
    );
    private static final Set<String> SKIP_ROOT_FILES = Set.of("notes/index.json");

    private WorkspaceDiff() {
    }

    static Snapshot capture(Path workspace) {
        Path root = workspace != null ? workspace.toAbsolutePath().normalize() : null;
        Map<String, FileEntry> entries = new LinkedHashMap<>();
        if (root == null || !Files.exists(root)) {
            return new Snapshot(root, entries, false, root == null ? "workspace is null" : "workspace does not exist", false);
        }

        Counter counter = new Counter();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!dir.equals(root) && SKIP_DIRS.contains(name)) {
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
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (SKIP_ROOT_FILES.contains(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String sha256 = sha256(file);
                    entries.put(relative, new FileEntry(relative, attrs.size(), sha256, textContent(file, attrs.size())));
                    counter.files++;
                    return FileVisitResult.CONTINUE;
                }
            });
            return new Snapshot(root, entries, true, null, counter.truncated);
        } catch (Exception e) {
            return new Snapshot(root, entries, false, e.getMessage(), counter.truncated);
        }
    }

    static DiffResult diff(Snapshot before, Snapshot after) {
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> modified = new ArrayList<>();
        List<Map<String, Object>> deleted = new ArrayList<>();

        Map<String, FileEntry> beforeEntries = before != null ? before.entries() : Map.of();
        Map<String, FileEntry> afterEntries = after != null ? after.entries() : Map.of();

        for (Map.Entry<String, FileEntry> entry : afterEntries.entrySet()) {
            FileEntry beforeEntry = beforeEntries.get(entry.getKey());
            FileEntry afterEntry = entry.getValue();
            if (beforeEntry == null) {
                added.add(fileChange("added", null, afterEntry, null));
            } else if (!beforeEntry.sha256().equals(afterEntry.sha256()) || beforeEntry.size() != afterEntry.size()) {
                modified.add(fileChange("modified", beforeEntry, afterEntry, textDiff(before, after, entry.getKey())));
            }
        }

        for (Map.Entry<String, FileEntry> entry : beforeEntries.entrySet()) {
            if (!afterEntries.containsKey(entry.getKey())) {
                deleted.add(fileChange("deleted", entry.getValue(), null, null));
            }
        }

        return new DiffResult(
                snapshotSummary(before),
                snapshotSummary(after),
                added,
                modified,
                deleted
        );
    }

    private static Map<String, Object> fileChange(String type, FileEntry before, FileEntry after, String textDiff) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("path", after != null ? after.path() : before.path());
        if (before != null) {
            out.put("before_size", before.size());
            out.put("before_sha256", before.sha256());
        }
        if (after != null) {
            out.put("after_size", after.size());
            out.put("after_sha256", after.sha256());
        }
        if (textDiff != null && !textDiff.isBlank()) {
            out.put("text_diff", textDiff);
        }
        return out;
    }

    private static Map<String, Object> snapshotSummary(Snapshot snapshot) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (snapshot == null) {
            out.put("available", false);
            return out;
        }
        out.put("available", snapshot.available());
        out.put("path", snapshot.root() != null ? snapshot.root().toString() : "");
        out.put("file_count", snapshot.entries().size());
        out.put("truncated", snapshot.truncated());
        if (snapshot.reason() != null) {
            out.put("reason", snapshot.reason());
        }
        return out;
    }

    private static String textDiff(Snapshot before, Snapshot after, String relative) {
        if (before == null || after == null) {
            return null;
        }
        FileEntry beforeEntry = before.entries().get(relative);
        FileEntry afterEntry = after.entries().get(relative);
        if (beforeEntry == null || afterEntry == null) {
            return null;
        }
        String beforeText = beforeEntry.textContent();
        String afterText = afterEntry.textContent();
        if (beforeText == null || afterText == null || beforeText.equals(afterText)) {
            return null;
        }
        return simpleLineDiff(beforeText, afterText);
    }

    private static String simpleLineDiff(String before, String after) {
        String[] a = before.split("\\R", -1);
        String[] b = after.split("\\R", -1);
        int max = Math.max(a.length, b.length);
        StringBuilder sb = new StringBuilder();
        int emitted = 0;
        for (int i = 0; i < max && emitted < 80; i++) {
            String left = i < a.length ? a[i] : null;
            String right = i < b.length ? b[i] : null;
            if (java.util.Objects.equals(left, right)) {
                continue;
            }
            if (left != null) {
                sb.append("- ").append(left).append('\n');
                emitted++;
            }
            if (right != null && emitted < 80) {
                sb.append("+ ").append(right).append('\n');
                emitted++;
            }
        }
        if (max > emitted) {
            sb.append("... diff truncated\n");
        }
        return sb.toString();
    }

    private static boolean looksText(byte[] bytes) {
        for (byte b : bytes) {
            int value = b & 0xff;
            if (value == 0) {
                return false;
            }
        }
        return true;
    }

    private static String textContent(Path file, long size) {
        if (size > MAX_TEXT_DIFF_BYTES) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (!looksText(bytes)) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(Path file) throws java.io.IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record FileEntry(String path, long size, String sha256, String textContent) {
    }

    record Snapshot(Path root, Map<String, FileEntry> entries, boolean available, String reason, boolean truncated) {
    }

    record DiffResult(
            Map<String, Object> beforeSnapshot,
            Map<String, Object> afterSnapshot,
            List<Map<String, Object>> added,
            List<Map<String, Object>> modified,
            List<Map<String, Object>> deleted
    ) {
        int changeCount() {
            return added.size() + modified.size() + deleted.size();
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("before_snapshot", beforeSnapshot);
            out.put("after_snapshot", afterSnapshot);
            out.put("added", added);
            out.put("modified", modified);
            out.put("deleted", deleted);
            out.put("change_count", changeCount());
            return out;
        }
    }

    private static final class Counter {
        private int files;
        private boolean truncated;
    }
}
