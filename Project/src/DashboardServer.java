import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public class DashboardServer {

    private static final int PORT = 8080;

    private final HttpServer      server;
    private final DataStorageModule dataStore;
    private final Path            dashboardDir;
    private final CrawlController crawlController;

    private volatile Thread      activeCrawlThread = null;
    private volatile CrawlEngine activeEngine      = null;

    public enum CrawlStatus { IDLE, RUNNING, FINISHED, ERROR }

    private volatile CrawlStatus crawlStatus = CrawlStatus.IDLE;
    private volatile String      crawlError  = null;

    public interface CrawlController {
        void startCrawl(String seedUrl, int maxDepth, String keyword, int maxPages, int maxBreadth);
    }

    public DashboardServer(DataStorageModule dataStore, Path dashboardDir, CrawlController crawlController) throws IOException {
        this.dataStore       = dataStore;
        this.dashboardDir    = dashboardDir;
        this.crawlController = crawlController;

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        registerHandlers();
        
        // FIX 1: Use a ThreadPool to prevent /api/reset from starving other endpoints
        this.server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "dashboard-http");
            t.setDaemon(true);
            return t;
        }));
    }

    public void registerActiveCrawl(Thread thread, CrawlEngine engine) {
        this.activeCrawlThread = thread;
        this.activeEngine      = engine;
    }

    private void clearActiveCrawl() {
        activeCrawlThread = null;
        activeEngine      = null;
    }

    private void registerHandlers() {
        server.createContext("/api/graph",  new GraphHandler());
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/start",  new StartCrawlHandler());
        server.createContext("/api/reset",  new ResetCrawlHandler());
        server.createContext("/api/page",   new PageDataHandler());
        server.createContext("/",           new StaticFileHandler());
    }

    public void start() {
        server.start();
        System.out.println("[DASHBOARD] Server started -> http://localhost:" + PORT);
    }

    public void stop() {
        server.stop(1);
        System.out.println("[DASHBOARD] Server stopped.");
    }

    public void notifyCrawlFinished() {
        crawlStatus = CrawlStatus.FINISHED;
        clearActiveCrawl();
    }

    public void notifyCrawlError(String message) {
        crawlStatus = CrawlStatus.ERROR;
        crawlError  = message;
        clearActiveCrawl();
    }

    private class PageDataHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "Method Not Allowed"); return;
            }
            try {
                String query = ex.getRequestURI().getRawQuery();
                if (query == null || !query.startsWith("url=")) {
                    sendJson(ex, 400, "{\"error\":\"Missing url query parameter\"}"); return;
                }
                String targetUrl = java.net.URLDecoder.decode(query.substring(4), StandardCharsets.UTF_8);
                String text = dataStore.getPageText(targetUrl);
                if (text == null) {
                    sendJson(ex, 404, "{\"error\":\"No data captured for this page yet\"}"); return;
                }
                sendJson(ex, 200, "{\"url\":" + escapeForJsonValue(targetUrl) + ",\"text\":" + escapeForJsonValue(text) + "}");
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + escapeForJson(e.getMessage()) + "\"}");
            }
        }
    }

    private class ResetCrawlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "Method Not Allowed"); return;
            }
            try {
                // FIX 1: Fire-and-forget interruption, removing the HTTP thread block
                if (crawlStatus == CrawlStatus.RUNNING) {
                    CrawlEngine eng = activeEngine;
                    Thread      thr = activeCrawlThread;
                    if (eng != null) eng.abort(thr);
                }

                dataStore.clear();
                crawlStatus = CrawlStatus.IDLE;
                crawlError  = null;
                clearActiveCrawl();

                sendJson(ex, 200, "{\"status\":\"reset\"}");
            } catch (Exception e) {
                System.err.println("[DASHBOARD] /api/reset error: " + e.getMessage());
                sendJson(ex, 500, "{\"error\":\"" + escapeForJson(e.getMessage()) + "\"}");
            }
        }
    }

    private class GraphHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "Method Not Allowed"); return;
            }
            // FIX 3: Parse delta query to prevent O(N^2) payload bombs
            String raw = ex.getRequestURI().getRawQuery(); 
            int since = 0;
            if (raw != null && raw.startsWith("since=")) {
                try { since = Integer.parseInt(raw.substring(6)); } catch (NumberFormatException ignored) {}
            }
            String json = since == 0 ? dataStore.getGraphJson() : dataStore.getGraphJsonDelta(since);
            sendJson(ex, 200, json);
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "Method Not Allowed"); return;
            }
            int total = dataStore.getResultCount();
            String json;
            if (crawlStatus == CrawlStatus.ERROR) {
                json = String.format("{\"status\":\"error\",\"totalPages\":%d,\"error\":\"%s\"}", total, escapeForJson(crawlError));
            } else {
                json = String.format("{\"status\":\"%s\",\"totalPages\":%d}", crawlStatus.name().toLowerCase(), total);
            }
            sendJson(ex, 200, json);
        }
    }

    private class StartCrawlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "Method Not Allowed"); return;
            }
            try {
                InputStream is   = ex.getRequestBody();
                String body      = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                if (crawlStatus == CrawlStatus.RUNNING) {
                    sendJson(ex, 409, "{\"error\":\"A crawl is already running.\"}"); return;
                }

                String seedUrl    = extractJsonString(body, "seedUrl");
                int    maxDepth   = extractJsonInt(body, "maxDepth",   3);
                String keyword    = extractJsonString(body, "keyword");
                int    maxPages   = extractJsonInt(body, "maxPages",   100);
                int    maxBreadth = extractJsonInt(body, "maxBreadth", 25);

                if (seedUrl == null || seedUrl.isBlank()) {
                    sendJson(ex, 400, "{\"error\":\"seedUrl is required\"}"); return;
                }
                if (!seedUrl.startsWith("http://") && !seedUrl.startsWith("https://")) {
                    sendJson(ex, 400, "{\"error\":\"seedUrl must start with http:// or https://\"}"); return;
                }

                sendJson(ex, 202, "{\"status\":\"accepted\",\"message\":\"Crawl starting...\"}");

                crawlStatus = CrawlStatus.RUNNING;
                crawlController.startCrawl(seedUrl, maxDepth, keyword != null ? keyword : "", maxPages, maxBreadth);

            } catch (Exception e) {
                crawlStatus = CrawlStatus.ERROR;
                sendJson(ex, 500, "{\"error\":\"Failed to start crawl: " + escapeForJson(e.getMessage()) + "\"}");
            }
        }
    }

    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String requestPath = ex.getRequestURI().getPath();
            String filename;
            if (requestPath.equals("/") || requestPath.equals("/index.html")) filename = "index.html";
            else if (requestPath.equals("/style.css")) filename = "style.css";
            else if (requestPath.equals("/app.js"))    filename = "app.js";
            else { sendError(ex, 404, "Not Found"); return; }

            Path filePath = dashboardDir.resolve(filename);
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                sendError(ex, 404, "Dashboard file not found: " + filename);
                return;
            }

            try {
                byte[] body = Files.readAllBytes(filePath);
                ex.getResponseHeaders().set("Content-Type", getContentType(filename));
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            } catch (IOException e) {
                sendError(ex, 500, "Failed to read: " + filename);
            }
        }

        private String getContentType(String f) {
            if (f.endsWith(".html")) return "text/html; charset=UTF-8";
            if (f.endsWith(".css"))  return "text/css; charset=UTF-8";
            if (f.endsWith(".js"))   return "application/javascript; charset=UTF-8";
            return "application/octet-stream";
        }
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static void sendError(HttpExchange ex, int code, String msg) throws IOException {
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static String escapeForJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String escapeForJsonValue(String s) {
        return s == null ? "null" : "\"" + escapeForJson(s) + "\"";
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        return q2 < 0 ? null : json.substring(q1 + 1, q2);
    }

    private static int extractJsonInt(String json, String key, int def) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return def;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return def;
        StringBuilder sb = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c))  sb.append(c);
            else if (sb.length() > 0)  break;
        }
        try { return sb.length() > 0 ? Integer.parseInt(sb.toString()) : def; }
        catch (NumberFormatException e) { return def; }
    }
}