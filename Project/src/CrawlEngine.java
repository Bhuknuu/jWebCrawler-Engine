import java.util.List;

/**
 * The BFS traversal engine with production-grade safety controls.
 *
 * KEY ALGORITHM: Domain-Bounded Depth-Limited BFS
 *   Standard BFS visits ALL reachable nodes. On the web, that's billions.
 *   This engine applies three bounding strategies:
 *     1. Depth limit   — stops after N hops from the seed
 *     2. Domain scope   — only follows links on the seed's domain
 *     3. Page cap       — hard limit on total pages crawled
 *     4. Politeness     — delay between requests to avoid hammering servers
 */
public class CrawlEngine {

    private final URLManager urlManager;
    private final HTTPFetcherHTMLParser fetcher;
    private final DataStorageModule dataStore;

    private static final int POLITENESS_DELAY_MS = 150;

    private volatile boolean abortFlag = false;
    private long startTimeMs;

    public CrawlEngine(URLManager urlManager, HTTPFetcherHTMLParser fetcher, DataStorageModule dataStore) {
        this.urlManager = urlManager;
        this.fetcher = fetcher;
        this.dataStore = dataStore;
    }

    /**
     * Runs the depth-limited, domain-bounded BFS crawl.
     *
     * BFS depth tracking uses the level-order counting trick:
     *   pagesInCurrentLevel counts down as we dequeue.
     *   When it hits zero, we advance to the next depth level.
     *
     * Safety: The loop terminates when ANY of these are true:
     *   - Frontier is empty (BFS exhausted)
     *   - Current depth exceeds maxDepth
     *   - Total pages crawled reaches urlManager.maxPages
     *   - User triggers abort via Ctrl+C / shutdown hook
     */
    public void startBFS(int maxDepth, String keyword) {
        startTimeMs = System.currentTimeMillis();

        int currentDepth = 0;
        int pagesInCurrentLevel = urlManager.getFrontierSize();
        int pagesInNextLevel = 0;
        int pagesCrawled = 0;
        int maxPages = urlManager.getMaxPages();

        System.out.println("[INFO] Starting BFS crawl");
        System.out.println("[INFO]   Max depth   : " + maxDepth);
        System.out.println("[INFO]   Max pages   : " + maxPages);
        System.out.println("[INFO]   Domain      : " + urlManager.getSeedDomain());
        System.out.println("[INFO]   Politeness  : " + POLITENESS_DELAY_MS + "ms between requests");
        System.out.println("[INFO]   Depth 0 has " + pagesInCurrentLevel + " seed URL(s)");
        System.out.println();

        while (urlManager.hasPendingUrls() && currentDepth <= maxDepth && !abortFlag) {
            // Page cap check
            if (pagesCrawled >= maxPages) {
                System.out.println("\n[INFO] Page limit reached (" + maxPages + "). Stopping.");
                break;
            }

            String currentUrl = urlManager.getNextUrl();
            if (currentUrl == null) {
                break;
            }

            // Politeness delay
            if (pagesCrawled > 0) {
                try {
                    Thread.sleep(POLITENESS_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            long fetchStart = System.currentTimeMillis();
            String html = fetcher.fetchPage(currentUrl);
            long fetchTimeMs = System.currentTimeMillis() - fetchStart;

            if (html != null) {
                String title = fetcher.extractTitle(html);
                String pageText = fetcher.extractPageText(html);
                List<String> links = fetcher.extractLinks(html, currentUrl);

                boolean keywordMatch = false;
                if (keyword != null && !keyword.isBlank()) {
                    String lowerKeyword = keyword.toLowerCase();
                    keywordMatch = title.toLowerCase().contains(lowerKeyword)
                                || pageText.toLowerCase().contains(lowerKeyword);
                }

                DataStorageModule.CrawlResult result = new DataStorageModule.CrawlResult(
                    currentUrl, title, currentDepth, fetchTimeMs, keywordMatch
                );
                dataStore.addResult(result);
                pagesCrawled++;

                // Enqueue children for the next level
                int acceptedLinks = 0;
                if (currentDepth < maxDepth) {
                    for (String link : links) {
                        if (urlManager.addIfNotVisited(link)) {
                            pagesInNextLevel++;
                            acceptedLinks++;
                        }
                    }
                }

                System.out.printf("[%3d/%d] D%d | %-55s | %-28s | %d/%d links | %dms%s%n",
                    pagesCrawled, maxPages, currentDepth,
                    truncate(currentUrl, 55),
                    truncate(title, 28),
                    acceptedLinks, links.size(),
                    fetchTimeMs,
                    keywordMatch ? " | MATCH" : "");
            } else {
                System.out.printf("[%3d/%d] D%d | %-55s | FETCH FAILED | %dms%n",
                    pagesCrawled + 1, maxPages, currentDepth,
                    truncate(currentUrl, 55),
                    fetchTimeMs);
            }

            // Depth level transition
            pagesInCurrentLevel--;
            if (pagesInCurrentLevel <= 0) {
                currentDepth++;
                pagesInCurrentLevel = pagesInNextLevel;
                pagesInNextLevel = 0;
                if (currentDepth <= maxDepth && pagesInCurrentLevel > 0) {
                    System.out.println();
                    System.out.println("[INFO] Depth " + currentDepth
                        + " — " + pagesInCurrentLevel + " pages queued"
                        + " (frontier: " + urlManager.getFrontierSize()
                        + ", visited: " + urlManager.getVisitedCount() + ")");
                }
            }
        }

        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        System.out.println();
        System.out.println("  ========================================");
        System.out.println("    Crawl Complete");
        System.out.println("  ========================================");
        System.out.printf("    Pages crawled    : %d%n", dataStore.getResultCount());
        System.out.printf("    URLs discovered  : %d%n", urlManager.getVisitedCount());
        System.out.printf("    Frontier remain  : %d%n", urlManager.getFrontierSize());
        System.out.printf("    Domain scoped to : %s%n", urlManager.getSeedDomain());
        System.out.printf("    Time elapsed     : %s%n", formatDuration(elapsedMs));
        if (abortFlag) {
            System.out.println("    (Crawl was aborted by user)");
        }
        System.out.println("  ========================================");
    }

    /** Signals the crawl loop to stop after the current page. */
    public void abort() {
        abortFlag = true;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        }
        return seconds + "s " + (ms % 1000) + "ms";
    }
}
