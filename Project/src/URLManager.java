import java.util.LinkedList;
import java.util.Queue;
import java.util.HashSet;
import java.util.Set;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Manages the BFS frontier queue with domain-scoped crawling.
 *
 * KEY ALGORITHM: Domain-Bounded BFS
 *   Unlike naive BFS that follows ALL edges, this enforces a domain
 *   boundary so the crawler only explores pages on the same host as
 *   the seed URL. This is how real crawlers (Googlebot, Heritrix)
 *   implement focused crawling.
 *
 * SAFETY CAPS:
 *   maxPages       — hard limit on total pages to prevent runaway crawls
 *   maxFrontierSize — prevents the queue from consuming all memory
 *   BloomFilter     — probabilistic pre-filter before HashSet lookup
 */
public class URLManager {

    // Changed from Queue<String> to Queue<FrontierEdge> to capture graph topology.
    // Each edge in this queue represents a directed link: parent -> target.
    private final Queue<FrontierEdge> frontier;
    private final Set<String> visitedUrls;
    private final BloomFilterLayer bloomFilter;

    private String seedDomain;
    private int maxPages;
    private int maxFrontierSize;
    private int totalEnqueued;

    /**
     * Creates a URLManager with safety caps.
     * @param maxPages         maximum total pages to crawl (0 = unlimited)
     * @param maxFrontierSize  maximum queue size before rejecting new URLs
     */
    public URLManager(int maxPages, int maxFrontierSize) {
        this.frontier = new LinkedList<>();
        this.visitedUrls = new HashSet<>();
        this.bloomFilter = new BloomFilterLayer(Math.max(maxPages, 10000));
        this.maxPages = maxPages > 0 ? maxPages : 500;
        this.maxFrontierSize = maxFrontierSize > 0 ? maxFrontierSize : 5000;
        this.totalEnqueued = 0;
    }

    /** Default constructor with sensible limits. */
    public URLManager() {
        this(500, 5000);
    }

    /**
     * Seeds the BFS with the starting URL.
     * Extracts the domain to enforce same-domain scoping for all future URLs.
     */
    public void addSeed(String seedUrl) {
        String normalized = normalizeUrl(seedUrl);
        if (normalized == null || normalized.isBlank()) {
            return;
        }
        this.seedDomain = extractDomain(normalized);
        visitedUrls.add(normalized);
        bloomFilter.insert(normalized);
        // Seed node: parentUrl is null (it IS the graph root, no incoming edge)
        frontier.offer(new FrontierEdge(null, normalized, 0));
        totalEnqueued++;
    }

    /**
     * Adds a URL to the frontier only if it passes ALL filters:
     *   1. Not null/blank after normalization
     *   2. Same domain as the seed (domain-bounded BFS)
     *   3. Not already in the Bloom filter (fast probabilistic check)
     *   4. Not already in the HashSet (definitive duplicate check)
     *   5. Frontier has not exceeded maxFrontierSize
     *   6. Total enqueued has not exceeded maxPages
     *   7. Not a junk URL (images, stylesheets, login pages, etc.)
     */
    /**
     * Adds a URL to the frontier if it passes all filters.
     *
     * @param parentUrl the URL that discovered this link (for graph edge tracking)
     * @param url       the discovered URL to potentially enqueue
     * @param depth     the BFS depth at which this URL was discovered
     * @return true if the URL was accepted and enqueued; false otherwise
     */
    public boolean addIfNotVisited(String parentUrl, String url, int depth) {
        String normalized = normalizeUrl(url);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }

        // Domain scoping: only crawl pages on the same host or subdomains
        String urlDomain = extractDomain(normalized);
        if (seedDomain != null && urlDomain != null) {
            if (!seedDomain.equals(urlDomain) 
                && !urlDomain.endsWith("." + seedDomain)
                && !seedDomain.endsWith("." + urlDomain)) {
                return false;
            }
        } else if (seedDomain != null) {
            return false;
        }

        // Junk URL filter
        if (isJunkUrl(normalized)) {
            return false;
        }


        if (frontier.size() >= maxFrontierSize) {
            return false;
        }

        // Two-layer dedup: Bloom filter (fast) then HashSet (definitive)
        if (bloomFilter.mightContain(normalized)) {
            if (visitedUrls.contains(normalized)) {
                return false;
            }
        }

        visitedUrls.add(normalized);
        bloomFilter.insert(normalized);
        // Store the parent->target edge so the graph visualizer can draw directed edges
        frontier.offer(new FrontierEdge(parentUrl, normalized, depth));
        totalEnqueued++;
        return true;
    }

    /**
     * Dequeues the next frontier edge from the BFS queue.
     * Returns null if the frontier is empty.
     * The caller uses edge.targetUrl for fetching and edge.parentUrl for graph topology.
     */
    public FrontierEdge getNextEdge() {
        return frontier.poll();
    }

    /** Checks if there are still URLs waiting to be crawled. */
    public boolean hasPendingUrls() {
        return !frontier.isEmpty();
    }

    /** Returns how many unique URLs have been seen so far. */
    public int getVisitedCount() {
        return visitedUrls.size();
    }

    /** Returns the current frontier queue size. */
    public int getFrontierSize() {
        return frontier.size();
    }

    /** Returns the max pages cap. */
    public int getMaxPages() {
        return maxPages;
    }

    /** Returns the seed domain this crawler is scoped to. */
    public String getSeedDomain() {
        return seedDomain;
    }

    /**
     * Filters out non-content URLs that waste crawl budget:
     *   - Binary files: images, PDFs, videos, archives
     *   - Wikipedia noise: action=edit, Special:, User:, Talk:, oldid=
     *   - CMS junk: login, signup, logout, /wp-admin, /feed
     *   - Query-heavy URLs (more than 2 parameters usually mean dynamic junk)
     */
    private static boolean isJunkUrl(String url) {
        String lower = url.toLowerCase();

        // Binary file extensions
        if (lower.matches(".*\\.(jpg|jpeg|png|gif|svg|webp|ico|bmp|pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar|gz|tar|mp3|mp4|avi|mov|wmv|css|js|xml|rss|atom|woff|woff2|ttf|eot)([?#].*)?$")) {
            return true;
        }

        // Wikipedia-specific noise (the #1 cause of BFS explosion)
        if (lower.contains("action=edit")
                || lower.contains("action=history")
                || lower.contains("oldid=")
                || lower.contains("diff=")
                || lower.contains("special:")
                || lower.contains("/wiki/user:")
                || lower.contains("/wiki/talk:")
                || lower.contains("/wiki/wikipedia:")
                || lower.contains("/wiki/template:")
                || lower.contains("/wiki/category:")
                || lower.contains("/wiki/help:")
                || lower.contains("/wiki/portal:")
                || lower.contains("/wiki/file:")
                || lower.contains("/wiki/module:")
                || lower.contains("/wiki/mediawiki:")
                || lower.contains("/w/index.php")) {
            return true;
        }

        // CMS and login pages
        if (lower.contains("/login") || lower.contains("/signup")
                || lower.contains("/register") || lower.contains("/logout")
                || lower.contains("/wp-admin") || lower.contains("/feed")
                || lower.contains("/rss") || lower.contains("/print/")) {
            return true;
        }

        // Too many query parameters (usually dynamic/stateful pages)
        String query = "";
        int qIdx = url.indexOf('?');
        if (qIdx >= 0) {
            query = url.substring(qIdx + 1);
        }
        long paramCount = query.isEmpty() ? 0 : query.chars().filter(c -> c == '&').count() + 1;
        if (paramCount > 2) {
            return true;
        }

        return false;
    }

    /**
     * Normalizes a URL to canonical form:
     *   1. Lowercase scheme and host
     *   2. Remove default ports (:80, :443)
     *   3. Remove fragment identifiers (#section)
     *   4. Remove trailing slash (unless root "/")
     */
    public static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        rawUrl = rawUrl.strip();

        try {
            URI uri = new URI(rawUrl);

            String scheme = uri.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) return null;

            String host = uri.getHost();
            if (host == null || host.isBlank()) return null;
            host = host.toLowerCase();

            int port = uri.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                port = -1;
            }

            String path = uri.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            String query = uri.getQuery();

            StringBuilder result = new StringBuilder();
            result.append(scheme).append("://").append(host);
            if (port != -1) result.append(":").append(port);
            result.append(path);
            if (query != null && !query.isBlank()) {
                result.append("?").append(query);
            }
            return result.toString();

        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Extracts the host (domain) from a URL string, stripping www. prefix. */
    private static String extractDomain(String url) {
        try {
            String host = new URI(url).getHost();
            if (host != null && host.startsWith("www.")) {
                return host.substring(4);
            }
            return host;
        } catch (Exception e) {
            return null;
        }
    }
}
