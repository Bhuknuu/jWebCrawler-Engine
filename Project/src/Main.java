import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point for jWebCrawler-Engine — frontend-first GUI mode.
 *
 * STARTUP SEQUENCE:
 *   1. Resolve the dashboard/ directory (independent of JVM working directory)
 *   2. Create shared DataStorageModule, URLManager, CrawlEngine
 *   3. Start the DashboardServer (binds localhost:8080)
 *   4. Auto-open the browser to http://localhost:8080
 *   5. Block the main thread (keep JVM alive) — server + crawl run on daemon threads
 *   6. The browser setup form POSTs to /api/start -> crawl begins in "bfs-worker" thread
 *   7. Shutdown hook flushes data and stops the server on Ctrl+C or window close
 *
 * NO CLI PROMPTS: All configuration is via the browser setup form.
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {

        // ---------------------------------------------------------------
        // Step 1: Resolve dashboard/ directory relative to this .class file
        //         so it works regardless of JVM working directory (BUG-01 fix)
        // ---------------------------------------------------------------
        Path dashboardDir = resolveDashboardDir();
        System.out.println("[INIT] Dashboard assets: " + dashboardDir.toAbsolutePath());

        // ---------------------------------------------------------------
        // Step 2: Build shared components
        // ---------------------------------------------------------------
        DataStorageModule    dataStore   = new DataStorageModule();
        // URLManager and CrawlEngine are created per-crawl inside the lambda below.
        // This allows future "restart crawl" support without restarting the JVM.

        // ---------------------------------------------------------------
        // Step 3: Wire the CrawlController callback
        //         The server calls this when it receives POST /api/start.
        //         We spin up a fresh URLManager + CrawlEngine per crawl.
        // ---------------------------------------------------------------
        DashboardServer[] serverHolder = new DashboardServer[1]; // array trick for lambda capture

        DashboardServer.CrawlController crawlController = (seedUrl, maxDepth, keyword, maxPages, maxBreadth) -> {
            Thread bfsThread = new Thread(() -> {
                System.out.println("[BFS] Starting crawl: " + seedUrl);
                try {
                    URLManager           urlManager  = new URLManager(maxPages, maxPages * 10);
                    HTTPFetcherHTMLParser fetcher    = new HTTPFetcherHTMLParser();
                    CrawlEngine          crawlEngine = new CrawlEngine(urlManager, fetcher, dataStore);

                    urlManager.addSeed(seedUrl);
                    crawlEngine.startBFS(maxDepth, maxBreadth, keyword);
                    dataStore.flush();

                    if (serverHolder[0] != null) {
                        serverHolder[0].notifyCrawlFinished();
                    }
                    System.out.println("[BFS] Crawl complete. Dashboard remains open for exploration.");
                } catch (Exception e) {
                    System.err.println("[BFS] Fatal error during crawl: " + e.getMessage());
                    if (serverHolder[0] != null) {
                        serverHolder[0].notifyCrawlError(e.getMessage());
                    }
                }
            }, "bfs-worker");
            bfsThread.setDaemon(true);
            bfsThread.start();
        };

        // ---------------------------------------------------------------
        // Step 4: Start the HTTP server
        // ---------------------------------------------------------------
        DashboardServer server;
        try {
            server = new DashboardServer(dataStore, dashboardDir, crawlController);
            serverHolder[0] = server;
            server.start();
        } catch (IOException e) {
            System.err.println("[FATAL] Cannot start dashboard server: " + e.getMessage());
            System.err.println("[FATAL] Is port 8080 already in use? Kill the process using it and retry.");
            System.exit(1);
            return;
        }

        // ---------------------------------------------------------------
        // Step 5: Auto-open browser (BUG-03 fix)
        // ---------------------------------------------------------------
        openBrowser("http://localhost:8080");

        // ---------------------------------------------------------------
        // Step 6: Register shutdown hook
        // ---------------------------------------------------------------
        final DashboardServer serverRef = server;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SHUTDOWN] Flushing data and stopping server...");
            dataStore.flush();
            serverRef.stop();
        }, "shutdown-hook"));

        System.out.println("[INIT] Ready. Use the browser at http://localhost:8080 to configure and start a crawl.");
        System.out.println("[INIT] Press Ctrl+C to stop.");

        // ---------------------------------------------------------------
        // Step 7: Keep main thread alive (server + BFS run on daemon threads)
        //         Main thread blocks here until Ctrl+C / shutdown signal.
        // ---------------------------------------------------------------
        Thread.currentThread().join();
    }

    /**
     * Resolves the dashboard/ directory relative to the location of Main.class.
     *
     * Strategy:
     *   - Main.class is compiled to Project/src/out/Main.class
     *   - dashboard/ is at the project root: Project/../dashboard/ = jWebCrawler-Engine/dashboard/
     *   - So we navigate: out/ -> Project/src/ -> Project/ -> jWebCrawler-Engine/ -> dashboard/
     *
     * If resolution fails (e.g., running from a fat JAR), falls back to ./dashboard/.
     */
    private static Path resolveDashboardDir() {
        try {
            // Get location of Main.class file (the out/ directory)
            URI classLocation = Main.class.getProtectionDomain()
                                          .getCodeSource()
                                          .getLocation()
                                          .toURI();
            Path classDir = Paths.get(classLocation);

            // Navigate: out/ -> Project/src/ -> Project/ -> jWebCrawler-Engine/
            // The dashboard/ directory lives at jWebCrawler-Engine/dashboard/
            // Exact nesting depends on compile output dir; we try multiple levels.
            for (int levels = 1; levels <= 5; levels++) {
                Path candidate = classDir;
                for (int i = 0; i < levels; i++) {
                    candidate = candidate.getParent();
                }
                Path dashCandidate = candidate.resolve("dashboard");
                if (dashCandidate.toFile().isDirectory()) {
                    return dashCandidate.toAbsolutePath().normalize();
                }
            }
        } catch (URISyntaxException e) {
            System.err.println("[INIT] Warning: Could not resolve class location: " + e.getMessage());
        }

        // Fallback: assume current working directory
        System.err.println("[INIT] Warning: Using ./dashboard/ as fallback path.");
        return Paths.get("dashboard").toAbsolutePath().normalize();
    }

    /**
     * Opens the system default browser to the given URL.
     * Silent on failure — the user can open manually.
     */
    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                System.out.println("[INIT] Browser opened to " + url);
            } else {
                System.out.println("[INIT] Auto-open not supported. Please open: " + url);
            }
        } catch (Exception e) {
            System.out.println("[INIT] Could not open browser automatically. Please open: " + url);
        }
    }
}