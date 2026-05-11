package ricbot.integration.api;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class RicbotApiSupport {
    static final int MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024;

    private RicbotApiSupport() {
    }

    static ExecutorService newApiExecutor() {
        int threads = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        AtomicInteger seq = new AtomicInteger(1);
        return new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "ricbot-api-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = is.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BODY_BYTES) {
                    throw new PayloadTooLargeException("请求体超过限制：" + MAX_REQUEST_BODY_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    static final class PayloadTooLargeException extends IOException {
        private PayloadTooLargeException(String message) {
            super(message);
        }
    }
}
