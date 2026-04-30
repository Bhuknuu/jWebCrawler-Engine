import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

public class CrawlEngine {

    private final URLManager urlManager;
    private final HTTPFetcherHTMLParser fetcher;
    private final DataStorageModule dataStore;
    private final boolean enableScraper;

    private volatile boolean abortFlag = false;
    private volatile boolean pauseFlag = false;
    private long startTimeMs;

    public CrawlEngine(URLManager urlManager, HTTPFetcherHTMLParser fetcher,
                       DataStorageModule dataStore, boolean enableScraper) {
        this.urlManager = urlManager;
        this.fetcher = fetcher;
        this.dataStore = dataStore;
        this.enableScraper = enableScraper;
    }

    public void startBFS(int maxDepth, int maxBreadth, String keyword) {
        startTimeMs = System.currentTimeMillis();
        urlManager.setKeyword(keyword);
        int maxPages = urlManager.getMaxPages();
        int numThreads = Math.min(Runtime.getRuntime().availableProcessors() * 2, 16);

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        AtomicInteger pagesCrawled = new AtomicInteger(0);

        // tracks how many tasks are currently in-flight
        AtomicInteger inFlight = new AtomicInteger(0);

        // a blocking queue for edges to dispatch; workers push new edges back here
        BlockingQueue<FrontierEdge> workQueue = new LinkedBlockingQueue<>();

        Pattern kwPattern = (keyword != null && !keyword.isBlank())
            ? Pattern.compile("\\b" + Pattern.quote(keyword.toLowerCase()) + "\\b")
            : null;

        RobotsTxtModule robots = new RobotsTxtModule();

        System.out.println("[INFO] Starting BFS crawl");
        System.out.printf("[INFO]   Seed domain  : %s%n", urlManager.getSeedDomain());
        System.out.printf("[INFO]   Max depth    : %d%n", maxDepth);
        System.out.printf("[INFO]   Max breadth  : %d%n", maxBreadth);
        System.out.printf("[INFO]   Max pages    : %d%n", maxPages);
        System.out.printf("[INFO]   Threads      : %d%n", numThreads);
        System.out.printf("[INFO]   Scraper      : %s%n", enableScraper ? "ON" : "OFF");
        System.out.printf("[INFO]   Keyword      : %s%n", keyword != null ? keyword : "(none)");
        System.out.println();

        // seed the work queue
        FrontierEdge seed = urlManager.getNextEdge();
        if (seed == null) {
            System.err.println("[ERROR] No seed URL in frontier. Crawl cannot start.");
            return;
        }
        workQueue.add(seed);

        // dispatch loop: runs until queue is empty AND no tasks are in-flight
        while (!abortFlag && pagesCrawled.get() < maxPages) {
            FrontierEdge edge = workQueue.poll();

            if (edge == null) {
                // queue is temporarily empty — check if workers are still producing
                if (inFlight.get() == 0) {
                    // all workers done and queue empty: genuine termination
                    break;
                }
                // workers still running; yield and retry
                try { Thread.sleep(20); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            if (edge.depth > maxDepth) {
                // don't dispatch but keep checking — queue might have shallower items
                continue;
            }

            inFlight.incrementAndGet();
            final FrontierEdge finalEdge = edge;

            pool.submit(() -> {
                try {
                    processEdge(finalEdge, maxDepth, maxBreadth, kwPattern,
                                robots, pagesCrawled, maxPages, workQueue);
                } catch (Exception e) {
                    System.err.println("[WORKER] Unhandled error on " + finalEdge.targetUrl
                                       + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }

        pool.shutdown();
        try {
            boolean finished = pool.awaitTermination(10, TimeUnit.MINUTES);
            if (!finished) {
                System.err.println("[WARN] Worker pool did not finish within 10 minutes. Forcing shutdown.");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }

        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        System.out.printf("%n[DONE] Pages crawled : %d / %d%n", pagesCrawled.get(), maxPages);
        System.out.printf("[DONE] Time elapsed  : %s%n", formatDuration(elapsedMs));
    }

    private void processEdge(FrontierEdge edge, int maxDepth, int maxBreadth,
                              Pattern kwPattern, RobotsTxtModule robots,
                              AtomicInteger pagesCrawled, int maxPages,
                              BlockingQueue<FrontierEdge> workQueue) {

        // pause gate — blocks until resumed or aborted
        while (pauseFlag && !abortFlag) {
            try { Thread.sleep(200); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }

        if (abortFlag || pagesCrawled.get() >= maxPages) return;

        String currentUrl   = edge.targetUrl;
        String parentUrl    = edge.parentUrl;
        int    currentDepth = edge.depth;

        if (currentUrl == null || currentUrl.isBlank()) {
            System.err.println("[WORKER] Skipping null/blank URL from parent: " + parentUrl);
            return;
        }

        // robots.txt check — does NOT consume a page slot on failure
        boolean allowed = false;
        try {
            allowed = robots.isAllowed(currentUrl);
        } catch (Exception e) {
            System.err.println("[ROBOTS] Error checking " + currentUrl + ": " + e.getMessage());
            allowed = true; // allow on error so one bad robots.txt doesn't halt the crawl
        }
        if (!allowed) {
            System.out.println("[ROBOTS] Blocked: " + currentUrl);
            return;
        }

        // politeness: requeue if too soon — does NOT consume a page slot
        try {
            if (pagesCrawled.get() > 0 && !robots.isPoliteToVisit(currentUrl)) {
                urlManager.requeueEdge(edge);
                workQueue.offer(edge);
                return;
            }
        } catch (Exception e) {
            System.err.println("[ROBOTS] Politeness check error: " + e.getMessage());
        }

        // fetch page — does NOT consume a slot if it fails
        String html = null;
        long fetchStart = System.currentTimeMillis();
        try {
            html = fetcher.fetchPage(currentUrl);
        } catch (Exception e) {
            System.err.println("[FETCH] Error on " + currentUrl + ": " + e.getMessage());
            return;
        }
        long fetchTimeMs = System.currentTimeMillis() - fetchStart;

        try { robots.recordVisit(currentUrl); } catch (Exception ignored) {}

        if (html == null || html.isBlank()) {
            // don't log as error — many pages return empty (redirects, paywalls, etc.)
            return;
        }

        // parse content
        String title    = "";
        String pageText = "";
        List<HTTPFetcherHTMLParser.LinkWithText> links = List.of();

        try { title    = fetcher.extractTitle(html); }
        catch (Exception e) { System.err.println("[PARSE] Title error on " + currentUrl + ": " + e.getMessage()); }

        try { pageText = fetcher.extractPageText(html); }
        catch (Exception e) { System.err.println("[PARSE] Text error on " + currentUrl + ": " + e.getMessage()); }

        try { links = fetcher.extractLinksWithText(html, currentUrl); }
        catch (Exception e) { System.err.println("[PARSE] Links error on " + currentUrl + ": " + e.getMessage()); }

        // keyword match
        boolean keywordMatch = false;
        if (kwPattern != null) {
            try {
                String content = ((title != null ? title : "") + " " + (pageText != null ? pageText : "")).toLowerCase();
                keywordMatch = kwPattern.matcher(content).find();
            } catch (Exception e) {
                System.err.println("[MATCH] Keyword match error: " + e.getMessage());
            }
        }

        // atomically claim a page slot ONLY after a successful fetch+parse
        // this ensures blocked/failed URLs never count against maxPages
        int claimedSlot;
        do {
            claimedSlot = pagesCrawled.get();
            if (claimedSlot >= maxPages) return; // limit hit while we were fetching
        } while (!pagesCrawled.compareAndSet(claimedSlot, claimedSlot + 1));
        int count = claimedSlot + 1;

        // store result
        try {
            dataStore.addResult(new DataStorageModule.CrawlResult(
                currentUrl, title, parentUrl, currentDepth, fetchTimeMs, keywordMatch));
        } catch (Exception e) {
            System.err.println("[STORE] Failed to add result for " + currentUrl + ": " + e.getMessage());
        }

        // store page text to disk if scraper is enabled
        if (enableScraper && pageText != null && !pageText.isBlank()) {
            try {
                dataStore.storePageText(currentUrl, pageText);
            } catch (Exception e) {
                System.err.println("[STORE] Failed to write text for " + currentUrl + ": " + e.getMessage());
            }
        }

        // focused crawl: only follow links from keyword-matched pages (Crawler4j pattern)
        boolean shouldFollowLinks = (kwPattern == null) || keywordMatch;
        int acceptedLinks = 0;

        if (shouldFollowLinks && currentDepth < maxDepth && !abortFlag) {
            int breadthCount = 0;
            for (HTTPFetcherHTMLParser.LinkWithText lwt : links) {
                if (breadthCount >= maxBreadth || pagesCrawled.get() >= maxPages) break;
                if (lwt == null || lwt.url == null) continue;
                try {
                    if (urlManager.addIfNotVisited(currentUrl, lwt.url, lwt.anchorText, currentDepth + 1)) {
                        workQueue.offer(new FrontierEdge(currentUrl, lwt.url, currentDepth + 1));
                        breadthCount++;
                        acceptedLinks++;
                    }
                } catch (Exception e) {
                    System.err.println("[FRONTIER] Error enqueuing " + lwt.url + ": " + e.getMessage());
                }
            }
        }

        System.out.printf("[%3d/%d] D%d | %-52s | %-25s | +%d links | %dms%s%n",
            count, maxPages, currentDepth,
            truncate(currentUrl, 52),
            truncate(title, 25),
            acceptedLinks, fetchTimeMs,
            keywordMatch ? " [MATCH]" : "");
    }

    public void abort() { abortFlag = true; }

    public void abort(Thread crawlThread) {
        abortFlag = true;
        if (crawlThread != null) crawlThread.interrupt();
    }

    public void pause()  { pauseFlag = true;  }
    public void resume() { pauseFlag = false; }

    public boolean isAborted() { return abortFlag; }
    public boolean isPaused()  { return pauseFlag; }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) return "(no title)";
        text = text.strip();
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long secs = ms / 1000;
        long mins = secs / 60;
        secs = secs % 60;
        return mins > 0 ? String.format("%dm %ds", mins, secs) : secs + "s " + (ms % 1000) + "ms";
    }
}