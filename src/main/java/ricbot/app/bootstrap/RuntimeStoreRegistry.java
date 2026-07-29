package ricbot.app.bootstrap;

import ricbot.application.runtime.RuntimeLifecycleManager;
import ricbot.infra.runtime.SqliteRuntimeStore;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作区运行时资源的进程组合根。
 * 为每个工作区管理唯一的共享 schema-v2 存储和生命周期管理器，
 * 实现引用计数、资源获取/释放以及确定的关闭流程。
 */
public final class RuntimeStoreRegistry {
    /** 映射规范化后的工作区路径到其关联的运行时资源。 */
    private static final Map<Path, RuntimeProcessResources> RESOURCES = new ConcurrentHashMap<>();
    /** 标志位，确保 JVM 关机钩子仅注册一次。 */
    private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean();

    private RuntimeStoreRegistry() { }

    /**
     * 获取或创建指定工作区的共享 {@link SqliteRuntimeStore}。
     * 首次调用时会自动注册进程关机钩子。
     *
     * @param rawWorkspace 原始工作区路径。
     * @return 共享的运行时存储实例。
     */
    public static SqliteRuntimeStore shared(Path rawWorkspace) {
        Path workspace = rawWorkspace.toAbsolutePath().normalize();
        registerShutdownHook();
        return RESOURCES.computeIfAbsent(workspace, RuntimeStoreRegistry::open).store();
    }

    /**
     * 为长期运行的应用程序组件获取进程拥有的运行时资源。
     * 增加所有者计数，防止在组件活跃期间过早清理资源。
     *
     * @param rawWorkspace 工作区路径。
     * @return 运行时存储实例。
     */
    public static SqliteRuntimeStore acquire(Path rawWorkspace) {
        Path workspace = rawWorkspace.toAbsolutePath().normalize();
        SqliteRuntimeStore store = shared(workspace);
        RESOURCES.get(workspace).owners.incrementAndGet();
        return store;
    }

    /**
     * 释放一个进程所有者。
     * 如果这是最后一个所有者，将触发该工作区所有后台服务的停止和资源关闭。
     *
     * @param rawWorkspace 工作区路径。
     */
    public static void release(Path rawWorkspace) {
        Path workspace = rawWorkspace.toAbsolutePath().normalize();
        RESOURCES.computeIfPresent(workspace, (ignored, resources) -> {
            int remaining = resources.owners.updateAndGet(current -> Math.max(0, current - 1));
            if (remaining > 0) return resources;
            resources.close();
            return null;
        });
    }

    /**
     * 获取指定工作区的生命周期管理器。
     * 在返回管理器之前，确保工作区资源已初始化。
     *
     * @param rawWorkspace 工作区路径。
     * @return 运行时生命周期管理器。
     */
    public static RuntimeLifecycleManager lifecycle(Path rawWorkspace) {
        shared(rawWorkspace);
        return RESOURCES.get(rawWorkspace.toAbsolutePath().normalize()).lifecycle();
    }

    /**
     * 注册一个由运行时管理的后台组件，以便在进程关闭时进行确定性清理。
     * 这些组件将在工作区资源被释放或 JVM 退出时被关闭。
     *
     * @param rawWorkspace 工作区路径。
     * @param closeable 需要管理的可关闭组件。
     */
    public static void registerManaged(Path rawWorkspace, AutoCloseable closeable) {
        Path workspace = rawWorkspace.toAbsolutePath().normalize();
        shared(workspace);
        RESOURCES.get(workspace).managed.add(closeable);
    }

    /**
     * 强制立即关闭所有注册的 workspace 资源。
     * 主要用于测试场景或紧急清理情况。
     */
    public static void closeAll() {
        RESOURCES.values().forEach(RuntimeProcessResources::close);
        RESOURCES.clear();
    }

    /**
     * 为特定工作区初始化运行时环境。
     * 包括存储创建以及生命周期启动。
     *
     * @param workspace 规范化的工作区路径。
     * @return 新的 {@link RuntimeProcessResources} 实例。
     */
    private static RuntimeProcessResources open(Path workspace) {
        SqliteRuntimeStore store = new SqliteRuntimeStore(workspace);
        RuntimeLifecycleManager lifecycle = new RuntimeLifecycleManager(store);
        lifecycle.start();
        return new RuntimeProcessResources(store, lifecycle);
    }

    /**
     * 注册 JVM 关机钩子，以便在进程终止时清理所有运行时资源。
     * 确保数据库连接和后台线程能够安全关闭。
     */
    private static void registerShutdownHook() {
        if (!HOOK_REGISTERED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(RuntimeStoreRegistry::closeAll,
                "ricbot-runtime-shutdown"));
    }

    /** 封装单个工作区运行时资源的内部类。 */
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

        /**
         * 执行资源清理：
         * 1. 按顺序关闭所有受管组件（忽略异常以确保继续）。
         * 2. 清空受管列表。
         * 3. 关闭生命周期管理器。
         * 4. 关闭数据库存储。
         */
        private void close() {
            for (AutoCloseable closeable : managed) {
                try { closeable.close(); }
                catch (Exception ignored) { /* 剩余进程资源仍需关闭，忽略单个组件异常。*/ }
            }
            managed.clear();
            lifecycle.close();
            store.close();
        }
    }
}
