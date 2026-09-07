package ricbot.tool.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.tool.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FileToolSupportTest {
    @Test void readRejectsOversizedFiles(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("large.txt"), "a".repeat((int) FileToolSupport.MAX_TEXT_FILE_BYTES + 1));
        ToolResult result = new ReadFileTool(workspace, workspace, List.of()).execute(
                new ToolInvocation("1", "read_file", Map.of("path", "large.txt")), context(workspace, Map.of()),
                ToolChunkSink.discard());
        assertInstanceOf(ToolResult.Failure.class, result);
    }

    @Test void newFileDoesNotRequireReceiptButOverwriteDoes(@TempDir Path workspace) throws Exception {
        WriteFileTool write = new WriteFileTool(workspace, workspace);
        ToolResult created = write.execute(new ToolInvocation("1", "write_file",
                Map.of("path", "notes.txt", "content", "alpha\n")), context(workspace, Map.of()), ToolChunkSink.discard());
        assertInstanceOf(ToolResult.Success.class, created);
        ToolResult denied = write.execute(new ToolInvocation("2", "write_file",
                Map.of("path", "notes.txt", "content", "beta\n")), context(workspace, Map.of()), ToolChunkSink.discard());
        assertInstanceOf(ToolResult.Failure.class, denied);
    }

    @Test void fullReadReceiptAllowsAtomicOverwrite(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "alpha\n");
        ReadFileTool read = new ReadFileTool(workspace, workspace, List.of());
        ToolResult.Success readResult = (ToolResult.Success) read.execute(new ToolInvocation("1", "read_file",
                Map.of("path", "notes.txt")), context(workspace, Map.of()), ToolChunkSink.discard());
        ToolStateMutation.RecordFileReadReceipt mutation = (ToolStateMutation.RecordFileReadReceipt) readResult.mutations().get(0);
        Map<String, Object> receipts = Map.of(mutation.key(), mutation.receipt());
        ToolResult result = new WriteFileTool(workspace, workspace).execute(new ToolInvocation("2", "write_file",
                Map.of("path", "notes.txt", "content", "beta\n")), context(workspace, receipts), ToolChunkSink.discard());
        assertInstanceOf(ToolResult.Success.class, result);
        assertEquals("beta\n", Files.readString(workspace.resolve("notes.txt")));
    }

    @Test void editRequiresUniqueMatchAndFreshReceipt(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "same same\n");
        ToolResult.Success read = (ToolResult.Success) new ReadFileTool(workspace, workspace, List.of()).execute(
                new ToolInvocation("1", "read_file", Map.of("path", "notes.txt")), context(workspace, Map.of()),
                ToolChunkSink.discard());
        var receipt = (ToolStateMutation.RecordFileReadReceipt) read.mutations().get(0);
        ToolResult duplicate = new EditFileTool(workspace, workspace).execute(new ToolInvocation("2", "edit_file",
                Map.of("path", "notes.txt", "old_text", "same", "new_text", "new", "replace_all", false)),
                context(workspace, Map.of(receipt.key(), receipt.receipt())), ToolChunkSink.discard());
        assertInstanceOf(ToolResult.Failure.class, duplicate);
    }

    @Test void compareAndWriteRejectsStaleSha(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("notes.txt"); Files.writeString(file, "one");
        String sha = FileToolSupport.sha256(file); Files.writeString(file, "two");
        assertThrows(java.util.ConcurrentModificationException.class,
                () -> FileToolSupport.compareAndWrite(file, sha, "three"));
    }

    @Test void compareAndWriteRejectsDeletedUpdateAndExistingCreate(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("notes.txt");
        Files.writeString(file, "one");
        String sha = FileToolSupport.sha256(file);
        Files.delete(file);
        assertThrows(java.util.ConcurrentModificationException.class,
                () -> FileToolSupport.compareAndWrite(file, sha, "two"));
        assertFalse(Files.exists(file));

        Files.writeString(file, "external");
        assertThrows(java.util.ConcurrentModificationException.class,
                () -> FileToolSupport.compareAndWrite(file, null, "ours"));
        assertEquals("external", Files.readString(file));
    }

    @Test void compareAndWriteEnforcesUtf8LimitBeforeChangingTarget(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("notes.txt");
        Files.writeString(file, "original");
        String sha = FileToolSupport.sha256(file);
        String allowed = "a".repeat((int) FileToolSupport.MAX_TEXT_FILE_BYTES);
        FileToolSupport.compareAndWrite(file, sha, allowed);
        assertEquals(FileToolSupport.MAX_TEXT_FILE_BYTES, Files.size(file));
        String updatedSha = FileToolSupport.sha256(file);
        assertThrows(java.io.IOException.class, () -> FileToolSupport.compareAndWrite(
                file, updatedSha, "你".repeat((int) FileToolSupport.MAX_TEXT_FILE_BYTES / 3 + 1)));
        assertEquals(updatedSha, FileToolSupport.sha256(file));
    }

    @Test void compareAndWritePreservesExecutablePermissions(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("script.sh");
        Files.writeString(file, "#!/bin/sh\nexit 0\n");
        try {
            Set<PosixFilePermission> executable = Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, executable);
            FileToolSupport.compareAndWrite(file, FileToolSupport.sha256(file), "#!/bin/sh\necho ok\n");
            assertEquals(executable, Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException ignored) {
            assertTrue(Files.exists(file));
        }
    }

    private static ToolExecutionContext context(Path workspace, Map<String, Object> receipts) {
        return new ToolExecutionContext("run", "session", "task", "activation", "workspace", workspace,
                "DEVELOPER", new ToolAuthorizationDecision(ToolAuthorizationDecision.Decision.ALLOW,
                "test", List.of(), false), Map.of(), receipts, null);
    }
}
