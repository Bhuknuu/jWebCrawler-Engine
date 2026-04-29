import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class URLManager {

    // FIX 5: Integrated the PriorityScheduler for actual Best-First Search
    private final PriorityScheduler        scheduler;
    private final Map<String, String>      pendingParents;
    private final Set<String>              visitedUrls;
    private final BloomFilterLayer         bloomFilter;

    private String seedDomain;
    private String keyword = "";
    private int maxPages;
    private int maxFrontierSize;
    private int totalEnqueued;

    public URLManager(int maxPages, int maxFrontierSize) {
        this.scheduler       = new PriorityScheduler();
        this.pendingParents  = new java.util.HashMap<>();
        this.visitedUrls     = new HashSet<>();
        this.bloomFilter     = new BloomFilterLayer(Math.max(maxPages, 10000));
        this.maxPages        = maxPages > 0 ? maxPages : 500;
        this.maxFrontierSize = maxFrontierSize > 0 ? maxFrontierSize : 5000;
        this.totalEnqueued   = 0;
    }

    public URLManager() {
        this(500, 5000);
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword != null ? keyword : "";
    }

    public void addSeed(String seedUrl) {
        String normalized = normalizeUrl(seedUrl);
        if (normalized == null || normalized.isBlank()) {
            return;
        }
        this.seedDomain = extractDomain(normalized);
        visitedUrls.add(normalized);
        bloomFilter.insert(normalized);
        
        scheduler.scoreAndEnqueue(normalized, "", keyword, 0);
        pendingParents.put(normalized, null);
        totalEnqueued++;
    }

    public boolean addIfNotVisited(String parentUrl, String url, String anchorText, int depth) {
        String normalized = normalizeUrl(url);
        if (normalized == null || normalized.isBlank()) return false;

        String urlDomain = extractDomain(normalized);
        if (seedDomain != null && urlDomain != null) {
            if (!seedDomain.equals(urlDomain) && !urlDomain.endsWith("." + seedDomain) && !seedDomain.endsWith("." + urlDomain)) return false;
        } else if (seedDomain != null) return false;

        if (isJunkUrl(normalized)) return false;
        if (scheduler.size() >= maxFrontierSize) return false;

        if (bloomFilter.mightContain(normalized)) {
            if (visitedUrls.contains(normalized)) return false;
        }

        visitedUrls.add(normalized);
        bloomFilter.insert(normalized);
        
        pendingParents.put(normalized, parentUrl);
        scheduler.scoreAndEnqueue(normalized, anchorText, keyword, depth);
        
        totalEnqueued++;
        return true;
    }

    public FrontierEdge getNextEdge() {
        PriorityScheduler.ScoredUrl top = scheduler.pollHighestPriority();
        if (top == null) return null;
        String parent = pendingParents.remove(top.url);
        return new FrontierEdge(parent, top.url, top.depth);
    }

    // FIX 4: Re-queue logic that respects the PriorityScheduler architecture
    public void requeueEdge(FrontierEdge edge) {
        pendingParents.put(edge.targetUrl, edge.parentUrl);
        scheduler.scoreAndEnqueue(edge.targetUrl, "", keyword, edge.depth);
    }

    public boolean hasPendingUrls()  { return scheduler.hasPending(); }
    public int     getVisitedCount() { return visitedUrls.size(); }
    public int     getFrontierSize() { return scheduler.size(); }
    public int     getMaxPages()     { return maxPages; }
    public String  getSeedDomain()   { return seedDomain; }

    private static boolean isJunkUrl(String url) {
        String lower = url.toLowerCase();
        if (lower.matches(".*\\.(jpg|jpeg|png|gif|svg|webp|ico|bmp|pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar|gz|tar|mp3|mp4|avi|mov|wmv|css|js|xml|rss|atom|woff|woff2|ttf|eot)([?#].*)?$")) return true;
        if (lower.contains("action=edit") || lower.contains("action=history") || lower.contains("oldid=") || lower.contains("diff=") || lower.contains("special:") || lower.contains("/wiki/user:") || lower.contains("/wiki/talk:") || lower.contains("/wiki/wikipedia:") || lower.contains("/wiki/template:") || lower.contains("/wiki/category:") || lower.contains("/wiki/help:") || lower.contains("/wiki/portal:") || lower.contains("/wiki/file:") || lower.contains("/wiki/module:") || lower.contains("/wiki/mediawiki:") || lower.contains("/w/index.php")) return true;
        if (lower.contains("/login") || lower.contains("/signup") || lower.contains("/register") || lower.contains("/logout") || lower.contains("/wp-admin") || lower.contains("/feed") || lower.contains("/rss") || lower.contains("/print/")) return true;

        String query = "";
        int qIdx = url.indexOf('?');
        if (qIdx >= 0) query = url.substring(qIdx + 1);
        long paramCount = query.isEmpty() ? 0 : query.chars().filter(c -> c == '&').count() + 1;
        return paramCount > 2;
    }

    public static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
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
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) port = -1;

            String path = uri.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

            String query = uri.getQuery();
            StringBuilder result = new StringBuilder();
            result.append(scheme).append("://").append(host);
            if (port != -1) result.append(":").append(port);
            result.append(path);
            if (query != null && !query.isBlank()) result.append("?").append(query);
            return result.toString();
        } catch (URISyntaxException e) { return null; }
    }

    private static String extractDomain(String url) {
        try {
            String host = new URI(url).getHost();
            if (host != null && host.startsWith("www.")) return host.substring(4);
            return host;
        } catch (Exception e) { return null; }
    }
}