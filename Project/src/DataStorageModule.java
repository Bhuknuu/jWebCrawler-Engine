import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores crawl results in memory and exports to CSV and JSON files.
 * Supports incremental flushing to prevent data loss on crashes.
 */
public class DataStorageModule {

    /**
     * Immutable record holding data for one crawled page.
     */
    public static class CrawlResult {
        public final String url;
        public final String title;
        public final int depth;
        public final long fetchTimeMs;
        public final boolean keywordMatch;
        public final long timestamp;

        public CrawlResult(String url, String title, int depth, long fetchTimeMs, boolean keywordMatch) {
            this.url = url;
            this.title = title;
            this.depth = depth;
            this.fetchTimeMs = fetchTimeMs;
            this.keywordMatch = keywordMatch;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static final int FLUSH_INTERVAL = 50;
    private static final String OUTPUT_DIR = "crawl_output";

    private final List<CrawlResult> results;
    private final String sessionId;
    private int lastFlushedIndex;
    private boolean flushed;

    public DataStorageModule() {
        this.results = new ArrayList<>();
        this.sessionId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        this.lastFlushedIndex = 0;

        java.io.File dir = new java.io.File(OUTPUT_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * Adds a result to the in-memory buffer.
     * Triggers an incremental flush every FLUSH_INTERVAL pages.
     */
    public void addResult(CrawlResult result) {
        results.add(result);
        if (results.size() % FLUSH_INTERVAL == 0) {
            flushIncrementalCSV();
        }
    }

    /**
     * Returns an unmodifiable view of all results collected so far.
     */
    public List<CrawlResult> getAllResults() {
        return Collections.unmodifiableList(results);
    }

    /**
     * Returns the total number of results stored.
     */
    public int getResultCount() {
        return results.size();
    }

    /**
     * Writes all results to both CSV and JSON files.
     * Guarded to prevent double-export from shutdown hook.
     */
    public void flush() {
        if (results.isEmpty() || flushed) {
            return;
        }
        flushed = true;
        exportCSV();
        exportJSON();
    }

    /**
     * Exports all results to a CSV file.
     */
    private void exportCSV() {
        String filename = OUTPUT_DIR + "/crawl_" + sessionId + ".csv";
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename, false))) {
            writer.println("URL,Title,Depth,FetchTime_ms,KeywordMatch,Timestamp");

            for (CrawlResult r : results) {
                writer.println(
                    escapeCSV(r.url) + ","
                    + escapeCSV(r.title) + ","
                    + r.depth + ","
                    + r.fetchTimeMs + ","
                    + r.keywordMatch + ","
                    + r.timestamp
                );
            }

            System.out.println("[EXPORT] CSV saved: " + filename + " (" + results.size() + " rows)");
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to write CSV: " + e.getMessage());
        }
    }

    /**
     * Appends only new results since the last flush to the CSV file.
     * This prevents data loss if the crawl crashes mid-session.
     */
    private void flushIncrementalCSV() {
        String filename = OUTPUT_DIR + "/crawl_" + sessionId + "_partial.csv";
        boolean isNewFile = (lastFlushedIndex == 0);

        try (PrintWriter writer = new PrintWriter(new FileWriter(filename, true))) {
            if (isNewFile) {
                writer.println("URL,Title,Depth,FetchTime_ms,KeywordMatch,Timestamp");
            }

            for (int i = lastFlushedIndex; i < results.size(); i++) {
                CrawlResult r = results.get(i);
                writer.println(
                    escapeCSV(r.url) + ","
                    + escapeCSV(r.title) + ","
                    + r.depth + ","
                    + r.fetchTimeMs + ","
                    + r.keywordMatch + ","
                    + r.timestamp
                );
            }

            lastFlushedIndex = results.size();
        } catch (IOException e) {
            System.err.println("[ERROR] Incremental flush failed: " + e.getMessage());
        }
    }

    /**
     * Exports all results to a JSON file.
     */
    private void exportJSON() {
        String filename = OUTPUT_DIR + "/crawl_" + sessionId + ".json";
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename, false))) {
            writer.println("{");
            writer.println("  \"crawlSession\": {");
            writer.println("    \"sessionId\": \"" + sessionId + "\",");
            writer.println("    \"totalPages\": " + results.size() + ",");
            writer.println("    \"exportedAt\": \"" + LocalDateTime.now() + "\"");
            writer.println("  },");
            writer.println("  \"results\": [");

            for (int i = 0; i < results.size(); i++) {
                CrawlResult r = results.get(i);
                String comma = (i < results.size() - 1) ? "," : "";
                writer.println("    {");
                writer.println("      \"url\": " + escapeJSON(r.url) + ",");
                writer.println("      \"title\": " + escapeJSON(r.title) + ",");
                writer.println("      \"depth\": " + r.depth + ",");
                writer.println("      \"fetchTimeMs\": " + r.fetchTimeMs + ",");
                writer.println("      \"keywordMatch\": " + r.keywordMatch + ",");
                writer.println("      \"timestamp\": " + r.timestamp);
                writer.println("    }" + comma);
            }

            writer.println("  ]");
            writer.println("}");

            System.out.println("[EXPORT] JSON saved: " + filename + " (" + results.size() + " entries)");
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to write JSON: " + e.getMessage());
        }
    }

    /**
     * Escapes a field value for CSV. Wraps in quotes if the value
     * contains commas, quotes, or newlines.
     */
    private static String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Escapes a string for JSON. Wraps in quotes and escapes
     * special characters.
     */
    private static String escapeJSON(String value) {
        if (value == null) return "null";
        String escaped = value.replace("\\", "\\\\")
                              .replace("\"", "\\\"")
                              .replace("\n", "\\n")
                              .replace("\r", "\\r")
                              .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }
}
