import java.util.List;
import java.util.Scanner;

/**
 * Entry point for jWebCrawler-Engine.
 * Always runs interactive setup, then launches the BFS crawl.
 */
public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        String[] config = UserInterface.runInteractiveSetup(scanner);
        String seedUrl = config[0];
        int maxDepth = Integer.parseInt(config[1]);
        String keyword = config[2];

        UserInterface.printCrawlConfig(seedUrl, maxDepth, keyword);

        if (!UserInterface.confirmStart(scanner)) {
            System.out.println("\n  Crawl cancelled. Goodbye!");
            scanner.close();
            return;
        }

        System.out.println();

        URLManager urlManager = new URLManager();
        HTTPFetcherHTMLParser fetcher = new HTTPFetcherHTMLParser();
        DataStorageModule dataStore = new DataStorageModule();
        CrawlEngine crawlEngine = new CrawlEngine(urlManager, fetcher, dataStore);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n  [SHUTDOWN] Flushing results...");
            dataStore.flush();
        }));

        urlManager.addSeed(seedUrl);
        crawlEngine.startBFS(maxDepth, keyword);

        List<DataStorageModule.CrawlResult> results = dataStore.getAllResults();
        UserInterface.displayResults(results);

        dataStore.flush();
        scanner.close();
    }
}