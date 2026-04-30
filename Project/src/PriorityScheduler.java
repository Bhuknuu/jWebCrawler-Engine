import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Heap-based URL scheduler that ranks URLs by relevance score.
 * Transforms plain BFS (FIFO) into Best-First Search (priority queue).
 *
 * Scoring factors:
 *   1. Keyword match in anchor text (weight: 5.0)
 *   2. Domain authority (.edu/.gov bonus) (weight: 2.0)
 *   3. Path depth heuristic (weight: 1.0)
 *   4. Domain novelty bonus (weight: 1.5)
 */
public class PriorityScheduler {

    /**
     * A URL paired with its relevance score for heap ordering.
     */
    public static class ScoredUrl {
        public final String url;
        public final double score;
        public final int depth;
        public final String anchorText;

        public ScoredUrl(String url, double score, int depth, String anchorText) {
            this.url = url;
            this.score = score;
            this.depth = depth;
            this.anchorText = anchorText;
        }
    }

    // Max-heap: highest score first
    private final PriorityQueue<ScoredUrl> queue;
    private final Set<String> seenDomains;

    public PriorityScheduler() {
        this.queue = new PriorityQueue<>(
            Comparator.comparingDouble((ScoredUrl s) -> s.score).reversed()
        );
        this.seenDomains = new HashSet<>();
    }

    /**
     * Computes a relevance score for a URL based on four factors.
     */
    public double computeScore(String url, String anchorText, String keyword, int depth) {
        double score = 0.0;

        // Factor 1: Keyword match via word boundary regex
        if (keyword != null && !keyword.isBlank()) {
            java.util.regex.Pattern kw = java.util.regex.Pattern.compile(
                "\\b" + java.util.regex.Pattern.quote(keyword.toLowerCase()) + "\\b");
            if (anchorText != null && kw.matcher(anchorText.toLowerCase()).find()) {
                score += 5.0;
            } else if (kw.matcher(url.toLowerCase()).find()) {
                score += 3.0;
            }
        }

        // Factor 2: Domain authority
        String domain = extractDomain(url);
        if (domain != null) {
            if (domain.endsWith(".edu") || domain.endsWith(".gov")) {
                score += 2.0;
            } else if (domain.endsWith(".org")) {
                score += 1.5;
            } else {
                score += 1.0;
            }
        }

        // Factor 3: Path depth
        int pathDepth = countPathSegments(url);
        score += 1.0 / (pathDepth + 1);

        // Factor 4: Domain novelty
        if (domain != null && !seenDomains.contains(domain)) {
            score += 1.5;
        }

        // Factor 5: Depth penalty to discourage deep, low-relevance pages
        score -= (depth * depth) * 0.3;
        if (score < 0) score = 0;

        return score;
    }

    /**
     * Enqueues a scored URL into the priority queue.
     * O(log n) heap insertion.
     */
    public void offer(ScoredUrl scoredUrl) {
        queue.offer(scoredUrl);
        String domain = extractDomain(scoredUrl.url);
        if (domain != null) {
            seenDomains.add(domain);
        }
    }

    /**
     * Dequeues the highest-scored URL.
     * O(log n) heap extraction.
     */
    public ScoredUrl pollHighestPriority() {
        return queue.poll();
    }

    /**
     * Checks if the scheduler has pending URLs.
     */
    public boolean hasPending() {
        return !queue.isEmpty();
    }

    /**
     * Returns the current queue size.
     */
    public int size() {
        return queue.size();
    }

    /**
     * Convenience method: score, wrap, and enqueue in one call.
     */
    public void scoreAndEnqueue(String url, String anchorText, String keyword, int depth) {
        double score = computeScore(url, anchorText, keyword, depth);
        offer(new ScoredUrl(url, score, depth, anchorText));
    }

    private static String extractDomain(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static int countPathSegments(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String path = uri.getPath();
            if (path == null || path.equals("/") || path.isEmpty()) {
                return 0;
            }
            return (int) path.chars().filter(c -> c == '/').count();
        } catch (Exception e) {
            return 0;
        }
    }
}
