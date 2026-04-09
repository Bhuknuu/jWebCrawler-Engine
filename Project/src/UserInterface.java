import java.util.List;
import java.util.Scanner;

/**
 * Handles CLI input and output for the crawler.
 * Provides interactive prompts with smart seed suggestions and formatted results.
 */
public class UserInterface {

    private static final String DEFAULT_SEED = "https://books.toscrape.com";
    private static final int DEFAULT_DEPTH = 2;

    /**
     * Curated seed suggestions organized by research domain.
     * Each entry: { purpose, seed URL, description }
     */
    private static final String[][] SEED_SUGGESTIONS = {
        {"General Web Crawl",
         "https://books.toscrape.com",
         "Safe sandbox for testing (1000 fake book pages, no rate limits)"},

        {"Academic Research",
         "https://en.wikipedia.org/wiki/Main_Page",
         "Wikipedia main page, largest open encyclopedia"},

        {"Computer Science",
         "https://en.wikipedia.org/wiki/Algorithm",
         "Graph theory, data structures, algorithm taxonomy"},

        {"Medical / Pharmacology",
         "https://en.wikipedia.org/wiki/Pharmacology",
         "Drug classifications, clinical trials, drug interactions"},

        {"Prehistoric Art / Archaeology",
         "https://en.wikipedia.org/wiki/Prehistoric_art",
         "Cave paintings, Venus figurines, megalithic art"},

        {"Art History Archive",
         "https://smarthistory.org",
         "Smarthistory (Khan Academy partner), museum-grade art essays"},

        {"Ancient Civilizations",
         "https://en.wikipedia.org/wiki/Ancient_history",
         "Mesopotamia, Egypt, Indus Valley, Greece, Rome"},

        {"Machine Learning / AI",
         "https://en.wikipedia.org/wiki/Machine_learning",
         "Supervised, unsupervised, reinforcement learning taxonomy"},

        {"Biology / Genetics",
         "https://en.wikipedia.org/wiki/Genetics",
         "DNA, gene expression, heredity, genomics"},

        {"Physics / Quantum",
         "https://en.wikipedia.org/wiki/Quantum_mechanics",
         "Wave functions, Schrodinger equation, particle physics"},

        {"Mathematics",
         "https://en.wikipedia.org/wiki/Mathematics",
         "Algebra, calculus, number theory, topology"},

        {"World History",
         "https://en.wikipedia.org/wiki/World_history",
         "Civilizations, wars, trade routes, cultural exchange"},

        {"Climate / Environment",
         "https://en.wikipedia.org/wiki/Climate_change",
         "Global warming, carbon cycle, renewable energy"},

        {"Space / Astronomy",
         "https://en.wikipedia.org/wiki/Astronomy",
         "Solar system, galaxies, cosmology, exoplanets"},

        {"Open Source Software",
         "https://github.com/trending",
         "Trending repos, developer tools, open source projects"},
    };

    /**
     * Runs the full interactive input flow.
     * Returns a String[4]: {seedUrl, maxDepth, keyword, maxPages}
     */
    public static String[] runInteractiveSetup(Scanner scanner) {
        printBanner();
        printSeedSuggestions();

        String seedUrl = promptSeedUrl(scanner);
        int maxDepth = promptDepth(scanner);
        int maxPages = promptMaxPages(scanner);
        String keyword = promptKeyword(scanner);

        return new String[] { seedUrl, String.valueOf(maxDepth), keyword, String.valueOf(maxPages) };
    }

    /**
     * Prints the welcome banner.
     */
    private static void printBanner() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════╗");
        System.out.println("  ║            Web Crawler  Engine           ║");
        System.out.println("  ╚══════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * Displays curated seed URL suggestions grouped by domain.
     */
    private static void printSeedSuggestions() {
        System.out.println("  Suggested Seeds (enter the number or type your own URL):");
        System.out.println("  --------------------------------------------------------");
        for (int i = 0; i < SEED_SUGGESTIONS.length; i++) {
            System.out.printf("   [%2d]  %-28s  %s%n",
                i + 1,
                SEED_SUGGESTIONS[i][0],
                SEED_SUGGESTIONS[i][2]);
            System.out.printf("         %s%n", SEED_SUGGESTIONS[i][1]);
        }
        System.out.println("  --------------------------------------------------------");
        System.out.printf("   [  ]  Press ENTER for default: %s%n", DEFAULT_SEED);
        System.out.println();
    }

    /**
     * Prompts for seed URL. Accepts:
     *   - A number (1-15) to pick from suggestions
     *   - A full URL starting with http/https
     *   - Empty input for the default
     */
    public static String promptSeedUrl(Scanner scanner) {
        System.out.print("  Seed URL (number, URL, or ENTER for default): ");
        String input = scanner.nextLine().strip();

        if (input.isEmpty()) {
            System.out.println("  Using default: " + DEFAULT_SEED);
            return DEFAULT_SEED;
        }

        // Check if it is a number referencing a suggestion
        try {
            int choice = Integer.parseInt(input);
            if (choice >= 1 && choice <= SEED_SUGGESTIONS.length) {
                String picked = SEED_SUGGESTIONS[choice - 1][1];
                System.out.println("  Selected: " + SEED_SUGGESTIONS[choice - 1][0]);
                System.out.println("  URL     : " + picked);
                return picked;
            }
        } catch (NumberFormatException ignored) {
            // Not a number, treat as URL
        }

        // Validate as URL
        String lower = input.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            System.out.println("  URL must start with http:// or https://");
            System.out.println("  Prepending https:// automatically.");
            input = "https://" + input;
        }

        return input;
    }

    /**
     * Prompts for depth with sensible defaults and explanation.
     */
    public static int promptDepth(Scanner scanner) {
        System.out.println();
        System.out.println("  Depth controls how many link-hops from the seed to explore:");
        System.out.println("    1 = seed page only + its direct links (~50-200 pages)");
        System.out.println("    2 = two levels deep (~500-5000 pages)");
        System.out.println("    3 = three levels deep (can be 10,000+ pages, slower)");
        System.out.println();
        System.out.print("  Max depth (0-10, ENTER for default " + DEFAULT_DEPTH + "): ");

        String input = scanner.nextLine().strip();
        if (input.isEmpty()) {
            System.out.println("  Using default depth: " + DEFAULT_DEPTH);
            return DEFAULT_DEPTH;
        }

        try {
            int depth = Integer.parseInt(input);
            if (depth < 0) {
                System.out.println("  Minimum depth is 0. Using 0.");
                return 0;
            }
            if (depth > 10) {
                System.out.println("  Maximum depth is 10. Using 10.");
                return 10;
            }
            return depth;
        } catch (NumberFormatException e) {
            System.out.println("  Invalid number. Using default depth: " + DEFAULT_DEPTH);
            return DEFAULT_DEPTH;
        }
    }

    /**
     * Prompts for an optional keyword filter with examples.
     */
    public static String promptKeyword(Scanner scanner) {
        System.out.println();
        System.out.println("  Keyword filtering marks pages that contain your search term.");
        System.out.println("  Examples: \"prehistoric paintings\", \"machine learning\", \"DNA\"");
        System.out.println();
        System.out.print("  Search keyword (ENTER to skip): ");

        String keyword = scanner.nextLine().strip();
        if (keyword.isEmpty()) {
            System.out.println("  No keyword filter applied.");
        } else {
            System.out.println("  Filtering for: \"" + keyword + "\"");
        }
        return keyword;
    }

    /**
     * Prompts for max pages to crawl.
     */
    public static int promptMaxPages(Scanner scanner) {
        System.out.println();
        System.out.println("  Max pages limits total pages crawled (prevents runaway BFS):");
        System.out.println("    50  = quick scan (~15 seconds)");
        System.out.println("    100 = moderate scan (~30 seconds)");
        System.out.println("    500 = deep scan (~3 minutes)");
        System.out.println();
        System.out.print("  Max pages (10-1000, ENTER for default 100): ");

        String input = scanner.nextLine().strip();
        if (input.isEmpty()) {
            System.out.println("  Using default: 100 pages");
            return 100;
        }
        try {
            int pages = Integer.parseInt(input);
            if (pages < 10) {
                System.out.println("  Minimum is 10. Using 10.");
                return 10;
            }
            if (pages > 1000) {
                System.out.println("  Maximum is 1000. Using 1000.");
                return 1000;
            }
            return pages;
        } catch (NumberFormatException e) {
            System.out.println("  Invalid number. Using default: 100 pages");
            return 100;
        }
    }

    /**
     * Prints the crawl configuration summary before starting.
     */
    public static void printCrawlConfig(String seedUrl, int maxDepth, String keyword, int maxPages) {
        System.out.println();
        System.out.println("  ========================================");
        System.out.println("    Crawl Configuration");
        System.out.println("  ========================================");
        System.out.println("    Seed URL  : " + seedUrl);
        System.out.println("    Depth     : " + maxDepth);
        System.out.println("    Max pages : " + maxPages);
        System.out.println("    Keyword   : " + (keyword.isEmpty() ? "(none)" : "\"" + keyword + "\""));
        System.out.println("    Scoping   : same-domain only");
        System.out.println("  ========================================");
    }

    /**
     * Asks the user to confirm before starting the crawl.
     */
    public static boolean confirmStart(Scanner scanner) {
        System.out.println();
        System.out.print("  Start crawling? (Y/n): ");
        String input = scanner.nextLine().strip().toLowerCase();
        return input.isEmpty() || input.equals("y") || input.equals("yes");
    }

    /**
     * Renders crawl results as a formatted CLI table.
     */
    public static void displayResults(List<DataStorageModule.CrawlResult> results) {
        if (results == null || results.isEmpty()) {
            System.out.println("\n  [INFO] No results to display.");
            return;
        }

        System.out.println();
        System.out.println("  ════════════════════════════════════════════════════════");
        System.out.println("    Crawl Results (" + results.size() + " pages)");
        System.out.println("  ════════════════════════════════════════════════════════");

        System.out.printf("  %-5s  %-50s  %-25s  %-5s  %-7s  %-5s%n",
            "#", "URL", "Title", "Depth", "Time", "Match");
        System.out.println("  " + "-".repeat(105));

        for (int i = 0; i < results.size(); i++) {
            DataStorageModule.CrawlResult r = results.get(i);
            System.out.printf("  %-5d  %-50s  %-25s  %-5d  %-7s  %-5s%n",
                i + 1,
                truncate(r.url, 50),
                truncate(r.title, 25),
                r.depth,
                r.fetchTimeMs + "ms",
                r.keywordMatch ? "YES" : ""
            );
        }

        System.out.println();
        printSummaryStats(results);
    }

    /**
     * Prints summary statistics after results table.
     */
    private static void printSummaryStats(List<DataStorageModule.CrawlResult> results) {
        long totalFetchTime = 0;
        int maxDepth = 0;
        int matchCount = 0;
        java.util.Set<String> domains = new java.util.HashSet<>();

        for (DataStorageModule.CrawlResult r : results) {
            totalFetchTime += r.fetchTimeMs;
            if (r.depth > maxDepth) maxDepth = r.depth;
            if (r.keywordMatch) matchCount++;
            String domain = extractDomain(r.url);
            if (domain != null) domains.add(domain);
        }

        double avgFetchTime = results.isEmpty() ? 0 : (double) totalFetchTime / results.size();

        System.out.println("  Summary:");
        System.out.printf("    Total pages crawled    : %d%n", results.size());
        System.out.printf("    Unique domains found   : %d%n", domains.size());
        System.out.printf("    Max depth reached      : %d%n", maxDepth);
        System.out.printf("    Keyword matches        : %d%n", matchCount);
        System.out.printf("    Avg fetch time         : %.0fms%n", avgFetchTime);
        System.out.printf("    Total fetch time       : %dms%n", totalFetchTime);

        int[] depthCounts = new int[maxDepth + 1];
        for (DataStorageModule.CrawlResult r : results) {
            depthCounts[r.depth]++;
        }
        System.out.println("    Depth distribution     :");
        for (int d = 0; d <= maxDepth; d++) {
            String bar = "█".repeat(Math.min(depthCounts[d], 50));
            System.out.printf("      Depth %d: %3d pages  %s%n", d, depthCounts[d], bar);
        }
        System.out.println();
    }

    private static String extractDomain(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }
}