import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// FIX 6: Required Jsoup Dependency
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class HTTPFetcherHTMLParser {

    private static final int CONNECTION_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS       = 15000;
    private static final int MAX_BODY_SIZE         = 1_048_576;
    private static final int MAX_REDIRECTS         = 5;
    private static final String USER_AGENT         = "jWebCrawler/1.0 (+educational project)";

    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

    public static final class LinkWithText {
        public final String url;
        public final String anchorText;

        LinkWithText(String url, String anchorText) {
            this.url        = url;
            this.anchorText = anchorText;
        }
    }

    private HttpURLConnection openConnection(String urlString) throws IOException {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECTION_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        conn.setInstanceFollowRedirects(false); 
        return conn;
    }

    public String fetchPage(String urlString) {
        HttpURLConnection connection = null;
        try {
            String currentUrl = urlString;
            for (int redirects = 0; redirects < MAX_REDIRECTS; redirects++) {
                connection = openConnection(currentUrl);
                int status = connection.getResponseCode();

                if (status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null || location.isBlank()) return null;
                    currentUrl = resolveUrl(location, currentUrl);
                    if (currentUrl == null) return null;
                    continue;
                }

                if (status < 200 || status >= 400) return null;

                String contentType = connection.getContentType();
                if (contentType != null && !contentType.contains("text/html") && !contentType.contains("application/xhtml")) return null;

                String charset = "UTF-8";
                if (contentType != null) {
                    Matcher m = CHARSET_PATTERN.matcher(contentType);
                    if (m.find()) charset = m.group(1).trim();
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), charset));
                StringBuilder body = new StringBuilder();
                char[] buffer = new char[8192];
                int charsRead;
                int totalRead = 0;
                while ((charsRead = reader.read(buffer)) != -1) {
                    body.append(buffer, 0, charsRead);
                    totalRead += charsRead;
                    if (totalRead >= MAX_BODY_SIZE) break;
                }
                reader.close();
                return body.toString();
            }
            return null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // FIX 6: Actual DOM parsing
    public List<LinkWithText> extractLinksWithText(String html, String baseUrl) {
        List<LinkWithText> results = new ArrayList<>();
        if (html == null || html.isBlank()) return results;

        Document doc = Jsoup.parse(html, baseUrl);
        for (Element a : doc.select("a[href]")) {
            String abs  = a.attr("abs:href").strip();
            String text = a.text().strip();

            if (abs.isEmpty() || abs.equals("#")) continue;
            String lower = abs.toLowerCase();
            if (lower.startsWith("javascript:") || lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("data:")) continue;
            if (isBinaryExtension(lower)) continue;

            results.add(new LinkWithText(abs, text));
        }
        return results;
    }

    public List<String> extractLinks(String html, String baseUrl) {
        List<LinkWithText> pairs = extractLinksWithText(html, baseUrl);
        List<String> urls = new ArrayList<>(pairs.size());
        for (LinkWithText p : pairs) urls.add(p.url);
        return urls;
    }

    // FIX 6: Clean extraction
    public String extractTitle(String html) {
        if (html == null || html.isBlank()) return "[No Title]";
        String t = Jsoup.parse(html).title().strip().replaceAll("\\s+", " ");
        return t.isEmpty() ? "[No Title]" : t;
    }

    // FIX 6: Remove Regex execution
    public String extractPageText(String html) {
        if (html == null || html.isBlank()) return "";
        String text = Jsoup.parse(html).body().text();
        return text.length() > 10240 ? text.substring(0, 10240) : text;
    }

    private static boolean isBinaryExtension(String href) {
        int qIdx = href.indexOf('?');
        String pathOnly = qIdx >= 0 ? href.substring(0, qIdx) : href;
        return pathOnly.matches(".*\\.(jpg|jpeg|png|gif|svg|webp|ico|bmp|pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar|gz|tar|mp3|mp4|avi|mov|wmv|css|js|woff|woff2|ttf|eot|xml|rss|atom)$");
    }

    private String resolveUrl(String href, String baseUrl) {
        try {
            URI base     = new URI(baseUrl);
            URI resolved = base.resolve(new URI(href.replace(" ", "%20")));
            String scheme = resolved.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            return resolved.toString();
        } catch (Exception e) { return null; }
    }
}