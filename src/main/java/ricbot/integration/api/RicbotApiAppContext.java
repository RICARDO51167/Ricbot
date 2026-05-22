package ricbot.integration.api;

import com.sun.net.httpserver.HttpExchange;
import ricbot.domain.agent.AgentLoop;
import ricbot.infra.config.Config;
import ricbot.infra.config.ConfigLoader;

import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runtime state shared by OpenAI-compatible API handlers.
 */
public class RicbotApiAppContext {
    private static final int MAX_SESSION_LOCKS = 4096;
    private static final long SESSION_LOCK_IDLE_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final long SESSION_LOCK_CLEANUP_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private final AgentLoop agentLoop;
    private final String modelName;
    private final long requestTimeoutMillis;
    private final String bindHost;
    private final String bearerToken;
    private final boolean requireAuth;
    private final Config config;
    private final Path configPath;
    private final Path workspace;
    private final Map<String, SessionLockEntry> sessionLocks = new ConcurrentHashMap<>();
    private volatile long lastLockCleanupMillis = 0L;

    public RicbotApiAppContext(
            AgentLoop agentLoop,
            String modelName,
            long requestTimeoutMillis,
            String bindHost,
            String bearerToken
    ) {
        this(agentLoop, modelName, requestTimeoutMillis, bindHost, bearerToken, null, null, null);
    }

    public RicbotApiAppContext(
            AgentLoop agentLoop,
            String modelName,
            long requestTimeoutMillis,
            String bindHost,
            String bearerToken,
            Config config,
            Path configPath,
            Path workspace
    ) {
        this.agentLoop = agentLoop;
        this.modelName = modelName != null ? modelName : "ricbot";
        this.requestTimeoutMillis = requestTimeoutMillis > 0 ? requestTimeoutMillis : 120_000L;
        this.bindHost = bindHost != null && !bindHost.isBlank() ? bindHost : "127.0.0.1";
        this.bearerToken = bearerToken != null ? bearerToken.trim() : "";
        this.requireAuth = !this.bearerToken.isBlank() || !isLoopbackHost(this.bindHost);
        this.config = config != null ? config : new Config();
        this.configPath = configPath != null ? configPath.toAbsolutePath().normalize() : ConfigLoader.getConfigPath();
        this.workspace = workspace != null ? workspace.toAbsolutePath().normalize() : this.config.getWorkspacePath();
    }

    public AgentLoop getAgentLoop() {
        return agentLoop;
    }

    public String getModelName() {
        return modelName;
    }

    public long getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public String getBindHost() {
        return bindHost;
    }

    public Config getConfig() {
        return config;
    }

    public Path getConfigPath() {
        return configPath;
    }

    public Path getWorkspace() {
        return workspace;
    }

    public boolean isConsoleExposedBeyondLoopback() {
        return !isLoopbackHost(bindHost);
    }

    public boolean isAuthorized(HttpExchange exchange) {
        if (!requireAuth) {
            return true;
        }
        if (bearerToken.isBlank()) {
            return false;
        }

        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }

        String provided = header.substring(7).trim();
        return MessageDigest.isEqual(
                bearerToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }

    public SessionLockLease acquireSessionLock(String sessionKey) {
        cleanupSessionLocksIfNeeded();
        String key = sessionKey != null && !sessionKey.isBlank() ? sessionKey : RicbotApiServer.API_SESSION_KEY;
        SessionLockEntry entry = sessionLocks.computeIfAbsent(key, k -> new SessionLockEntry());
        entry.acquire();
        return new SessionLockLease(key, entry);
    }

    private void cleanupSessionLocksIfNeeded() {
        long now = System.currentTimeMillis();
        if (sessionLocks.size() < MAX_SESSION_LOCKS
                && now - lastLockCleanupMillis < SESSION_LOCK_CLEANUP_INTERVAL_MILLIS) {
            return;
        }
        lastLockCleanupMillis = now;
        for (Map.Entry<String, SessionLockEntry> item : sessionLocks.entrySet()) {
            SessionLockEntry entry = item.getValue();
            if (entry.users.get() != 0 || now - entry.lastAccessMillis < SESSION_LOCK_IDLE_MILLIS) {
                continue;
            }
            if (!entry.lock.tryLock()) {
                continue;
            }
            try {
                if (entry.users.get() == 0 && now - entry.lastAccessMillis >= SESSION_LOCK_IDLE_MILLIS) {
                    sessionLocks.remove(item.getKey(), entry);
                }
            } finally {
                entry.lock.unlock();
            }
        }
    }

    public static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception e) {
            return "localhost".equalsIgnoreCase(host);
        }
    }

    public static final class SessionLockLease implements AutoCloseable {
        private final String key;
        private final SessionLockEntry entry;
        private boolean closed;

        private SessionLockLease(String key, SessionLockEntry entry) {
            this.key = key;
            this.entry = entry;
        }

        public String key() {
            return key;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            entry.release();
        }
    }

    private static final class SessionLockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger users = new AtomicInteger();
        private volatile long lastAccessMillis = System.currentTimeMillis();

        private void acquire() {
            users.incrementAndGet();
            lastAccessMillis = System.currentTimeMillis();
            lock.lock();
        }

        private void release() {
            try {
                lastAccessMillis = System.currentTimeMillis();
                lock.unlock();
            } finally {
                users.decrementAndGet();
            }
        }
    }
}
