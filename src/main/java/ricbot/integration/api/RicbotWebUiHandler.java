package ricbot.integration.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;

/**
 * Serves the bundled web UI under "/" and "/app/*".
 */
public class RicbotWebUiHandler implements HttpHandler {
    private static final String INDEX_RESOURCE = "webui/index.html";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            RicbotApiServer.writeErrorJson(exchange, 405, "不支持的 HTTP 方法", "invalid_request_error");
            return;
        }

        String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : "/";
        String resource = resolveResource(path);
        if (resource == null) {
            RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
            return;
        }

        byte[] bytes;
        try (InputStream in = RicbotWebUiHandler.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                RicbotApiServer.writeErrorJson(exchange, 404, "资源不存在", "not_found");
                return;
            }
            bytes = in.readAllBytes();
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(resource));
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : bytes.length);
        if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    private static String resolveResource(String path) {
        if (path == null || path.isBlank() || "/".equals(path) || "/app".equals(path) || "/app/".equals(path)) {
            return INDEX_RESOURCE;
        }
        if (!path.startsWith("/app/")) {
            return null;
        }
        String relative = path.substring("/app/".length());
        if (relative.isBlank() || relative.contains("..") || relative.startsWith("/")) {
            return INDEX_RESOURCE;
        }
        return "webui/" + relative;
    }

    private static String contentType(String resource) {
        String guessed = URLConnection.guessContentTypeFromName(resource);
        if (guessed != null) {
            return guessed + "; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resource.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
