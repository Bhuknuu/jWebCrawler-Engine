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

/**
 * Fetches web pages via HTTP and extracts hyperlinks from the HTML.
 * Uses java.net.HttpURLConnection (no external dependencies).
 * Link extraction uses regex pattern matching on anchor tags.
 */
public class HTTPFetcherHTMLParser {

    private static final int CONNECTION_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_BODY_SIZE = 1_048_576;
    private static final String USER_AGENT = "jWebCrawler/1.0 (+educational project)";

    // Pattern for <a href="..."> with both single and double quotes
    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
        "<a\\s+[^>]*href\\s*=\\s*[\"']([^\"'#][^\"']*)[\"']",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern for <title>...</title>
    private static final Pattern TITLE_PATTERN = Pattern.compile(
        "<title[^>]*>([^<]*)</title>",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Fetches the raw HTML content of a page via HTTP GET.
     * Returns null on any failure (timeout, bad status, non-HTML, etc).
     * Follows up to 5 redirects.
     */
    public String fetchPage(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(urlString).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            connection.setInstanceFollowRedirects(true);

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 400) {
                System.err.println("[WARN] HTTP " + statusCode + " for " + urlString);
                return null;
            }

            String contentType = connection.getContentType();
            if (contentType != null && !contentType.contains("text/html")
                    && !contentType.contains("application/xhtml")) {
                return null;
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), "UTF-8")
            );
            StringBuilder body = new StringBuilder();
            char[] buffer = new char[8192];
            int charsRead;
            int totalRead = 0;

            while ((charsRead = reader.read(buffer)) != -1) {
                body.append(buffer, 0, charsRead);
                totalRead += charsRead;
                if (totalRead >= MAX_BODY_SIZE) {
                    break;
                }
            }
            reader.close();

            return body.toString();

        } catch (IOException e) {
            System.err.println("[WARN] Failed to fetch " + urlString + ": " + e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            System.err.println("[WARN] Invalid URL format: " + urlString);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Extracts all valid hyperlinks from an HTML string.
     * Converts relative URLs to absolute using the base URL.
     * Filters out javascript:, mailto:, tel:, and data: schemes.
     */
    public List<String> extractLinks(String html, String baseUrl) {
        List<String> links = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return links;
        }

        Matcher matcher = ANCHOR_PATTERN.matcher(html);
        while (matcher.find()) {
            String href = matcher.group(1).strip();

            if (href.isEmpty()) {
                continue;
            }

            // Skip non-HTTP schemes
            String hrefLower = href.toLowerCase();
            if (hrefLower.startsWith("javascript:")
                    || hrefLower.startsWith("mailto:")
                    || hrefLower.startsWith("tel:")
                    || hrefLower.startsWith("data:")
                    || hrefLower.startsWith("ftp:")) {
                continue;
            }

            // Resolve relative URLs against the base
            String absoluteUrl = resolveUrl(href, baseUrl);
            if (absoluteUrl != null) {
                links.add(absoluteUrl);
            }
        }

        return links;
    }

    /**
     * Extracts the page title from HTML.
     * Returns "[No Title]" if no title tag is found.
     */
    public String extractTitle(String html) {
        if (html == null || html.isBlank()) {
            return "[No Title]";
        }

        Matcher matcher = TITLE_PATTERN.matcher(html);
        if (matcher.find()) {
            String title = matcher.group(1).strip();
            // Collapse whitespace
            title = title.replaceAll("\\s+", " ");
            return title.isEmpty() ? "[No Title]" : title;
        }
        return "[No Title]";
    }

    /**
     * Strips HTML tags and extracts visible text content.
     * Removes script and style blocks first, then all remaining tags.
     */
    public String extractPageText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        // Remove <script>...</script> blocks
        String cleaned = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        // Remove <style>...</style> blocks
        cleaned = cleaned.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        // Remove all HTML tags
        cleaned = cleaned.replaceAll("<[^>]+>", " ");
        // Decode common HTML entities
        cleaned = cleaned.replace("&amp;", "&")
                         .replace("&lt;", "<")
                         .replace("&gt;", ">")
                         .replace("&quot;", "\"")
                         .replace("&apos;", "'")
                         .replace("&nbsp;", " ");
        // Collapse whitespace
        cleaned = cleaned.replaceAll("\\s+", " ").strip();

        return cleaned;
    }

    /**
     * Resolves a possibly relative href against a base URL.
     * Returns the absolute URL as a String, or null if resolution fails.
     */
    private String resolveUrl(String href, String baseUrl) {
        try {
            URI base = new URI(baseUrl);
            URI resolved = base.resolve(href);

            String scheme = resolved.getScheme();
            if (scheme == null) {
                return null;
            }
            scheme = scheme.toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }

            return resolved.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
