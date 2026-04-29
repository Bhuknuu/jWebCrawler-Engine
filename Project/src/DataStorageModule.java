import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class DataStorageModule {

    public static class CrawlResult {
        public final String url;
        public final String title;
        public final String parentUrl;
        public final int depth;
        public final long fetchTimeMs;
        public final boolean keywordMatch;
        public final long timestamp;

        public CrawlResult(String url, String title, String parentUrl, int depth, long fetchTimeMs, boolean keywordMatch) {
            this.url = url;
            this.title = title;
            this.parentUrl = parentUrl;
            this.depth = depth;
            this.fetchTimeMs = fetchTimeMs;
            this.keywordMatch = keywordMatch;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static final int FLUSH_INTERVAL = 50;
    private static final String OUTPUT_DIR = "crawl_output";

    private final List<CrawlResult> results;
    private final ConcurrentHashMap<String, String> pageTextCache;
    private String sessionId;
    private int lastFlushedCsvIndex;
    private int lastFlushedJsonIndex;
    private boolean flushed;

    public DataStorageModule() {
        this.results       = new ArrayList<>();
        this.pageTextCache = new ConcurrentHashMap<>();
        this.sessionId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        this.lastFlushedCsvIndex = 0;
        this.lastFlushedJsonIndex = 0;

        java.io.File dir = new java.io.File(OUTPUT_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public void clear() {
        synchronized (results) {
            results.clear();
            sessionId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            lastFlushedCsvIndex = 0;
            lastFlushedJsonIndex = 0;
            flushed = false;
        }
        pageTextCache.clear();
    }

    public void storePageText(String url, String text) {
        if (url == null || url.isBlank() || text == null) return;
        if (text.length() > 65536) {
            text = text.substring(0, 65536) + "\n[...truncated at 64 KB]";
        }
        pageTextCache.put(url, text);
    }

    public String getPageText(String url) {
        if (url == null) return null;
        return pageTextCache.get(url);
    }

    // FIX 2: Synchronize to prevent ConcurrentModificationException
    public void addResult(CrawlResult result) {
        synchronized (results) {
            results.add(result);
            if (results.size() % FLUSH_INTERVAL == 0) {
                flushIncrementalCSV();
                flushIncrementalJSON();
            }
        }
    }

    public List<CrawlResult> getAllResults() {
        return Collections.unmodifiableList(results);
    }

    // FIX 2: Thread-safe read
    public int getResultCount() {
        synchronized (results) {
            return results.size();
        }
    }

    public void flush() {
        if (flushed) return;
        flushed = true;
        exportCSV();
        exportJSON();
        exportGraphJSON();
    }

    // FIX 3: Delta tracking JSON extraction to prevent network tab crashes
    public String getGraphJsonDelta(int since) {
        synchronized (results) {
            int from = Math.max(0, Math.min(since, results.size()));

            StringBuilder sb = new StringBuilder();
            sb.append("{\"nodes\":[");

            boolean first = true;
            for (int i = from; i < results.size(); i++) {
                CrawlResult r = results.get(i);
                if (!first) sb.append(',');
                sb.append("{\"data\":{")
                  .append("\"id\":").append(escapeJSON(r.url)).append(',')
                  .append("\"label\":").append(escapeJSON(r.title != null ? r.title : r.url)).append(',')
                  .append("\"depth\":").append(r.depth).append(',')
                  .append("\"fetchTimeMs\":").append(r.fetchTimeMs).append(',')
                  .append("\"keywordMatch\":").append(r.keywordMatch).append(',')
                  .append("\"timestamp\":").append(r.timestamp)
                  .append("}}");
                first = false;
            }

            sb.append("],\"edges\":[");

            first = true;
            int edgeId = since; 
            for (int i = from; i < results.size(); i++) {
                CrawlResult r = results.get(i);
                if (r.parentUrl == null || r.parentUrl.isBlank()) continue;
                if (!first) sb.append(',');
                sb.append("{\"data\":{")
                  .append("\"id\":\"e").append(edgeId++).append("\",")
                  .append("\"source\":").append(escapeJSON(r.parentUrl)).append(',')
                  .append("\"target\":").append(escapeJSON(r.url))
                  .append("}}");
                first = false;
            }

            sb.append("],\"nextSince\":").append(results.size()).append('}');
            return sb.toString();
        }
    }

    public String getGraphJson() {
        return getGraphJsonDelta(0);
    }

    private void exportGraphJSON() {
        String filename = OUTPUT_DIR + "/crawl_" + sessionId + "_graph.json";
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename, false))) {
            writer.print(getGraphJson());
        } catch (IOException e) {}
    }

    private void exportCSV() {
        String filename = OUTPUT_DIR + "/crawl_" + sessionId + ".csv";
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename, false))) {
            writer.println("URL,Title,ParentURL,Depth,FetchTime_ms,KeywordMatch,Timestamp");
            for (CrawlResult r : results) {
                writer.println(escapeCSV(r.url) + "," + escapeCSV(r.title) + "," + escapeCSV(r.parentUrl) + "," + r.depth + "," + r.fetchTimeMs + "," + r.keywordMatch + "," + r.timestamp);
            }
        } catch (IOException e) {}
    }

    private void flushIncrementalCSV() {
        String filename = OUTPUT_DIR + "/crawl_" + sessionId + "_partial.csv";
        boolean isNewFile = (lastFlushedCsvIndex == 0);
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename, true))) {
            if (isNewFile) writer.println("URL,Title,ParentURL,Depth,FetchTime_ms,KeywordMatch,Timestamp");
            for (int i = lastFlushedCsvIndex; i < results.size(); i++) {
                CrawlResult r = results.get(i);
                writer.println(escapeCSV(r.url) + "," + escapeCSV(r.title) + "," + escapeCSV(r.parentUrl) + "," + r.depth + "," + r.fetchTimeMs + "," + r.keywordMatch + "," + r.timestamp);
            }
            lastFlushedCsvIndex = results.size();
        } catch (IOException e) {}
    }

    private void flushIncrementalJSON() {
        String filename = OUTPUT_DIR + "/crawl_" + sessionId + "_partial.json";
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename, true))) {
            for (int i = lastFlushedJsonIndex; i < results.size(); i++) {
                CrawlResult r = results.get(i);
                writer.println("  {");
                writer.println("    \"url\": " + escapeJSON(r.url) + ",");
                writer.println("    \"title\": " + escapeJSON(r.title) + ",");
                writer.println("    \"parentUrl\": " + escapeJSON(r.parentUrl) + ",");
                writer.println("    \"depth\": " + r.depth + ",");
                writer.println("    \"fetchTimeMs\": " + r.fetchTimeMs + ",");
                writer.println("    \"keywordMatch\": " + r.keywordMatch + ",");
                writer.println("    \"timestamp\": " + r.timestamp);
                writer.println("  },");
            }
            lastFlushedJsonIndex = results.size();
        } catch (IOException e) {}
    }

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
                writer.println("      \"parentUrl\": " + escapeJSON(r.parentUrl) + ",");
                writer.println("      \"depth\": " + r.depth + ",");
                writer.println("      \"fetchTimeMs\": " + r.fetchTimeMs + ",");
                writer.println("      \"keywordMatch\": " + r.keywordMatch + ",");
                writer.println("      \"timestamp\": " + r.timestamp);
                writer.println("    }" + comma);
            }
            writer.println("  ]\n}");
        } catch (IOException e) {}
    }

    private static String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String escapeJSON(String value) {
        if (value == null) return "null";
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        StringBuilder sb = new StringBuilder(escaped);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                String replacement = String.format("\\u%04x", (int) c);
                sb.replace(i, i + 1, replacement);
                i += replacement.length() - 1;
            }
        }
        return "\"" + sb + "\"";
    }
}