/**
 * A lightweight, immutable data carrier representing a directed edge in the crawl graph.
 *
 * WHY THIS CLASS EXISTS:
 *   The original BFS queue stored raw URL Strings. This was sufficient for a pure
 *   CLI crawler that only needed to visit pages. To power the graph visualization,
 *   we need to know the parent->child relationship for every discovered URL so that
 *   Cytoscape.js can draw directed edges between nodes.
 *
 * DESIGN DECISIONS:
 *   - All fields are final: FrontierEdge is immutable once created. No setters needed.
 *   - parentUrl is nullable: the seed URL has no parent (it is the graph root).
 *   - depth is stored here so CrawlEngine does not need to re-track it separately.
 *   - No inheritance, no interfaces: keep this class as thin as possible to minimize
 *     heap allocation overhead in the BFS queue.
 *
 * ALTERNATIVE CONSIDERED:
 *   Using Java Records (Java 16+): cleaner syntax, but this project targets Java 8+
 *   to maximize compatibility. This explicit class is the Java 8-safe equivalent.
 */
public final class FrontierEdge {

    /**
     * The URL that discovered this link. Null only for the seed URL (root node).
     * In graph terms: this is the source vertex of the directed edge.
     */
    public final String parentUrl;

    /**
     * The URL to be crawled. Never null.
     * In graph terms: this is the target vertex of the directed edge.
     */
    public final String targetUrl;

    /**
     * The BFS depth at which targetUrl was discovered.
     * Depth 0 = seed URL. Depth 1 = pages linked directly from seed. Etc.
     */
    public final int depth;

    /**
     * Constructs a new FrontierEdge.
     *
     * @param parentUrl  the URL that linked to targetUrl; null if this is the seed
     * @param targetUrl  the URL to be crawled; must not be null
     * @param depth      the BFS depth of targetUrl; must be >= 0
     * @throws IllegalArgumentException if targetUrl is null or depth is negative
     */
    public FrontierEdge(String parentUrl, String targetUrl, int depth) {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalArgumentException("FrontierEdge: targetUrl must not be null or blank");
        }
        if (depth < 0) {
            throw new IllegalArgumentException("FrontierEdge: depth must be >= 0, got: " + depth);
        }
        this.parentUrl = parentUrl;
        this.targetUrl = targetUrl;
        this.depth = depth;
    }

    /**
     * Returns true if this edge represents the root seed node (no parent).
     */
    public boolean isSeedNode() {
        return parentUrl == null;
    }

    @Override
    public String toString() {
        return "FrontierEdge{"
            + "parent=" + (parentUrl != null ? parentUrl : "ROOT")
            + ", target=" + targetUrl
            + ", depth=" + depth
            + "}";
    }
}
