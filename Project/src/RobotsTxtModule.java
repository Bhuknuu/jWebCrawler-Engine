import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RobotsTxtModule {

    private static class RobotRules {
        final List<String> disallowPaths;
        final List<String> allowPaths;
        final int crawlDelaySeconds;
        long lastFetchTimestamp;

        RobotRules(List<String> disallowPaths, List<String> allowPaths, int crawlDelaySeconds) {
            this.disallowPaths = disallowPaths;
            this.allowPaths = allowPaths;
            this.crawlDelaySeconds = crawlDelaySeconds;
            this.lastFetchTimestamp = 0;
        }

        static RobotRules empty() {
            return new RobotRules(new ArrayList<>(), new ArrayList<>(), 1);
        }
    }

    private static final int FETCH_TIMEOUT_MS = 3000;
    private final Map<String, RobotRules> domainRulesCache;

    public RobotsTxtModule() {
        this.domainRulesCache = new ConcurrentHashMap<>();
    }

    public boolean isAllowed(String urlString) {
        String domain = extractDomain(urlString);
        if (domain == null) return true;

        if (!domainRulesCache.containsKey(domain)) {
            RobotRules rules = fetchAndParse(domain, urlString);
            domainRulesCache.put(domain, rules);
        }

        RobotRules rules = domainRulesCache.get(domain);
        String path = extractPath(urlString);

        for (String allowPath : rules.allowPaths) {
            if (path.startsWith(allowPath)) return true;
        }

        for (String disallowPath : rules.disallowPaths) {
            if (path.startsWith(disallowPath)) return false;
        }

        return true;
    }

    // FIX 4: Asynchronous politeness checks. Skip unready URLs instead of sleeping.
    public boolean isPoliteToVisit(String urlString) {
        String domain = extractDomain(urlString);
        if (domain == null) return true;
        RobotRules rules = domainRulesCache.get(domain);
        if (rules == null || rules.lastFetchTimestamp == 0) return true;
        long required = rules.crawlDelaySeconds * 1000L;
        return (System.currentTimeMillis() - rules.lastFetchTimestamp) >= required;
    }

    public void recordVisit(String urlString) {
        String domain = extractDomain(urlString);
        if (domain == null) return;
        RobotRules rules = domainRulesCache.get(domain);
        if (rules != null) rules.lastFetchTimestamp = System.currentTimeMillis();
    }

    private RobotRules fetchAndParse(String domain, String originalUrl) {
        String scheme = "http";
        try {
            URI uri = new URI(originalUrl);
            if (uri.getScheme() != null) scheme = uri.getScheme().toLowerCase();
        } catch (Exception ignored) {}

        String robotsUrl = scheme + "://" + domain + "/robots.txt";

        try {
            URL url = URI.create(robotsUrl).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(FETCH_TIMEOUT_MS);
            connection.setReadTimeout(FETCH_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "jWebCrawler/1.0");

            int statusCode = connection.getResponseCode();
            if (statusCode != 200) {
                connection.disconnect();
                return RobotRules.empty();
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            List<String> disallowPaths = new ArrayList<>();
            List<String> allowPaths = new ArrayList<>();
            int crawlDelay = 1;
            boolean inOurBlock = false;

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.toLowerCase().startsWith("user-agent:")) {
                    String agent = line.substring(11).strip().toLowerCase();
                    inOurBlock = agent.equals("*") || agent.contains("jwebcrawler");
                    continue;
                }

                if (!inOurBlock) continue;

                if (line.toLowerCase().startsWith("disallow:")) {
                    String path = line.substring(9).strip();
                    if (!path.isEmpty()) disallowPaths.add(path);
                } else if (line.toLowerCase().startsWith("allow:")) {
                    String path = line.substring(6).strip();
                    if (!path.isEmpty()) allowPaths.add(path);
                } else if (line.toLowerCase().startsWith("crawl-delay:")) {
                    String delayStr = line.substring(12).strip();
                    try {
                        crawlDelay = (int) Math.ceil(Double.parseDouble(delayStr)); // Supports fractional delays safely
                        if (crawlDelay < 0) crawlDelay = 1;
                        if (crawlDelay > 30) crawlDelay = 30;
                    } catch (NumberFormatException ignored) {}
                }
            }

            reader.close();
            connection.disconnect();
            return new RobotRules(disallowPaths, allowPaths, crawlDelay);

        } catch (IOException e) { return RobotRules.empty(); }
    }

    private static String extractDomain(String urlString) {
        try {
            URI uri = new URI(urlString);
            return uri.getHost();
        } catch (Exception e) { return null; }
    }

    private static String extractPath(String urlString) {
        try {
            URI uri = new URI(urlString);
            String path = uri.getPath();
            return (path == null || path.isEmpty()) ? "/" : path;
        } catch (Exception e) { return "/"; }
    }
}