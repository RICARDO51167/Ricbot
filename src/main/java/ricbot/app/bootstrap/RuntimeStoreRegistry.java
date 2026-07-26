package ricbot.app.bootstrap;

import ricbot.application.runtime.RuntimeLifecycleManager;
import ricbot.infra.runtime.RuntimeSchemaV2Migrator;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process composition root for the one shared schema-v2 store and lifecycle per workspace. */
public final class RuntimeStoreRegistry {
    private static final Map<Path, RuntimeProcessResources> RESOURCES = new ConcurrentHashMap<>();
    private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean();

    private RuntimeStoreRegistry() { }

    public static SqliteRuntimeStore shared(Path rawWorkspace) {
        Path workspace = rawWorkspace.toAbsolutePath().normalize();
        registerShutdownHook();
        return RESOURCES.computeIfAbsent(workspace, RuntimeStoreRegistry::open).store();
    }

    /** Acquires the process-owned runtime resources for a long-lived application component. */
    public static SqliteRuntimeStore acquire(Path rawWorkspace) {
        Path workspace = rawWorkspace.toAbsolutePath().normalize();
        SqliteRuntimeStore store = shared(workspace);
        RESOURCES.get(workspace).owners.incrementAndGet();
        return store;
    }

    /** Releases one process owner and stops all workspace background services at the last release. */
    public static void release(Path rawWorkspace) {
        Path workspace = rawWorkspace.toAbsolutePath().normalize();
        RESOURCES.computeIfPresent(workspace, (ignored, resources) -> {
            int remaining = resources.owners.updateAndGet(current -> Math.max(0, current - 1));
            if (remaining > 0) return resources;
            resources.close();
            return null;
        });
    }

    public static RuntimeLifecycleManager lifecycle(Path rawWorkspace) {
        shared(rawWorkspace);
        return RESOURCES.get(rawWorkspace.toAbsolutePath().normalize()).lifecycle();
    }

    /** Registers a runtime-owned background component for deterministic process shutdown. */
    public static void registerManaged(Path rawWorkspace, AutoCloseable closeable) {
        Path workspace = rawWorkspace.toAbsolutePath().normalize();
        shared(workspace);
        RESOURCES.get(workspace).managed.add(closeable);
    }

    public static void closeAll() {
        RESOURCES.values().forEach(RuntimeProcessResources::close);
        RESOURCES.clear();
    }

    private static RuntimeProcessResources open(Path workspace) {
        RuntimeSchemaV2Migrator.prepare(workspace);
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        RuntimeLifecycleManager lifecycle = new RuntimeLifecycleManager(store);
        lifecycle.start();
        return new RuntimeProcessResources(store, lifecycle);
    }

    private static void registerShutdownHook() {
        if (!HOOK_REGISTERED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(RuntimeStoreRegistry::closeAll,
                "ricbot-runtime-shutdown"));
    }

    private static final class RuntimeProcessResources {
        private final SqliteRuntimeStore store;
        private final RuntimeLifecycleManager lifecycle;
        private final AtomicInteger owners = new AtomicInteger();
        private final CopyOnWriteArrayList<AutoCloseable> managed = new CopyOnWriteArrayList<>();

        private RuntimeProcessResources(SqliteRuntimeStore store, RuntimeLifecycleManager lifecycle) {
            this.store = store;
            this.lifecycle = lifecycle;
        }

        private SqliteRuntimeStore store() { return store; }
        private RuntimeLifecycleManager lifecycle() { return lifecycle; }

        private void close() {
            for (AutoCloseable closeable : managed) {
                try { closeable.close(); }
                catch (Exception ignored) { /* Remaining process resources still must close. */ }
            }
            managed.clear();
            lifecycle.close();
            store.close();
        }
    }
}
