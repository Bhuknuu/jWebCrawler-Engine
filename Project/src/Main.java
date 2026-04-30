import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point for jWebCrawler-Engine.
 * Starts the dashboard server and opens the browser.
 * All configuration is via the browser setup form.
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {

        Path dashboardDir = resolveDashboardDir();
        System.out.println("[INIT] Dashboard assets: " + dashboardDir.toAbsolutePath());

        DataStorageModule dataStore = new DataStorageModule();

        DashboardServer[] serverHolder = new DashboardServer[1];

        DashboardServer.CrawlController crawlController =
            (seedUrl, maxDepth, keyword, maxPages, maxBreadth, enableScraper) -> {
                Thread bfsThread = new Thread(() -> {
                    System.out.println("[BFS] Starting crawl: " + seedUrl);
                    try {
                        URLManager            urlManager  = new URLManager(maxPages, maxPages * 10);
                        HTTPFetcherHTMLParser fetcher     = new HTTPFetcherHTMLParser();
                        CrawlEngine           crawlEngine =
                            new CrawlEngine(urlManager, fetcher, dataStore, enableScraper);

                        // register so pause/stop controls can reach the engine
                        if (serverHolder[0] != null) {
                            serverHolder[0].registerActiveCrawl(Thread.currentThread(), crawlEngine);
                        }

                        urlManager.addSeed(seedUrl);
                        crawlEngine.startBFS(maxDepth, maxBreadth, keyword);
                        dataStore.flush();

                        if (serverHolder[0] != null) {
                            serverHolder[0].notifyCrawlFinished();
                        }
                        System.out.println("[BFS] Crawl complete. Dashboard remains open.");
                    } catch (Exception e) {
                        System.err.println("[BFS] Fatal error during crawl: " + e.getMessage());
                        e.printStackTrace();
                        if (serverHolder[0] != null) {
                            serverHolder[0].notifyCrawlError(e.getMessage());
                        }
                    }
                }, "bfs-worker");
                bfsThread.setDaemon(true);
                bfsThread.start();
            };

        DashboardServer server;
        try {
            server = new DashboardServer(dataStore, dashboardDir, crawlController);
            serverHolder[0] = server;
            server.start();
        } catch (IOException e) {
            System.err.println("[FATAL] Cannot start dashboard server: " + e.getMessage());
            System.err.println("[FATAL] Is port 8080 already in use?");
            System.exit(1);
            return;
        }

        openBrowser("http://localhost:8080");

        final DashboardServer serverRef = server;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SHUTDOWN] Flushing data and writing LLM corpus...");
            dataStore.flush();
            dataStore.flushLLMCorpus();
            serverRef.stop();
        }, "shutdown-hook"));

        System.out.println("[INIT] Ready. Use the browser at http://localhost:8080");
        System.out.println("[INIT] Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }

    private static Path resolveDashboardDir() {
        try {
            URI classLocation = Main.class.getProtectionDomain()
                                          .getCodeSource()
                                          .getLocation()
                                          .toURI();
            Path classDir = Paths.get(classLocation);

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

        System.err.println("[INIT] Warning: Using ./dashboard/ as fallback path.");
        return Paths.get("dashboard").toAbsolutePath().normalize();
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                System.out.println("[INIT] Browser opened to " + url);
            } else {
                System.out.println("[INIT] Auto-open not supported. Please open: " + url);
            }
        } catch (Exception e) {
            System.out.println("[INIT] Could not open browser. Please open: " + url);
        }
    }
}