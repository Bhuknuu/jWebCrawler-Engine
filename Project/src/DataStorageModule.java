import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    private static final String OUTPUT_DIR = "crawl_output";
    private static final String TEXT_DIR = "crawl_output/texts";

    private final List<CrawlResult> results;
    private String sessionId;

    public DataStorageModule() {
        this.results = new ArrayList<>();
        this.sessionId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        new File(OUTPUT_DIR).mkdirs();
        new File(TEXT_DIR).mkdirs();
    }

    public void clear() {
        synchronized (results) {
            results.clear();
            sessionId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        }
        // wipe text files from previous session
        File textDir = new File(TEXT_DIR);
        if (textDir.exists()) {
            File[] files = textDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.delete()) {
                        System.err.println("[STORAGE] Could not delete: " + f.getName());
                    }
                }
            }
        }
    }

    // write page text directly to disk as individual .txt files
    public void storePageText(String url, String text) {
        if (url == null || url.isBlank() || text == null) return;
        try {
            File dir = new File(TEXT_DIR);
            if (!dir.exists()) dir.mkdirs();
            String hash = sha256Hex(url).substring(0, 48);
            File file = new File(dir, hash + ".txt");
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
                pw.println("URL: " + url);
                pw.println("---");
                if (text.length() > 65536) {
                    pw.print(text.substring(0, 65536));
                    pw.println("\n[truncated at 64 KB]");
                } else {
                    pw.print(text);
                }
            }
        } catch (IOException e) {
            System.err.println("[STORAGE] Failed to write text for: " + url + " - " + e.getMessage());
        }
    }

    // read page text back from disk
    public String getPageText(String url) {
        if (url == null) return null;
        try {
            String hash = sha256Hex(url).substring(0, 48);
            File file = new File(TEXT_DIR, hash + ".txt");
            if (!file.exists()) return null;
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[STORAGE] Failed to read text for: " + url + " - " + e.getMessage());
            return null;
        }
    }

    // thread-safe result add
    public void addResult(CrawlResult result) {
        synchronized (results) {
            results.add(result);
        }
    }

    public List<CrawlResult> getAllResults() {
        return Collections.unmodifiableList(results);
    }

    public int getResultCount() {
        synchronized (results) {
            return results.size();
        }
    }

    // disk writes are immediate per-page, nothing to batch
    public void flush() { }

    // LLM corpus output in CommonCrawl/C4 JSONL schema
    public void flushLLMCorpus() {
        String filename = OUTPUT_DIR + "/corpus_" + sessionId + ".jsonl";
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(filename, false), StandardCharsets.UTF_8))) {
            synchronized (results) {
                for (CrawlResult r : results) {
                    String bodyText = readBodyText(r.url);
                    String domain = extractDomain(r.url);
                    String json = "{"
                        + "\"id\":" + escapeJSON(sha256Hex(r.url).substring(0, 16)) + ","
                        + "\"url\":" + escapeJSON(r.url) + ","
                        + "\"title\":" + escapeJSON(r.title) + ","
                        + "\"text\":" + escapeJSON(bodyText) + ","
                        + "\"crawl_depth\":" + r.depth + ","
                        + "\"keyword_match\":" + r.keywordMatch + ","
                        + "\"fetch_time_ms\":" + r.fetchTimeMs + ","
                        + "\"timestamp_utc\":" + escapeJSON(Instant.ofEpochMilli(r.timestamp).toString()) + ","
                        + "\"domain\":" + escapeJSON(domain) + ","
                        + "\"parent_url\":" + escapeJSON(r.parentUrl) + ","
                        + "\"char_count\":" + bodyText.length()
                        + "}";
                    pw.println(json);
                }
            }
            System.out.println("[CORPUS] LLM JSONL written -> " + filename + " (" + results.size() + " records)");
        } catch (IOException e) {
            System.err.println("[CORPUS] Failed to write JSONL: " + e.getMessage());
        }
    }

    // read body text from disk, stripping the URL header we prepend
    private String readBodyText(String url) {
        String raw = getPageText(url);
        if (raw == null) return "";
        int marker = raw.indexOf("---\n");
        if (marker >= 0) return raw.substring(marker + 4);
        return raw;
    }

    private static String extractDomain(String url) {
        try { return new java.net.URI(url).getHost(); }
        catch (Exception e) { return ""; }
    }

    // delta graph JSON for live dashboard polling
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

    static String escapeJSON(String value) {
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

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }
}