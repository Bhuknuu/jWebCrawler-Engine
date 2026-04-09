import java.util.LinkedList;
import java.util.Queue;
import java.util.HashSet;
import java.util.Set;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Manages the BFS frontier queue and visited URL tracking.
 * The frontier is a FIFO queue (LinkedList) for level-order traversal.
 * The visited set is a HashSet for O(1) duplicate detection.
 */
public class URLManager {

    private final Queue<String> frontier;
    private final Set<String> visitedUrls;

    public URLManager() {
        this.frontier = new LinkedList<>();
        this.visitedUrls = new HashSet<>();
    }

    /**
     * Seeds the BFS with the starting URL.
     * Marks it visited before enqueueing to prevent self-loop re-discovery.
     */
    public void addSeed(String seedUrl) {
        String normalized = normalizeUrl(seedUrl);
        if (normalized != null && !normalized.isBlank()) {
            visitedUrls.add(normalized);
            frontier.offer(normalized);
        }
    }

    /**
     * Adds a URL to the frontier only if it has not been visited.
     * Returns true if the URL was new and enqueued, false if duplicate.
     */
    public boolean addIfNotVisited(String url) {
        String normalized = normalizeUrl(url);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        if (visitedUrls.contains(normalized)) {
            return false;
        }
        visitedUrls.add(normalized);
        frontier.offer(normalized);
        return true;
    }

    /**
     * Dequeues the next URL from the frontier.
     * Returns null if the frontier is empty (BFS exhausted).
     */
    public String getNextUrl() {
        return frontier.poll();
    }

    /**
     * Checks whether there are still URLs waiting to be crawled.
     */
    public boolean hasPendingUrls() {
        return !frontier.isEmpty();
    }

    /**
     * Returns how many unique URLs have been seen so far.
     */
    public int getVisitedCount() {
        return visitedUrls.size();
    }

    /**
     * Returns the current size of the frontier queue.
     */
    public int getFrontierSize() {
        return frontier.size();
    }

    /**
     * Normalizes a URL into a canonical form to prevent duplicate crawling:
     *   1. Lowercase the scheme and host
     *   2. Remove default ports (:80 for http, :443 for https)
     *   3. Remove fragment identifiers (#section)
     *   4. Remove trailing slash (unless path is just "/")
     *   5. Remove common tracking parameters
     *
     * Returns null if the URL is malformed.
     */
    public static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }

        rawUrl = rawUrl.strip();

        try {
            URI uri = new URI(rawUrl);

            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            scheme = scheme.toLowerCase();

            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            host = host.toLowerCase();

            int port = uri.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                port = -1;
            }

            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            String query = uri.getQuery();

            // Rebuild without fragment
            StringBuilder result = new StringBuilder();
            result.append(scheme).append("://").append(host);
            if (port != -1) {
                result.append(":").append(port);
            }
            result.append(path);
            if (query != null && !query.isBlank()) {
                result.append("?").append(query);
            }

            return result.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
