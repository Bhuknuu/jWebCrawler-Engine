import java.util.List;

/**
 * The BFS traversal engine. Pulls URLs from the frontier,
 * fetches pages, extracts links, enforces the depth limit,
 * and stores results.
 */
public class CrawlEngine {

    private final URLManager urlManager;
    private final HTTPFetcherHTMLParser fetcher;
    private final DataStorageModule dataStore;

    private volatile boolean abortFlag = false;
    private long startTimeMs;

    public CrawlEngine(URLManager urlManager, HTTPFetcherHTMLParser fetcher, DataStorageModule dataStore) {
        this.urlManager = urlManager;
        this.fetcher = fetcher;
        this.dataStore = dataStore;
    }

    /**
     * Runs the depth-limited BFS crawl from the seed URL already in the frontier.
     *
     * BFS depth tracking uses the level-order counting trick:
     *   pagesInCurrentLevel counts down as we dequeue.
     *   When it hits zero, we advance to the next depth level.
     */
    public void startBFS(int maxDepth, String keyword) {
        startTimeMs = System.currentTimeMillis();

        int currentDepth = 0;
        int pagesInCurrentLevel = urlManager.getFrontierSize();
        int pagesInNextLevel = 0;

        System.out.println("[INFO] Starting BFS crawl (max depth: " + maxDepth + ")");
        System.out.println("[INFO] Depth 0 starting with " + pagesInCurrentLevel + " seed URL(s)");
        System.out.println();

        while (urlManager.hasPendingUrls() && currentDepth <= maxDepth && !abortFlag) {
            String currentUrl = urlManager.getNextUrl();
            if (currentUrl == null) {
                break;
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

                System.out.println("[CRAWL] Depth " + currentDepth
                    + " | " + truncate(currentUrl, 60)
                    + " | " + truncate(title, 30)
                    + " | " + links.size() + " links"
                    + " | " + fetchTimeMs + "ms"
                    + (keywordMatch ? " | MATCH" : ""));

                // Enqueue children for the next level
                if (currentDepth < maxDepth) {
                    for (String link : links) {
                        if (urlManager.addIfNotVisited(link)) {
                            pagesInNextLevel++;
                        }
                    }
                }
            } else {
                System.out.println("[SKIP]  Depth " + currentDepth
                    + " | " + truncate(currentUrl, 60)
                    + " | fetch failed | " + fetchTimeMs + "ms");
            }

            // Depth level transition
            pagesInCurrentLevel--;
            if (pagesInCurrentLevel == 0) {
                currentDepth++;
                pagesInCurrentLevel = pagesInNextLevel;
                pagesInNextLevel = 0;
                if (currentDepth <= maxDepth && pagesInCurrentLevel > 0) {
                    System.out.println();
                    System.out.println("[INFO] Depth " + currentDepth
                        + " beginning (" + pagesInCurrentLevel + " pages in queue)");
                }
            }
        }

        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        System.out.println();
        System.out.println("========================================");
        System.out.println("  Crawl complete");
        System.out.println("  Pages crawled  : " + dataStore.getResultCount());
        System.out.println("  URLs discovered: " + urlManager.getVisitedCount());
        System.out.println("  Time elapsed   : " + formatDuration(elapsedMs));
        if (abortFlag) {
            System.out.println("  (Crawl was aborted by user)");
        }
        System.out.println("========================================");
    }

    /**
     * Signals the crawl loop to stop after the current page.
     */
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
