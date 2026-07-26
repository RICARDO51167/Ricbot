package ricbot.infra.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.domain.agent.SideEffectRecord;
import ricbot.domain.agent.graph.GraphExecutionState;
import ricbot.domain.runtime.LegacyRuntimeSnapshot;
import ricbot.domain.runtime.RuntimeDigest;
import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.task.TaskDelivery;
import ricbot.domain.task.TaskRecord;
import ricbot.domain.task.TaskResult;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Idempotent one-shot import followed by a recoverable archive move. */
public final class LegacyRuntimeMigrator {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final List<String> V1_DIRECTORIES = List.of(
            "run-journal", "run-checkpoints", "worker-runtime", "team", "team-runtime");

    private final Path workspace;
    private final Path ricbot;
    private final SqliteRuntimeStore store;

    public LegacyRuntimeMigrator(Path workspace, SqliteRuntimeStore store) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.ricbot = this.workspace.resolve(".ricbot");
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    public Result migrateIfNeeded() {
        List<Path> sources = sourceDirectories();
        if (sources.isEmpty()) return new Result("", false, 0, null);
        String migrationId = migrationId(sources);
        LegacyRuntimeSnapshot snapshot = snapshot(migrationId);
        boolean imported = store.importLegacy(snapshot);
        Path archive = ricbot.resolve("archive").resolve(migrationId);
        archiveSources(sources, archive);
        return new Result(migrationId, imported, snapshot.activeRuns().size() + snapshot.tasks().size()
                + snapshot.deliveries().size() + snapshot.approvals().size() + snapshot.sideEffects().size()
                + snapshot.archivedV1Records(), archive);
    }

    private LegacyRuntimeSnapshot snapshot(String migrationId) {
        Path legacy = ricbot.resolve("runtime-v2");
        List<TaskRecord> taskRecords = readNamed(legacy.resolve("tasks"), "record.json", TaskRecord.class);
        List<TaskResult> results = readNamed(legacy.resolve("tasks"), "result.json", TaskResult.class);
        return new LegacyRuntimeSnapshot(migrationId,
                readNamed(legacy.resolve("runs"), "checkpoint.json", GraphExecutionState.class), taskRecords, results,
                readAll(ricbot.resolve("runtime-v2/deliveries"), TaskDelivery.class),
                readNamed(legacy.resolve("approvals"), "request.json", ApprovalRequest.class),
                readAll(ricbot.resolve("side-effects"), SideEffectRecord.class), countV1Records());
    }

    private List<Path> sourceDirectories() {
        List<Path> result = new ArrayList<>();
        Path runtimeV2 = ricbot.resolve("runtime-v2");
        if (hasFiles(runtimeV2)) result.add(runtimeV2);
        Path sideEffects = ricbot.resolve("side-effects");
        if (hasFiles(sideEffects)) result.add(sideEffects);
        for (String name : V1_DIRECTORIES) {
            Path directory = ricbot.resolve(name);
            if (hasFiles(directory)) result.add(directory);
        }
        return List.copyOf(result);
    }

    private int countV1Records() {
        int count = 0;
        for (String name : V1_DIRECTORIES) count += countFiles(ricbot.resolve(name));
        return count;
    }

    private void archiveSources(List<Path> sources, Path archive) {
        try {
            Files.createDirectories(archive);
            for (Path source : sources) {
                if (!Files.exists(source)) continue;
                Path target = archive.resolve(source.getFileName().toString());
                if (Files.exists(target)) continue;
                try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target); }
            }
        } catch (IOException e) {
            throw new IllegalStateException("legacy state imported but archive move must be resumed: " + archive, e);
        }
    }

    private static <T> List<T> readAll(Path directory, Class<T> type) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().map(path -> read(path, type)).toList();
        } catch (IOException e) { throw new IllegalStateException("cannot scan legacy records " + directory, e); }
    }

    private static <T> List<T> readNamed(Path directory, String fileName, Class<T> type) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(path -> path.getFileName().toString().equals(fileName))
                    .sorted().map(path -> read(path, type)).toList();
        } catch (IOException e) { throw new IllegalStateException("cannot scan legacy records " + directory, e); }
    }

    private static <T> T read(Path path, Class<T> type) {
        try { return MAPPER.readValue(path.toFile(), type); }
        catch (IOException e) { throw new IllegalStateException("cannot read legacy record " + path, e); }
    }

    private static boolean hasFiles(Path path) { return countFiles(path) > 0; }
    private static int countFiles(Path path) {
        if (!Files.isDirectory(path)) return 0;
        try (Stream<Path> paths = Files.walk(path)) { return Math.toIntExact(paths.filter(Files::isRegularFile).count()); }
        catch (IOException e) { throw new IllegalStateException("cannot inspect legacy directory " + path, e); }
    }

    private static String migrationId(List<Path> sources) {
        List<String> facts = new ArrayList<>();
        for (Path source : sources) {
            try (Stream<Path> paths = Files.walk(source)) {
                paths.filter(Files::isRegularFile).sorted().forEach(path -> {
                    try { facts.add(source.relativize(path) + ":" + Files.size(path) + ":" + Files.getLastModifiedTime(path)); }
                    catch (IOException e) { throw new IllegalStateException(e); }
                });
            } catch (IOException e) { throw new IllegalStateException("cannot identify legacy migration", e); }
        }
        return "legacy-" + RuntimeDigest.sha256(facts).substring(0, 16);
    }

    public record Result(String migrationId, boolean imported, int records, Path archive) { }
}
