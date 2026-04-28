import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded zero-dependency HTTP server for the jWebCrawler Dashboard.
 *
 * ARCHITECTURE (revised — frontend-first):
 *   The server starts immediately when the program runs. There is no CLI.
 *   The browser is auto-opened to the setup page. All crawl configuration
 *   is submitted via POST /api/start from the frontend form.
 *
 * ENDPOINTS:
 *   GET  /             -> dashboard/index.html (setup form + graph view)
 *   GET  /style.css    -> dashboard/style.css
 *   GET  /app.js       -> dashboard/app.js
 *   GET  /api/graph    -> live Cytoscape-compliant JSON
 *   GET  /api/status   -> crawler state JSON {status, totalPages}
 *   POST /api/start    -> start a crawl with JSON body {seedUrl, maxDepth, keyword, maxPages}
 *
 * PATH RESOLUTION (BUG-01 fix):
 *   The dashboard directory path is passed in at construction time from Main.java,
 *   which resolves it relative to the .class file location — independent of
 *   the JVM working directory.
 *
 * CONCURRENCY:
 *   HTTP server runs on a single daemon thread.
 *   BFS crawl runs on a separate "bfs-worker" thread (started on /api/start).
 *   DataStorageModule.getGraphJson() is synchronized for safe concurrent reads.
 */
public class DashboardServer {

    private static final int PORT = 8080;

    private final HttpServer        server;
    private final DataStorageModule dataStore;
    private final Path              dashboardDir;
    private final CrawlController   crawlController;

    // -------------------------------------------------------------------------
    // Crawl state — shared between /api/start and /api/status
    // -------------------------------------------------------------------------
    /** Enum representing possible crawler states for the frontend status dot. */
    public enum CrawlStatus { IDLE, RUNNING, FINISHED, ERROR }

    private volatile CrawlStatus  crawlStatus  = CrawlStatus.IDLE;
    private volatile String       crawlError   = null;

    /**
     * Functional interface so DashboardServer can start a crawl without
     * having a direct reference to URLManager/CrawlEngine (avoids circular deps).
     */
    public interface CrawlController {
        /**
         * Start the BFS crawl with the given parameters.
         * Called on the HTTP handler thread; MUST be non-blocking
         * (implementation should start a new Thread).
         */
        void startCrawl(String seedUrl, int maxDepth, String keyword, int maxPages);
    }

    /**
     * @param dataStore      shared data store for graph JSON and result counts
     * @param dashboardDir   resolved path to the dashboard/ directory (never relative)
     * @param crawlController callback invoked when POST /api/start is received
     */
    public DashboardServer(DataStorageModule dataStore, Path dashboardDir,
                           CrawlController crawlController) throws IOException {
        this.dataStore       = dataStore;
        this.dashboardDir    = dashboardDir;
        this.crawlController = crawlController;

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        registerHandlers();
        this.server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dashboard-http");
            t.setDaemon(true);
            return t;
        }));
    }

    private void registerHandlers() {
        server.createContext("/api/graph",  new GraphHandler());
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/start",  new StartCrawlHandler());
        server.createContext("/api/reset",  new ResetCrawlHandler());
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

    /** Called by Main.java when BFS finishes, so the frontend status dot updates. */
    public void notifyCrawlFinished() {
        crawlStatus = CrawlStatus.FINISHED;
    }

    /** Called by Main.java if BFS throws a fatal error. */
    public void notifyCrawlError(String message) {
        crawlStatus = CrawlStatus.ERROR;
        crawlError  = message;
    }

    // =========================================================================
    // HANDLER: POST /api/reset
    // =========================================================================
    private class ResetCrawlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendError(ex, 405, "Method Not Allowed"); return; }
            try {
                if (crawlStatus == CrawlStatus.RUNNING) {
                    sendJson(ex, 409, "{\"error\": \"Cannot reset while a crawl is running.\"}");
                    return;
                }
                dataStore.clear();
                crawlStatus = CrawlStatus.IDLE;
                crawlError = null;
                sendJson(ex, 200, "{\"status\": \"reset\"}");
            } catch (Exception e) {
                System.err.println("[DASHBOARD] /api/reset error: " + e.getMessage());
                sendJson(ex, 500, "{\"error\": \"" + escapeForJson(e.getMessage()) + "\"}");
            }
        }
    }

    // =========================================================================
    // HANDLER: GET /api/graph
    // =========================================================================
    private class GraphHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { sendError(ex, 405, "Method Not Allowed"); return; }
            try {
                sendJson(ex, 200, dataStore.getGraphJson());
            } catch (Exception e) {
                System.err.println("[DASHBOARD] /api/graph error: " + e.getMessage());
                sendJson(ex, 500, "{\"error\": \"" + escapeForJson(e.getMessage()) + "\"}");
            }
        }
    }

    // =========================================================================
    // HANDLER: GET /api/status  (BUG-05 fix: uses crawlStatus enum, not page count)
    // =========================================================================
    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { sendError(ex, 405, "Method Not Allowed"); return; }
            try {
                String errorField = crawlError != null
                    ? ", \"error\": \"" + escapeForJson(crawlError) + "\""
                    : "";
                String json = "{"
                    + "\"status\": \"" + crawlStatus.name().toLowerCase() + "\","
                    + "\"totalPages\": " + dataStore.getResultCount()
                    + errorField
                    + "}";
                sendJson(ex, 200, json);
            } catch (Exception e) {
                System.err.println("[DASHBOARD] /api/status error: " + e.getMessage());
                sendJson(ex, 500, "{\"error\": \"Status query failed\"}");
            }
        }
    }

    // =========================================================================
    // HANDLER: POST /api/start  (BUG-04 fix: frontend triggers the crawl)
    // =========================================================================
    private class StartCrawlHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange ex) throws IOException {
            // CORS preflight
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendError(ex, 405, "Method Not Allowed"); return; }
            try {
                // Read JSON body manually since HttpServer does not auto-parse
                InputStream is = ex.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                System.out.println("[DASHBOARD] /api/start received body: " + body);

                if (crawlStatus == CrawlStatus.RUNNING) {
                    sendJson(ex, 409, "{\"error\": \"A crawl is already running.\"}");
                    return;
                }
                
                String seedUrl  = extractJsonString(body, "seedUrl");
                int    maxDepth = extractJsonInt(body, "maxDepth", 3);
                String keyword  = extractJsonString(body, "keyword");
                int    maxPages = extractJsonInt(body, "maxPages", 100);

                // Basic input validation
                if (seedUrl == null || seedUrl.isBlank()) {
                    sendJson(ex, 400, "{\"error\": \"seedUrl is required\"}");
                    return;
                }
                if (!seedUrl.startsWith("http://") && !seedUrl.startsWith("https://")) {
                    sendJson(ex, 400, "{\"error\": \"seedUrl must start with http:// or https://\"}");
                    return;
                }
                if (maxDepth < 1 || maxDepth > 10) {
                    sendJson(ex, 400, "{\"error\": \"maxDepth must be between 1 and 10\"}");
                    return;
                }
                if (maxPages < 1 || maxPages > 10000) {
                    sendJson(ex, 400, "{\"error\": \"maxPages must be between 1 and 10000\"}");
                    return;
                }

                // Acknowledge the request immediately, THEN start crawl asynchronously
                sendJson(ex, 202, "{\"status\": \"accepted\", \"message\": \"Crawl starting...\"}");

                crawlStatus = CrawlStatus.RUNNING;
                final String finalSeedUrl = seedUrl;
                final String finalKeyword = keyword != null ? keyword : "";
                final int    finalDepth   = maxDepth;
                final int    finalPages   = maxPages;

                System.out.println("[DASHBOARD] Starting crawl: seed=" + finalSeedUrl
                    + " depth=" + finalDepth + " pages=" + finalPages + " keyword=" + finalKeyword);

                crawlController.startCrawl(finalSeedUrl, finalDepth, finalKeyword, finalPages);

            } catch (Exception e) {
                crawlStatus = CrawlStatus.ERROR;
                System.err.println("[DASHBOARD] /api/start error: " + e.getMessage());
                sendJson(ex, 500, "{\"error\": \"Failed to start crawl: " + escapeForJson(e.getMessage()) + "\"}");
            }
        }
    }

    // =========================================================================
    // HANDLER: GET / — Static File Server  (BUG-01 fix: uses injected dashboardDir)
    // =========================================================================
    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String requestPath = ex.getRequestURI().getPath();

            // Whitelist-only: prevent path traversal by design
            String filename;
            if (requestPath.equals("/") || requestPath.equals("/index.html")) {
                filename = "index.html";
            } else if (requestPath.equals("/style.css")) {
                filename = "style.css";
            } else if (requestPath.equals("/app.js")) {
                filename = "app.js";
            } else {
                sendError(ex, 404, "Not Found: " + requestPath);
                return;
            }

            Path filePath = dashboardDir.resolve(filename);

            if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                System.err.println("[DASHBOARD] Static file missing: " + filePath.toAbsolutePath());
                sendError(ex, 404,
                    "Dashboard file not found: " + filename
                    + " (looked in: " + dashboardDir.toAbsolutePath() + ")\n"
                    + "Ensure the 'dashboard/' directory is at the project root.");
                return;
            }

            try {
                byte[] body = Files.readAllBytes(filePath);
                ex.getResponseHeaders().set("Content-Type", getContentType(filename));
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            } catch (IOException e) {
                System.err.println("[DASHBOARD] Error reading: " + filename + " — " + e.getMessage());
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

    // =========================================================================
    // UTILITIES
    // =========================================================================
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
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // Minimal JSON field extractors — avoids needing Gson/Jackson
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int quote1 = json.indexOf('"', colon + 1);
        if (quote1 < 0) return null;
        int quote2 = json.indexOf('"', quote1 + 1);
        if (quote2 < 0) return null;
        return json.substring(quote1 + 1, quote2);
    }

    private static int extractJsonInt(String json, String key, int defaultVal) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return defaultVal;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return defaultVal;
        StringBuilder sb = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c)) sb.append(c);
            else if (sb.length() > 0) break;
        }
        try { return sb.length() > 0 ? Integer.parseInt(sb.toString()) : defaultVal; }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
