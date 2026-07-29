package ricbot.domain.task;

import ricbot.domain.agent.graph.enump.GraphRuntimeEventType;
import ricbot.domain.agent.graph.interfacep.GraphRuntimeStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Applies worker patches; the unified event stream is the patch ledger. */
public final class PatchLedgerService {
    private final GraphRuntimeStore graphStore;

    public PatchLedgerService(Path workspace, GraphRuntimeStore graphStore) {
        if (workspace == null) throw new IllegalArgumentException("workspace is required");
        this.graphStore = java.util.Objects.requireNonNull(graphStore, "graphStore");
    }

    public List<PatchApplyResult> applyOrdered(String parentRunId, Path integrationWorktree,
                                               List<TaskResult> results) {
        List<PatchApplyResult> applied = new ArrayList<>();
        for (TaskResult result : results.stream().sorted(Comparator.comparingInt(TaskResult::planOrder)
                .thenComparing(TaskResult::taskId)).toList()) {
            PatchApplyResult outcome = apply(parentRunId, integrationWorktree, result);
            applied.add(outcome);
            if (outcome.status() == PatchApplyResult.Status.CONFLICT) break;
        }
        return List.copyOf(applied);
    }

    public PatchApplyResult apply(String parentRunId, Path integrationWorktree, TaskResult result) {
        String patch = result.patch();
        if (patch == null || patch.isBlank())
            return new PatchApplyResult(result.taskId(), "", PatchApplyResult.Status.EMPTY, "");
        String digest = digest(patch);
        boolean committed = graphStore.events(parentRunId).stream()
                .filter(event -> event.type() == GraphRuntimeEventType.PATCH_APPLIED)
                .anyMatch(event -> digest.equals(String.valueOf(event.data().get("digest"))));
        if (committed) return new PatchApplyResult(result.taskId(), digest,
                PatchApplyResult.Status.ALREADY_APPLIED, "patch digest already committed");

        // Covers a crash after git apply returned but before the event commit.
        CommandResult reverse = run(integrationWorktree, patch, "git", "apply", "--reverse", "--check", "-");
        if (reverse.exitCode() == 0) {
            graphStore.append(parentRunId, 0, GraphRuntimeEventType.PATCH_APPLIED,
                    Map.of("taskId", result.taskId(), "digest", digest, "recovered", true), "patch:" + digest);
            return new PatchApplyResult(result.taskId(), digest, PatchApplyResult.Status.ALREADY_APPLIED,
                    "recovered applied patch from worktree state");
        }
        CommandResult check = run(integrationWorktree, patch, "git", "apply", "--check", "-");
        if (check.exitCode() != 0) return new PatchApplyResult(result.taskId(), digest,
                PatchApplyResult.Status.CONFLICT, bounded(check.output()));
        CommandResult apply = run(integrationWorktree, patch, "git", "apply", "-");
        if (apply.exitCode() != 0) return new PatchApplyResult(result.taskId(), digest,
                PatchApplyResult.Status.CONFLICT, bounded(apply.output()));
        graphStore.append(parentRunId, 0, GraphRuntimeEventType.PATCH_APPLIED,
                Map.of("taskId", result.taskId(), "digest", digest), "patch:" + digest);
        return new PatchApplyResult(result.taskId(), digest, PatchApplyResult.Status.APPLIED, "");
    }

    private static CommandResult run(Path directory, String input, String... command) {
        try {
            Process process = new ProcessBuilder(List.of(command)).directory(directory.toFile()).start();
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            return new CommandResult(code, stderr.isBlank() ? stdout : stderr);
        } catch (IOException e) { throw new IllegalStateException("cannot execute git apply", e); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("git apply interrupted", e);
        }
    }
    private static String bounded(String value) {
        String clean = value != null ? value.trim() : "";
        return clean.length() <= 4000 ? clean : clean.substring(0, 4000);
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private record CommandResult(int exitCode, String output) { }
}
