import java.util.List;

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

    public void startBFS(int maxDepth, int maxBreadth, String keyword) {
        startTimeMs = System.currentTimeMillis();

        RobotsTxtModule robotsModule = new RobotsTxtModule();
        
        // FIX 5: Wire the priority queue keyword scoring engine
        urlManager.setKeyword(keyword);

        // FIX 7: Truncated dead BFS level-tracking variables
        int currentDepth = 0;
        int pagesCrawled = 0;
        int maxPages = urlManager.getMaxPages();

        System.out.println("[INFO] Starting BFS crawl");
        System.out.println("[INFO]   Max depth   : " + maxDepth);
        System.out.println("[INFO]   Max breadth : " + maxBreadth);
        System.out.println("[INFO]   Max pages   : " + maxPages);
        System.out.println("[INFO]   Domain      : " + urlManager.getSeedDomain());
        System.out.println();

        while (urlManager.hasPendingUrls() && !abortFlag) {
            if (pagesCrawled >= maxPages) {
                System.out.println("\n[INFO] Page limit reached (" + maxPages + "). Stopping.");
                break;
            }

            FrontierEdge edge = urlManager.getNextEdge();
            if (edge == null || edge.depth > maxDepth) break; 
            
            String currentUrl = edge.targetUrl;
            String parentUrl  = edge.parentUrl;
            currentDepth = edge.depth;

            if (!robotsModule.isAllowed(currentUrl)) {
                System.out.printf("[SKIP] robots.txt DISALLOW: %s%n", truncate(currentUrl, 70));
                continue;
            }

            // FIX 4: Non-blocking politeness yield.
            if (pagesCrawled > 0 && !robotsModule.isPoliteToVisit(currentUrl)) {
                urlManager.requeueEdge(edge);
                continue;
            }

            long fetchStart = System.currentTimeMillis();
            String html = fetcher.fetchPage(currentUrl);
            long fetchTimeMs = System.currentTimeMillis() - fetchStart;

            robotsModule.recordVisit(currentUrl);

            if (html != null) {
                String title = fetcher.extractTitle(html);
                String pageText = fetcher.extractPageText(html);
                List<HTTPFetcherHTMLParser.LinkWithText> links = fetcher.extractLinksWithText(html, currentUrl);

                boolean keywordMatch = false;
                if (keyword != null && !keyword.isBlank()) {
                    String lowerKeyword = keyword.toLowerCase();
                    keywordMatch = title.toLowerCase().contains(lowerKeyword) || pageText.toLowerCase().contains(lowerKeyword);
                }

                DataStorageModule.CrawlResult result = new DataStorageModule.CrawlResult(
                    currentUrl, title, parentUrl, currentDepth, fetchTimeMs, keywordMatch
                );
                dataStore.addResult(result);
                
                if (pageText != null && !pageText.isBlank()) {
                    dataStore.storePageText(currentUrl, pageText);
                }
                pagesCrawled++;

                int acceptedLinks = 0;
                if (currentDepth < maxDepth) {
                    int breadthCount = 0;
                    for (HTTPFetcherHTMLParser.LinkWithText lwt : links) {
                        if (breadthCount >= maxBreadth) break;
                        // FIX 5: Pass anchor text through to allow heuristic scoring
                        if (urlManager.addIfNotVisited(currentUrl, lwt.url, lwt.anchorText, currentDepth + 1)) {
                            breadthCount++;
                        }
                    }
                    acceptedLinks = breadthCount;
                }

                System.out.printf("[%3d/%d] D%d | %-55s | %-28s | %d/%d links | %dms%s%n",
                    pagesCrawled, maxPages, currentDepth, truncate(currentUrl, 55), truncate(title, 28),
                    acceptedLinks, links.size(), fetchTimeMs, keywordMatch ? " | MATCH" : "");
            } else {
                System.out.printf("[%3d/%d] D%d | %-55s | FETCH FAILED | %dms%n",
                    pagesCrawled + 1, maxPages, currentDepth, truncate(currentUrl, 55), fetchTimeMs);
            }
        }

        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        System.out.println("\n  ========================================");
        System.out.printf("    Pages crawled    : %d%n", dataStore.getResultCount());
        System.out.printf("    Time elapsed     : %s%n", formatDuration(elapsedMs));
        System.out.println("  ========================================");
    }

    public void abort() { abortFlag = true; }
    public void abort(Thread crawlThread) {
        abortFlag = true;
        if (crawlThread != null) crawlThread.interrupt();
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
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return seconds + "s " + (ms % 1000) + "ms";
    }
}